package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * A .part file by itself does not prove which bytes are inside it. Preserve
 * old partials unless source, strong HTTP validator, total length, selected
 * torrent identity (if URL changed), and aria2 control all verify.
 * No URL, credentials, request headers, or media titles are saved here.
 */
@Serializable
internal data class VerifiedResumeBinding(
    val version: Int = 1,
    val sourceSha256: String,
    val selectedTorrentSha256: String? = null,
    val strongEtag: String,
    val contentLength: Long,
)

internal data class RangeSourceProof(
    val strongEtag: String?,
    val contentLength: Long?,
    val respondsWithExactRange: Boolean,
)

internal fun canUseVerifiedPartial(
    stored: VerifiedResumeBinding?,
    currentSourceSha256: String,
    selectedTorrentSha256: String?,
    probe: RangeSourceProof?,
    controlFileExists: Boolean,
): Boolean {
    if (stored?.version != 1 || !controlFileExists || probe?.respondsWithExactRange != true) return false
    if (stored.contentLength <= 0 || probe.contentLength != stored.contentLength) return false
    if (stored.strongEtag.isBlank() || stored.strongEtag.startsWith("W/")) return false
    if (probe.strongEtag != stored.strongEtag) return false
    if (stored.sourceSha256 == currentSourceSha256) return true
    // An ETag from another URL is insufficient without the same torrent file.
    return stored.selectedTorrentSha256 != null &&
        stored.selectedTorrentSha256 == selectedTorrentSha256
}

internal class VerifiedResumeSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    internal data class Decision(
        val resumeExistingPartial: Boolean,
        val ifRangeEtag: String?,
    )

    fun inspectAndBind(request: DownloadPlatformRequest, part: File): Decision {
        val control = File(part.absolutePath + ".aria2")
        val identityFile = File(part.absolutePath + ".nuvio-identity.json")
        val hasBytes = part.exists() && part.length() > 0L
        val urlDigest = hash(request.sourceUrl + "\n" + request.sourceHeaders.toSortedMap().toString())
        val selectedTorrent = torrentSelectionIdentity(request.item)
        val proof = inspectRange(request.sourceUrl, request.sourceHeaders)
        if (hasBytes) {
            val old = readIdentity(identityFile)
            check(canUseVerifiedPartial(old, urlDigest, selectedTorrent, proof, control.isFile)) {
                "Safe resume unavailable: source identity or aria2 control could not be verified; partial retained"
            }
            // Only update the URL digest AFTER matching the old content.
            if (old!!.sourceSha256 != urlDigest) {
                writeIdentity(identityFile, old.copy(sourceSha256 = urlDigest))
            }
            return Decision(resumeExistingPartial = true, ifRangeEtag = old.strongEtag)
        }
        // Fresh transfers may use servers without validators, but a subsequent
        // process restart cannot safely reuse their nonempty partials.
        if (proof?.respondsWithExactRange == true &&
            proof.strongEtag != null && proof.contentLength != null && proof.contentLength > 0L
        ) {
            writeIdentity(
                identityFile,
                VerifiedResumeBinding(
                    sourceSha256 = urlDigest,
                    selectedTorrentSha256 = selectedTorrent,
                    strongEtag = proof.strongEtag,
                    contentLength = proof.contentLength,
                ),
            )
        } else {
            Files.deleteIfExists(identityFile.toPath())
        }
        return Decision(resumeExistingPartial = false, ifRangeEtag = null)
    }

    fun forget(part: File) {
        val target = File(part.absolutePath + ".nuvio-identity.json")
        runCatching { Files.deleteIfExists(target.toPath()) }
    }

    private fun readIdentity(path: File): VerifiedResumeBinding? = runCatching {
        if (!path.isFile || path.length() !in 1L..4096L) return@runCatching null
        json.decodeFromString<VerifiedResumeBinding>(path.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun writeIdentity(path: File, binding: VerifiedResumeBinding) {
        val temp = File(path.parentFile, path.name + ".tmp")
        temp.writeText(json.encodeToString(binding), Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(), path.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun inspectRange(url: String, headers: Map<String, String>): RangeSourceProof? =
        runCatching {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(12))
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
            headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank() &&
                    key.none { it == '\r' || it == '\n' } &&
                    value.none { it == '\r' || it == '\n' } &&
                    listOf("Range", "If-Range", "Accept-Encoding", "Host")
                        .none { it.equals(key, ignoreCase = true) }
                ) {
                    request.header(key, value)
                }
            }
            val response = client.send(
                request.GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            response.body().use {
                val range = response.headers().firstValue("Content-Range").orElse("")
                val length = Regex("^bytes 0-0/([0-9]+)$", RegexOption.IGNORE_CASE)
                    .matchEntire(range.trim())?.groupValues?.get(1)?.toLongOrNull()
                val encoding = response.headers().firstValue("Content-Encoding").orElse("identity")
                val rangeOk = response.statusCode() == 206 && length != null && length > 0L &&
                    encoding.equals("identity", ignoreCase = true)
                val etag = response.headers().firstValue("ETag").orElse("").trim()
                    .takeIf { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") &&
                        it.none { c -> c == '\n' || c == '\r' } }
                RangeSourceProof(strongEtag = etag, contentLength = length,
                    respondsWithExactRange = rangeOk)
            }
        }.getOrNull()

    private fun torrentSelectionIdentity(item: DownloadItem): String? {
        val source = item.sourceResolve ?: return null
        val info = source.infoHash?.trim()?.lowercase()?.takeIf {
            it.matches(Regex("^[a-f0-9]{40}$|^[a-f0-9]{64}$"))
        } ?: return null
        val index = source.fileIdx?.takeIf { it >= 0 } ?: return null
        val file = source.filename?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val provider = source.service?.trim()?.lowercase().orEmpty()
        return hash("$provider|$info|$index|$file")
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}