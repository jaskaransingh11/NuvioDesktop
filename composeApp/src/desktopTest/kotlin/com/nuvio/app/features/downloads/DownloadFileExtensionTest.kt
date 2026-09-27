package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadFileExtensionTest {
    @Test
    fun extensionless_signed_url_uses_actual_resolved_matroska_filename() {
        assertEquals(
            "mkv",
            downloadExtensionFromMetadata(
                "The.Show.S01E01.MKV",
                "https://cdn.example.test/direct/opaque-id?signature=synthetic",
            ),
        )
    }

    @Test
    fun selected_filename_wins_over_url_extension() {
        assertEquals(
            "webm",
            downloadExtensionFromMetadata(
                "Some Movie.webm",
                "https://cdn.example.test/file.mp4?token=synthetic",
            ),
        )
    }

    @Test
    fun direct_url_extension_remains_supported_without_metadata() {
        assertEquals(
            "mp4",
            downloadExtensionFromMetadata(null, "https://cdn.example.test/movie.MP4?signature=synthetic"),
        )
    }

    @Test
    fun opaque_url_without_metadata_keeps_legacy_fallback() {
        assertEquals(
            "mp4",
            downloadExtensionFromMetadata(null, "https://cdn.example.test/opaque?signature=synthetic"),
        )
    }

    @Test
    fun untrusted_executable_extension_is_not_used() {
        assertEquals(
            "mp4",
            downloadExtensionFromMetadata("untrusted.exe", "https://cdn.example.test/movie.mp4"),
        )
    }

    @Test
    fun path_components_in_metadata_are_discarded() {
        assertEquals(
            "mkv",
            downloadExtensionFromMetadata("../source/folder/Film.mkv", "https://cdn.example.test/opaque"),
        )
    }

    @Test
    fun synthetic_wav_fixture_retains_wav_extension() {
        assertEquals(
            "wav",
            downloadExtensionFromMetadata("silent64m.wav", "http://127.0.0.1:59391/slow-silent64m.wav"),
        )
    }
}