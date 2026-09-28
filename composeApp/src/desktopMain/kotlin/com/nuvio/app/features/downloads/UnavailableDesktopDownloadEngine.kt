package com.nuvio.app.features.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Windows production policy: preserve existing downloaded bytes when the
 * verified aria2 backend is unavailable. This implementation opens no URL,
 * creates no file, and cannot silently switch to the unsafe legacy engine.
 */
internal class UnavailableDesktopDownloadEngine : DesktopDownloadEngine {
    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        CoroutineScope(job + Dispatchers.IO).launch {
            onFailure(VERIFIED_DOWNLOADER_UNAVAILABLE)
        }
        return object : DownloadsTaskHandle {
            override fun cancel() {
                job.cancel()
            }
        }
    }
}

internal const val VERIFIED_DOWNLOADER_UNAVAILABLE =
    "Verified downloader unavailable: aria2 could not start; existing files preserved"

internal fun mustStopOnBackendUnavailable(message: String): Boolean =
    message.startsWith("Verified downloader unavailable:")
