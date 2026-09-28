package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDownloadDiagnosticsTest {
    @Test
    fun diagnosticDetailsRedactUrlsAndCredentials() {
        val sanitized = sanitizeDiagnosticDetail(
            "failed https://cdn.example/file?signature=secret token=abc123 authorization=BearerSecret api_key=xyz\nnext",
        )
        assertFalse(sanitized.contains("https://"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("abc123"))
        assertFalse(sanitized.contains("BearerSecret"))
        assertFalse(sanitized.contains("xyz"))
        assertTrue(sanitized.contains("[url]"))
        assertTrue(sanitized.contains("[redacted]"))
        assertFalse(sanitized.contains('\n'))
    }
}