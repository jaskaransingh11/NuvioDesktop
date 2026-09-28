package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Bounded local diagnostics with strictly structured fields. Never persist
 * signed media URLs, API keys, request headers, raw exceptions or media titles.
 */
internal object DownloadAudit {
    internal enum class Event {
        START, PROGRESS, PAUSE, RESUME, CANCEL, COMPLETE, ERROR,
        RESTART_VALIDATED, RESUME_BLOCKED,
    }

    private const val MaxLogBytes = 1_048_576L
    private val lock = Any()
    private val sessionSalt = UUID.randomUUID().toString()
    private val directory get() = DesktopStorage.rootDir.resolve("logs")

    fun record(
        event: Event,
        downloadId: String,
        bytes: Long? = null,
        elapsedMs: Long? = null,
        errorKind: String? = null,
    ) {
        runCatching {
            val kind = errorKind?.takeIf {
                it in setOf("HTTP_403", "HTTP_416", "HTTP_429", "HTTP_5XX",
                    "TIMEOUT", "SOURCE_IDENTITY", "IO", "OTHER")
            }
            val job = MessageDigest.getInstance("SHA-256")
                .digest((sessionSalt + downloadId).toByteArray(StandardCharsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val line = buildString {
                append("{\"ts\":")
                append(System.currentTimeMillis())
                append(",\"event\":\"")
                append(event.name)
                append("\",\"job\":\"")
                append(job)
                append('"')
                if (bytes != null) append(",\"bytes\":").append(bytes.coerceAtLeast(0))
                if (elapsedMs != null) append(",\"elapsedMs\":").append(elapsedMs.coerceAtLeast(0))
                if (kind != null) append(",\"errorKind\":\"").append(kind).append('"')
                append("}\n")
            }
            synchronized(lock) {
                Files.createDirectories(directory)
                val file = directory.resolve("download-events.jsonl")
                if (Files.exists(file) && Files.size(file) + line.length > MaxLogBytes) {
                    Files.move(
                        file, directory.resolve("download-events.previous.jsonl"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                Files.write(
                    file,
                    line.toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    internal fun classify(detail: String?): String {
        val text = detail.orEmpty().lowercase()
        return when {
            "429" in text -> "HTTP_429"
            "416" in text -> "HTTP_416"
            "403" in text || "401" in text -> "HTTP_403"
            Regex("\\b5[0-9][0-9]\\b").containsMatchIn(text) -> "HTTP_5XX"
            "timeout" in text || "timed out" in text -> "TIMEOUT"
            "identity" in text || "validator" in text ||
                "etag" in text || "partial retained" in text -> "SOURCE_IDENTITY"
            "ioexception" in text || "connection" in text -> "IO"
            else -> "OTHER"
        }
    }

    internal fun safeErrorMessage(detail: String?): String = when (classify(detail)) {
        "HTTP_429" -> "Download rate limited (HTTP 429); partial retained"
        "HTTP_416" -> "Server rejected the requested byte range (HTTP 416); partial retained"
        "HTTP_403" -> "Download link denied or expired (HTTP 403/401); partial retained"
        "HTTP_5XX" -> "Download server error; partial retained"
        "TIMEOUT" -> "Download connection timed out; partial retained"
        "SOURCE_IDENTITY" -> "Saved partial could not be safely verified; partial retained"
        "IO" -> "Download connection failed; partial retained"
        else -> "Download failed; partial retained"
    }
}