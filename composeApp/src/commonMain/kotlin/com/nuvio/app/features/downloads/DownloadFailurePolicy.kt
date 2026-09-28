package com.nuvio.app.features.downloads

internal enum class DownloadFailureAction {
    RefreshSource,
    RetrySameSource,
    Fail,
}

internal fun classifyDownloadFailure(message: String): DownloadFailureAction {
    val normalized = message.lowercase()
    val httpStatus = Regex("""(?:http|status(?:code)?[=:\s]*)\s*(\d{3})""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    return when {
        httpStatus in setOf(400, 401, 403, 404, 410, 416) -> DownloadFailureAction.RefreshSource
        httpStatus == 408 || httpStatus == 425 || httpStatus == 429 || (httpStatus != null && httpStatus in 500..599) ->
            DownloadFailureAction.RetrySameSource
        normalized.contains("timed out") ||
            normalized.contains("timeout") ||
            normalized.contains("connection reset") ||
            normalized.contains("temporarily unavailable") ||
            normalized.contains("connection refused") ->
            DownloadFailureAction.RetrySameSource
        else -> DownloadFailureAction.Fail
    }
}

internal fun downloadRetryDelayMs(attempt: Int): Long =
    when (attempt.coerceAtLeast(1)) {
        1 -> 1_000L
        2 -> 3_000L
        else -> 8_000L
    }