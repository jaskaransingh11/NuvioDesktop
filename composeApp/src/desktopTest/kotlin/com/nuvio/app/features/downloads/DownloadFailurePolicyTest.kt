package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadFailurePolicyTest {
    @Test
    fun signedLinkAndRangeFailuresRefreshSource() {
        listOf(
            "Download failed with HTTP 400",
            "Download failed with HTTP 401",
            "Download failed with HTTP 403",
            "status=404",
            "status: 410",
            "HTTP 416",
        ).forEach {
            assertEquals(DownloadFailureAction.RefreshSource, classifyDownloadFailure(it), it)
        }
    }

    @Test
    fun transientFailuresRetrySameSource() {
        listOf(
            "Download failed with HTTP 408",
            "HTTP 425",
            "status=429",
            "HTTP 500",
            "HTTP 503",
            "connection reset by peer",
            "request timed out",
        ).forEach {
            assertEquals(DownloadFailureAction.RetrySameSource, classifyDownloadFailure(it), it)
        }
    }

    @Test
    fun unknownFailuresFailWithoutBlindRetry() {
        assertEquals(DownloadFailureAction.Fail, classifyDownloadFailure("disk is full"))
        assertEquals(DownloadFailureAction.Fail, classifyDownloadFailure("invalid response"))
    }

    @Test
    fun retryBackoffIsBoundedAndIncreasing() {
        assertEquals(1_000L, downloadRetryDelayMs(1))
        assertEquals(3_000L, downloadRetryDelayMs(2))
        assertEquals(8_000L, downloadRetryDelayMs(3))
        assertEquals(8_000L, downloadRetryDelayMs(99))
    }
}