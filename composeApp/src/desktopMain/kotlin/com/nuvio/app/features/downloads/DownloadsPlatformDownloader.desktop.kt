package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.io.path.createDirectories

private val downloadsDirectoryProvider: () -> File = {
    File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())
}

private val desktopDownloadEngine: DesktopDownloadEngine by lazy {
    Aria2DesktopDownloadEngine.createOrNull(downloadsDirectoryProvider)
        ?: HttpDesktopDownloadEngine(downloadsDirectoryProvider)
}

internal actual object DownloadsPlatformDownloader {
    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle = desktopDownloadEngine.start(
        request = request,
        onProgress = onProgress,
        onSuccess = onSuccess,
        onFailure = onFailure,
        onPaused = onPaused,
    )

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        (desktopDownloadEngine as? Aria2DesktopDownloadEngine)?.discard(destinationFileName)
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        val controlFile = File(downloadsDir, "$destinationFileName.part.aria2")
        val tempRemoved = !tempFile.exists() || runCatching { tempFile.delete() }.getOrDefault(false)
        val controlRemoved = !controlFile.exists() || runCatching { controlFile.delete() }.getOrDefault(false)
        return tempRemoved && controlRemoved
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val directory = downloadsDir
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }
}

private fun openDirectoryWithPlatformCommand(directory: File): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", directory.absolutePath)
        osName.contains("win") -> listOf("explorer", directory.absolutePath)
        else -> listOf("xdg-open", directory.absolutePath)
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
