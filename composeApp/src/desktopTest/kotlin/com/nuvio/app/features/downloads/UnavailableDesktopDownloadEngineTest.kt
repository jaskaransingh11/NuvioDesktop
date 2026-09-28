package com.nuvio.app.features.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnavailableDesktopDownloadEngineTest {
    @Test
    fun missing_aria2_reports_an_actionable_error_without_touching_a_partial() = runBlocking {
        val dir = Files.createTempDirectory("jj-unavailable-downloader-").toFile()
        try {
            val partial = dir.resolve("fixture.wav.part")
            val original = byteArrayOf(1, 2, 3, 4, 5)
            partial.writeBytes(original)
            val observedFailure = CompletableDeferred<String>()
            val request = DownloadPlatformRequest(
                item = DownloadItem(
                    id = "fixture", contentType = "movie", parentMetaId = "fixture",
                    parentMetaType = "movie", videoId = "fixture", title = "Synthetic",
                    streamTitle = "Fixture", providerName = "Local", sourceUrl = "http://127.0.0.1:1/fixture.wav",
                    fileName = "fixture.wav", status = DownloadStatus.Paused,
                    createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
                ),
            )
            val handle = UnavailableDesktopDownloadEngine().start(
                request = request, onProgress = { _, _ -> error("unexpected progress") },
                onSuccess = { _, _ -> error("unexpected success") },
                onFailure = { observedFailure.complete(it) }, onPaused = { error("unexpected pause") },
            )
            assertEquals(VERIFIED_DOWNLOADER_UNAVAILABLE, withTimeout(5_000) { observedFailure.await() })
            assertTrue(mustStopOnBackendUnavailable(VERIFIED_DOWNLOADER_UNAVAILABLE))
            assertFalse(mustStopOnBackendUnavailable("HTTP 403"))
            assertTrue(partial.exists())
            assertTrue(partial.readBytes().contentEquals(original))
            assertFalse(dir.resolve("fixture.wav").exists())
            handle.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }
}