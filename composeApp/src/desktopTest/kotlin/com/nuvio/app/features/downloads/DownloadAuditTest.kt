package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DownloadAuditTest {
    @Test
    fun http_rate_limit_is_classified_without_leaking_signed_url() {
        val raw = "HTTP 429 https://cdn.example.test/?token=DO_NOT_PERSIST"
        assertEquals("HTTP_429", DownloadAudit.classify(raw))
        val safe = DownloadAudit.safeErrorMessage(raw)
        assertFalse(safe.contains("token"))
        assertFalse(safe.contains("cdn.example.test"))
        assertFalse(safe.contains("DO_NOT_PERSIST"))
    }

    @Test
    fun forbidden_link_is_reported_without_credentials() {
        val raw = "403 forbidden; authorization: Bearer SECRET"
        assertEquals("HTTP_403", DownloadAudit.classify(raw))
        val safe = DownloadAudit.safeErrorMessage(raw)
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("Bearer"))
    }

    @Test
    fun bad_byte_range_is_not_silently_retried_as_clean_file() {
        assertEquals("HTTP_416", DownloadAudit.classify("416 Range not satisfiable"))
    }

    @Test
    fun identity_mismatch_is_explained_without_source_url() {
        assertEquals("SOURCE_IDENTITY", DownloadAudit.classify(
            "Safe resume unavailable: source identity or aria2 control could not be verified; partial retained",
        ))
    }

    @Test
    fun unknown_java_error_does_not_expose_input() {
        val raw = "Unexpected error raw_credentials SECRET anyurl://secret"
        assertEquals("OTHER", DownloadAudit.classify(raw))
        val safe = DownloadAudit.safeErrorMessage(raw)
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("secret"))
    }

    @Test
    fun timeouts_and_transport_errors_have_distinct_categories() {
        assertEquals("TIMEOUT", DownloadAudit.classify("connection timed out"))
        assertEquals("IO", DownloadAudit.classify("IOException connection reset"))
    }
}