package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DuplicateEnqueuePolicyTest {
    private fun item(
        status: DownloadStatus,
        parentId: String = "tt:test-series",
        season: Int? = 3,
        episode: Int? = 8,
    ) = DownloadItem(
        id = "test-download",
        contentType = if (season == null) "movie" else "series",
        parentMetaId = parentId,
        parentMetaType = if (season == null) "movie" else "series",
        videoId = "jjtest:synthetic",
        title = "Synthetic Fixture",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "Local-only fixture",
        providerName = "JJ synthetic",
        sourceUrl = "http://127.0.0.1:12345/silent64m.wav",
        fileName = "synthetic.wav",
        status = status,
        downloadedBytes = 786432,
        createdAtEpochMs = 1000L,
        updatedAtEpochMs = 1001L,
    )

    @Test
    fun any_existing_episode_status_requires_explicit_removal() {
        for (status in DownloadStatus.entries) {
            val existing = item(status)
            assertEquals(
                DownloadEnqueueResult.AlreadyPresent,
                listOf(existing).duplicateEnqueueResult(existing.logicalContentKey),
                "Unexpected destructive reenqueue for $status",
            )
        }
    }

    @Test
    fun another_episode_does_not_block_a_new_download() {
        val existing = item(DownloadStatus.Paused, episode = 8)
        val anotherEpisode = item(DownloadStatus.Downloading, episode = 9)
        assertNull(listOf(existing).duplicateEnqueueResult(anotherEpisode.logicalContentKey))
    }

    @Test
    fun another_title_does_not_block_a_new_download() {
        val existing = item(DownloadStatus.Completed, parentId = "tt:another-series")
        val target = item(DownloadStatus.Downloading)
        assertNull(listOf(existing).duplicateEnqueueResult(target.logicalContentKey))
    }

    @Test
    fun existing_completed_movie_is_not_deleted_by_reenqueue() {
        val movie = item(DownloadStatus.Completed, parentId = "tt:movie", season = null, episode = null)
        assertEquals(
            DownloadEnqueueResult.AlreadyPresent,
            listOf(movie).duplicateEnqueueResult(movie.logicalContentKey),
        )
    }

    @Test
    fun empty_download_list_can_enqueue() {
        assertNull(emptyList<DownloadItem>().duplicateEnqueueResult("tt:movie|movie"))
    }
}