package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadRetryPolicyTest {
    @Test
    fun transient_transport_failure_uses_small_bounded_backoff() {
        assertEquals(1500L, downloadRetryDelayMs("connection reset", 1))
        assertEquals(3000L, downloadRetryDelayMs("temporary timeout", 2))
    }

    @Test
    fun request_count_stops_after_three_attempts() {
        assertNull(downloadRetryDelayMs("connection reset", 3))
        assertNull(downloadRetryDelayMs("connection reset", 8))
    }

    @Test
    fun rate_limit_never_retries_automatically() {
        assertNull(downloadRetryDelayMs("Download rate limited (HTTP 429); partial retained", 1))
        assertNull(downloadRetryDelayMs("HTTP 429", 2))
    }

    @Test
    fun forbidden_and_unauthorized_direct_link_trigger_refresh_instead() {
        assertNull(downloadRetryDelayMs("HTTP 401", 1))
        assertNull(downloadRetryDelayMs("HTTP 403", 1))
    }

    @Test
    fun invalid_byte_range_does_not_retry_same_source() {
        assertNull(downloadRetryDelayMs("HTTP 416", 1))
    }
}