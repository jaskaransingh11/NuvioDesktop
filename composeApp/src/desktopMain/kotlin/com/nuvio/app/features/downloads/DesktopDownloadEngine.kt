package com.nuvio.app.features.downloads

import java.io.File

/**
 * Desktop transfer engine used by [DownloadsPlatformDownloader].
 *
 * File-system ownership remains with the platform downloader while the engine
 * owns the byte-transfer lifecycle. Keeping the seam small makes it possible
 * to add alternative desktop transfer backends without changing repository or
 * UI download state management.
 */
internal interface DesktopDownloadEngine {
    fun start(
        request: DownloadPlatformRequest,
        destination: File,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle
}

internal fun createDesktopDownloadEngine(): DesktopDownloadEngine {
    if (!Aria2DesktopDownloadEngine.isAvailable()) return HttpDesktopDownloadEngine
    return runCatching {
        Aria2DesktopDownloadEngine.initialize()
        Aria2DesktopDownloadEngine
    }.getOrDefault(HttpDesktopDownloadEngine)
}
