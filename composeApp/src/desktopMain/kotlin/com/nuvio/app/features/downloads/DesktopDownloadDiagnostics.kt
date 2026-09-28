package com.nuvio.app.features.downloads

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object DesktopDownloadDiagnostics {
    private const val MaxLogBytes = 512L * 1024L
    private val lock = Any()

    fun log(
        event: String,
        destinationFileName: String,
        sourceUrl: String?,
        detail: String = "",
    ) {
        runCatching {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return
            val logDir = File(appData, "Nuvio/logs")
            synchronized(lock) {
                logDir.mkdirs()
                val current = File(logDir, "downloads.log")
                rotateIfNeeded(current)

                val host = sourceUrl
                    ?.let { runCatching { URI(it).host.orEmpty() }.getOrDefault("") }
                    .orEmpty()
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(100)
                val line = buildString {
                    append(System.currentTimeMillis())
                    append(" event=")
                    append(sanitizeToken(event, 48))
                    append(" key=")
                    append(shortHash(destinationFileName))
                    append(" host=")
                    append(host)
                    if (detail.isNotBlank()) {
                        append(" ")
                        append(sanitizeDiagnosticDetail(detail))
                    }
                    append('\n')
                }
                current.appendText(line, Charsets.UTF_8)
            }
        }
    }

    private fun rotateIfNeeded(current: File) {
        if (!current.exists() || current.length() < MaxLogBytes) return
        val rotated = File(current.parentFile, "downloads.log.1")
        if (rotated.exists()) rotated.delete()
        current.renameTo(rotated)
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}

internal fun sanitizeDiagnosticDetail(value: String): String =
    value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("""(?i)https?://\S+"""), "[url]")
        .replace(Regex("""(?i)(bearer|token|api[_-]?key|authorization|password)=\S+"""), "$1=[redacted]")
        .take(240)

private fun sanitizeToken(value: String, maxLength: Int): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(maxLength)