package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class Aria2DesktopDownloadEngine private constructor(
    private val sidecar: Aria2Sidecar,
    private val downloadsDirectory: () -> File,
) : DesktopDownloadEngine {
    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            val directory = downloadsDirectory()
            val destination = File(directory, request.destinationFileName)
            val partial = File(directory, "${request.destinationFileName}.part")

            try {
                val gid = sidecar.startOrResume(
                    key = request.destinationFileName,
                    url = request.sourceUrl,
                    headers = request.sourceHeaders,
                    directory = directory,
                    outputFileName = partial.name,
                )

                while (isActive) {
                    val status = sidecar.tellStatus(gid)
                    val downloaded = status.completedLength.coerceAtLeast(0L)
                    val total = status.totalLength?.takeIf { it > 0L }
                    onProgress(downloaded, total)

                    when (status.status) {
                        "complete" -> {
                            sidecar.forget(request.destinationFileName, gid)
                            if (destination.exists()) destination.delete()
                            if (!partial.renameTo(destination)) {
                                partial.copyTo(destination, overwrite = true)
                                partial.delete()
                            }
                            File(partial.absolutePath + ".aria2").delete()
                            val finalSize = destination.length()
                            onSuccess(destination.toURI().toString(), total ?: finalSize)
                            return@launch
                        }

                        "error", "removed" -> {
                            sidecar.forget(request.destinationFileName, gid)
                            val detail = status.errorMessage?.takeIf { it.isNotBlank() }
                                ?: status.errorCode?.let { "aria2 error code $it" }
                                ?: "aria2 download failed"
                            onFailure(detail)
                            return@launch
                        }

                        "paused" -> {
                            onPaused()
                            return@launch
                        }
                    }

                    delay(ARIA2_POLL_INTERVAL_MS)
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { sidecar.pause(request.destinationFileName) }
                }
                onPaused()
                throw error
            } catch (error: Throwable) {
                onFailure(error.message ?: "aria2 download failed")
            }
        }

        return Aria2DownloadsTaskHandle(job)
    }

    fun discard(destinationFileName: String) {
        runBlocking(Dispatchers.IO) {
            runCatching { sidecar.remove(destinationFileName) }
        }
    }

    companion object {
        fun createOrNull(downloadsDirectory: () -> File): Aria2DesktopDownloadEngine? {
            if (!System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
                return null
            }
            val executable = locateAria2Executable() ?: return null
            return runCatching {
                Aria2DesktopDownloadEngine(
                    sidecar = Aria2Sidecar.start(executable),
                    downloadsDirectory = downloadsDirectory,
                )
            }.getOrNull()
        }
    }
}

private class Aria2DownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private data class Aria2Status(
    val status: String,
    val totalLength: Long?,
    val completedLength: Long,
    val errorCode: String?,
    val errorMessage: String?,
)

private class Aria2Sidecar private constructor(
    private val process: Process,
    private val port: Int,
    private val secret: String,
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val requestIds = AtomicLong(0L)
    private val gidsByKey = ConcurrentHashMap<String, String>()

    suspend fun startOrResume(
        key: String,
        url: String,
        headers: Map<String, String>,
        directory: File,
        outputFileName: String,
    ): String {
        val existing = gidsByKey[key]
        if (existing != null) {
            runCatching {
                changeUri(existing, url)
                rpc("aria2.unpause", JsonArray(listOf(token(), JsonPrimitive(existing))))
                return existing
            }
            gidsByKey.remove(key, existing)
        }

        val headerValues = headers
            .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
            .map { (name, value) -> JsonPrimitive("$name: $value") }

        val options = buildJsonObject {
            put("dir", JsonPrimitive(directory.absolutePath))
            put("out", JsonPrimitive(outputFileName))
            put("continue", JsonPrimitive("true"))
            put("split", JsonPrimitive("4"))
            put("max-connection-per-server", JsonPrimitive("4"))
            put("min-split-size", JsonPrimitive("16M"))
            put("max-tries", JsonPrimitive("8"))
            put("retry-wait", JsonPrimitive("3"))
            put("connect-timeout", JsonPrimitive("15"))
            put("timeout", JsonPrimitive("60"))
            put("auto-file-renaming", JsonPrimitive("false"))
            put("allow-overwrite", JsonPrimitive("true"))
            put("file-allocation", JsonPrimitive("none"))
            if (headerValues.isNotEmpty()) {
                put("header", JsonArray(headerValues))
            }
        }

        val result = rpc(
            method = "aria2.addUri",
            params = buildJsonArray {
                add(token())
                add(JsonArray(listOf(JsonPrimitive(url))))
                add(options)
            },
        )
        val gid = result?.jsonPrimitive?.contentOrNull
            ?: error("aria2 did not return a download id")
        gidsByKey[key] = gid
        return gid
    }

    suspend fun tellStatus(gid: String): Aria2Status {
        val fields = JsonArray(
            listOf("status", "totalLength", "completedLength", "errorCode", "errorMessage")
                .map(::JsonPrimitive),
        )
        val result = rpc(
            "aria2.tellStatus",
            JsonArray(listOf(token(), JsonPrimitive(gid), fields)),
        )?.jsonObject ?: error("aria2 returned an invalid status response")

        return Aria2Status(
            status = result.string("status").orEmpty(),
            totalLength = result.string("totalLength")?.toLongOrNull(),
            completedLength = result.string("completedLength")?.toLongOrNull() ?: 0L,
            errorCode = result.string("errorCode"),
            errorMessage = result.string("errorMessage"),
        )
    }

    suspend fun pause(key: String) {
        val gid = gidsByKey[key] ?: return
        runCatching {
            rpc("aria2.forcePause", JsonArray(listOf(token(), JsonPrimitive(gid))))
        }
    }

    suspend fun remove(key: String) {
        val gid = gidsByKey.remove(key) ?: return
        runCatching {
            rpc("aria2.forceRemove", JsonArray(listOf(token(), JsonPrimitive(gid))))
        }
        runCatching {
            rpc("aria2.removeDownloadResult", JsonArray(listOf(token(), JsonPrimitive(gid))))
        }
    }

    fun forget(key: String, gid: String) {
        gidsByKey.remove(key, gid)
    }

    private suspend fun changeUri(gid: String, url: String) {
        rpc(
            "aria2.changeUri",
            buildJsonArray {
                add(token())
                add(JsonPrimitive(gid))
                add(JsonPrimitive(1))
                add(JsonArray(emptyList()))
                add(JsonArray(listOf(JsonPrimitive(url))))
            },
        )
    }

    private fun token(): JsonPrimitive = JsonPrimitive("token:$secret")

    private suspend fun rpc(method: String, params: JsonArray): JsonElement? =
        withContext(Dispatchers.IO) {
            check(process.isAlive) { "aria2 sidecar is not running" }
            val id = requestIds.incrementAndGet()
            val payload = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(id))
                put("method", JsonPrimitive(method))
                put("params", params)
            }.toString()
            val request = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/jsonrpc"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("aria2 RPC HTTP ${response.statusCode()}")
            }
            val body = json.parseToJsonElement(response.body()).jsonObject
            val rpcError = body["error"]?.jsonObject
            if (rpcError != null) {
                error(rpcError.string("message") ?: "aria2 RPC failed")
            }
            body["result"]
        }

    companion object {
        fun start(executable: File): Aria2Sidecar {
            val port = ServerSocket(0).use { it.localPort }
            val secret = UUID.randomUUID().toString()
            val process = ProcessBuilder(
                executable.absolutePath,
                "--enable-rpc=true",
                "--rpc-listen-all=false",
                "--rpc-listen-port=$port",
                "--rpc-secret=$secret",
                "--max-concurrent-downloads=2",
                "--file-allocation=none",
                "--console-log-level=warn",
                "--summary-interval=0",
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            val sidecar = Aria2Sidecar(process, port, secret)
            runBlocking(Dispatchers.IO) {
                var ready = false
                repeat(30) {
                    if (ready) return@repeat
                    if (!process.isAlive) error("aria2 exited during startup")
                    ready = runCatching {
                        sidecar.rpc("aria2.getVersion", JsonArray(listOf(sidecar.token())))
                        true
                    }.getOrDefault(false)
                    if (!ready) delay(100L)
                }
                check(ready) { "aria2 RPC did not become ready" }
            }
            return sidecar
        }
    }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun locateAria2Executable(): File? {
    val candidates = buildList {
        System.getenv("NUVIO_ARIA2_PATH")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.let(::add)

        System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, "Nuvio/tools/aria2c.exe") }
            ?.let(::add)

        System.getenv("ChocolateyInstall")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, "bin/aria2c.exe") }
            ?.let(::add)
    }

    candidates.firstOrNull { it.isFile }?.let { return it }

    val pathResult = runCatching {
        ProcessBuilder("where.exe", "aria2c.exe")
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val first = process.inputStream.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.isNotBlank() }
                }
                process.waitFor()
                first
            }
    }.getOrNull()

    return pathResult?.trim()?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
}

private const val ARIA2_POLL_INTERVAL_MS = 500L
