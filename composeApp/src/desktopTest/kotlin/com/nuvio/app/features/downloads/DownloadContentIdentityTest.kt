package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DownloadContentIdentityTest {
    private fun item(
        infoHash: String? = "0123456789abcdef0123456789abcdef01234567",
        fileIdx: Int? = 7,
        filename: String? = "Show.S01E01.mkv",
        size: Long? = 2_039_515_623L,
        type: String? = "torrent",
        sourceUrl: String = "https://signed.example/opaque",
    ) = DownloadItem(
        id = "id",
        contentType = "movie",
        parentMetaId = "meta",
        parentMetaType = "movie",
        videoId = "video",
        title = "Title",
        streamTitle = "Stream",
        providerName = "Provider",
        sourceUrl = sourceUrl,
        sourceResolve = DownloadSourceResolve(
            type = type,
            infoHash = infoHash,
            fileIdx = fileIdx,
            filename = filename,
            service = "torbox",
        ),
        fileName = "download.mkv",
        status = DownloadStatus.Downloading,
        totalBytes = size,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    @Test
    fun stableIdentityIgnoresRotatingSignedUrl() {
        assertEquals(
            item(sourceUrl = "https://signed.example/one").stableDownloadContentIdentity(),
            item(sourceUrl = "https://signed.example/two").stableDownloadContentIdentity(),
        )
    }

    @Test
    fun stableIdentityNormalizesHashAndFilenameCase() {
        assertEquals(
            item(infoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01", filename = "Folder/SHOW.MKV").stableDownloadContentIdentity(),
            item(infoHash = "abcdef0123456789abcdef0123456789abcdef01", filename = "show.mkv").stableDownloadContentIdentity(),
        )
    }

    @Test
    fun selectedFileOrSizeChangeChangesIdentity() {
        assertNotEquals(item(fileIdx = 7).stableDownloadContentIdentity(), item(fileIdx = 8).stableDownloadContentIdentity())
        assertNotEquals(item(size = 100).stableDownloadContentIdentity(), item(size = 101).stableDownloadContentIdentity())
    }

    @Test
    fun missingStrongTorrentFieldsFailClosed() {
        assertNull(item(infoHash = null).stableDownloadContentIdentity())
        assertNull(item(fileIdx = null).stableDownloadContentIdentity())
        assertNull(item(filename = null).stableDownloadContentIdentity())
        assertNull(item(size = null).stableDownloadContentIdentity())
        assertNull(item(type = "direct").stableDownloadContentIdentity())
    }

    @Test
    fun invalidInfoHashFailsClosed() {
        assertNull(item(infoHash = "not-a-hash").stableDownloadContentIdentity())
    }
}