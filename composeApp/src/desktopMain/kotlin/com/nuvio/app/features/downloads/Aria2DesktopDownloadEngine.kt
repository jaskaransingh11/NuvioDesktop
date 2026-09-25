package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal object Aria2DesktopDownloadEngine : DesktopDownloadEngine {
    fun isAvailable(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true) &&
            Aria2RpcDaemon.findExecutable() != null

    fun initialize() {
        Aria2RpcDaemon.ensureStarted()
    }

    override fun start(
        request: DownloadPlatformRequest,
        destination: File,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        var gid: String? = null

        scope.launch {
            try {
                val rpc = Aria2RpcDaemon.ensureStarted()
                gid = rpc.addUri(
                    sourceUrl = request.sourceUrl,
                    sourceHeaders = request.sourceHeaders,
                    directory = tempFile.parentFile,
                    outputFileName = tempFile.name,
                )

                while (true) {
                    val activeGid = gid ?: error("Missing aria2 download id")
                    val status = rpc.tellStatus(activeGid)
                    val downloadedBytes = status.completedLength
                    val totalBytes = status.totalLength
                    onProgress(downloadedBytes, totalBytes)

                    when (status.status) {
                        "complete" -> {
                            finalizeDownload(tempFile, destination)
                            rpc.removeDownloadResult(activeGid)
                            val finalSize = destination.length()
                            onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
                            return@launch
                        }

                        "error", "removed" -> {
                            rpc.removeDownloadResult(activeGid)
                            error(status.errorMessage ?: "aria2 download failed (status=${status.status})")
                        }
                    }

                    delay(StatusPollIntervalMs)
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    gid?.let { activeGid ->
                        runCatching { Aria2RpcDaemon.ensureStarted().forcePause(activeGid) }
                    }
                }
                onPaused()
                throw error
            } catch (error: Throwable) {
                onFailure(error.message ?: "aria2 download failed")
            }
        }

        return object : DownloadsTaskHandle {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    private fun finalizeDownload(tempFile: File, destination: File) {
        if (!tempFile.exists()) {
            error("aria2 completed without producing ${tempFile.name}")
        }
        if (destination.exists()) {
            destination.delete()
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }
    }

    private const val StatusPollIntervalMs = 500L
}

private object Aria2RpcDaemon {
    private val lock = Any()

    @Volatile
    private var current: Aria2RpcClient? = null

    fun findExecutable(): File? {
        val candidates = buildList {
            System.getenv("NUVIO_ARIA2_PATH")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            val home = System.getProperty("user.home").orEmpty()
            if (home.isNotBlank()) {
                add(File(home, "tools/aria2/aria2c.exe").absolutePath)
                add(File(home, "scoop/apps/aria2/current/aria2c.exe").absolutePath)
            }
            System.getenv("ProgramData")
                ?.takeIf { it.isNotBlank() }
                ?.let { add(File(it, "chocolatey/bin/aria2c.exe").absolutePath) }
        }

        candidates.asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?.let { return it }

        return findOnPath()
    }

    fun ensureStarted(): Aria2RpcClient {
        current?.takeIf { it.isAlive() }?.let { return it }

        synchronized(lock) {
            current?.takeIf { it.isAlive() }?.let { return it }

            val executable = findExecutable()
                ?: error(
                    "aria2c.exe was not found. Set NUVIO_ARIA2_PATH or install it under ~/tools/aria2.",
                )
            val port = freeLoopbackPort()
            val secret = UUID.randomUUID().toString()
            val process = ProcessBuilder(
                executable.absolutePath,
                "--enable-rpc=true",
                "--rpc-listen-all=false",
                "--rpc-listen-port=$port",
                "--rpc-secret=$secret",
                "--rpc-allow-origin-all=false",
                "--console-log-level=warn",
                "--summary-interval=0",
                "--download-result=hide",
                "--max-concurrent-downloads=3",
                "--file-allocation=none",
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

            val client = Aria2RpcClient(
                endpoint = URI("http://127.0.0.1:$port/jsonrpc"),
                secret = secret,
                process = process,
            )
            client.awaitReady()
            current = client
            return client
        }
    }

    private fun findOnPath(): File? =
        runCatching {
            val process = ProcessBuilder("where.exe", "aria2c.exe")
                .redirectErrorStream(true)
                .start()
            val first = process.inputStream.bufferedReader().useLines { lines ->
                lines.firstOrNull()?.trim()
            }
            process.waitFor()
            first?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
        }.getOrNull()

    private fun freeLoopbackPort(): Int =
        ServerSocket(0).use { socket -> socket.localPort }
}

private class Aria2RpcClient(
    private val endpoint: URI,
    private val secret: String,
    private val process: Process,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val requestId = AtomicLong(0L)
    private val json = Json { ignoreUnknownKeys = true }

    fun isAlive(): Boolean =
        process.isAlive && runCatching { getVersion(); true }.getOrDefault(false)

    fun awaitReady() {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                error("aria2c exited before its RPC server became ready")
            }
            try {
                getVersion()
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100)
            }
        }
        process.destroyForcibly()
        throw IllegalStateException("aria2 RPC did not become ready", lastError)
    }

    fun addUri(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        directory: File,
        outputFileName: String,
    ): String {
        val options = buildJsonObject {
            put("dir", JsonPrimitive(directory.absolutePath))
            put("out", JsonPrimitive(outputFileName))
            put("continue", JsonPrimitive("true"))
            put("allow-overwrite", JsonPrimitive("true"))
            put("auto-file-renaming", JsonPrimitive("false"))
            put("split", JsonPrimitive("4"))
            put("max-connection-per-server", JsonPrimitive("4"))
            put("min-split-size", JsonPrimitive("16M"))
            put("max-tries", JsonPrimitive("10"))
            put("retry-wait", JsonPrimitive("3"))
            put("connect-timeout", JsonPrimitive("15"))
            put("timeout", JsonPrimitive("60"))
            if (sourceHeaders.isNotEmpty()) {
                put(
                    "header",
                    JsonArray(
                        sourceHeaders
                            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
                            .map { (key, value) -> JsonPrimitive("$key: $value") },
                    ),
                )
            }
        }
        return call(
            method = "aria2.addUri",
            params = listOf(
                JsonArray(listOf(JsonPrimitive(sourceUrl))),
                options,
            ),
        ).jsonPrimitive.content
    }

    fun tellStatus(gid: String): Aria2Status {
        val result = call(
            method = "aria2.tellStatus",
            params = listOf(
                JsonPrimitive(gid),
                JsonArray(
                    listOf(
                        "status",
                        "totalLength",
                        "completedLength",
                        "errorCode",
                        "errorMessage",
                    ).map(::JsonPrimitive),
                ),
            ),
        ).jsonObject

        return Aria2Status(
            status = result.string("status").orEmpty(),
            totalLength = result.string("totalLength")
                ?.toLongOrNull()
                ?.takeIf { it > 0L },
            completedLength = result.string("completedLength")
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L,
            errorMessage = result.string("errorMessage")
                ?.takeIf { it.isNotBlank() }
                ?: result.string("errorCode")
                    ?.takeIf { it.isNotBlank() && it != "0" }
                    ?.let { "aria2 error code $it" },
        )
    }

    fun forcePause(gid: String) {
        call("aria2.forcePause", listOf(JsonPrimitive(gid)))
    }

    fun removeDownloadResult(gid: String) {
        runCatching { call("aria2.removeDownloadResult", listOf(JsonPrimitive(gid))) }
    }

    @Suppress("unused")
    fun replaceUri(gid: String, oldUrl: String, newUrl: String) {
        call(
            method = "aria2.changeUri",
            params = listOf(
                JsonPrimitive(gid),
                JsonPrimitive(1),
                JsonArray(listOf(JsonPrimitive(oldUrl))),
                JsonArray(listOf(JsonPrimitive(newUrl))),
            ),
        )
    }

    private fun getVersion(): JsonElement =
        call("aria2.getVersion")

    private fun call(
        method: String,
        params: List<JsonElement> = emptyList(),
    ): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(requestId.incrementAndGet().toString()))
            put("method", JsonPrimitive(method))
            put(
                "params",
                JsonArray(listOf(JsonPrimitive("token:$secret")) + params),
            )
        }
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("aria2 RPC failed with HTTP ${response.statusCode()}")
        }

        val root = json.parseToJsonElement(response.body()).jsonObject
        root["error"]?.takeUnless { it is JsonNull }?.jsonObject?.let { errorObject ->
            val code = errorObject["code"]?.jsonPrimitive?.contentOrNull
            val message = errorObject["message"]?.jsonPrimitive?.contentOrNull
            error("aria2 RPC error${code?.let { " $it" }.orEmpty()}: ${message ?: "unknown error"}")
        }
        return root["result"] ?: JsonNull
    }
}

private data class Aria2Status(
    val status: String,
    val totalLength: Long?,
    val completedLength: Long,
    val errorMessage: String?,
)

private fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull
