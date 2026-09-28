package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedResumeHttpTest {
    private fun checkWithLocalServer(block: (base: String, directory: File) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val tag = if (path.contains("changed")) "\"different-content\"" else "\"fixture-content\""
            if (!path.contains("no-etag")) exchange.responseHeaders.set("ETag", tag)
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            val rangeRequested = exchange.requestHeaders.getFirst("Range") == "bytes=0-0"
            if (rangeRequested && !path.contains("no-range")) {
                exchange.responseHeaders.set("Content-Range", "bytes 0-0/1048576")
                exchange.sendResponseHeaders(206, 1)
                exchange.responseBody.use { it.write(byteArrayOf(0x42)) }
            } else {
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
        }
        val temporary = Files.createTempDirectory("jj-resume-http-test-")
        server.start()
        try {
            block("http://127.0.0.1:" + server.address.port, temporary.toFile())
        } finally {
            server.stop(0)
            Files.walk(temporary).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun item(base: String, route: String, torrent: String = "a".repeat(40)) =
        DownloadItem(
            id = "fixture-download", contentType = "movie",
            parentMetaId = "fixture", parentMetaType = "movie",
            videoId = "fixture", title = "Synthetic",
            streamTitle = "Synthetic", providerName = "Test",
            sourceUrl = base + route, fileName = "fixture.wav",
            sourceResolve = DownloadSourceResolve(
                type = "torrent", infoHash = torrent,
                fileIdx = 0, filename = "fixture.wav", service = "torbox",
            ),
            status = DownloadStatus.Downloading,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
        )

    private fun createPartialAndControl(part: File) {
        part.writeBytes(byteArrayOf(1, 2, 3))
        File(part.absolutePath + ".aria2").writeBytes(byteArrayOf(1))
    }

    @Test
    fun fresh_source_creates_url_free_manifest_and_resume_can_verify_it() =
        checkWithLocalServer { base, dir ->
            val part = File(dir, "fixture.wav.part")
            val guard = VerifiedResumeSource()
            val request = DownloadPlatformRequest(item(base, "/original"))
            assertFalse(guard.inspectAndBind(request, part).resumeExistingPartial)
            val binding = File(part.absolutePath + ".nuvio-identity.json")
            assertTrue(binding.isFile)
            assertFalse(binding.readText().contains(base))
            assertFalse(binding.readText().contains("original"))
            createPartialAndControl(part)
            val decision = guard.inspectAndBind(request, part)
            assertTrue(decision.resumeExistingPartial)
            assertEquals("\"fixture-content\"", decision.ifRangeEtag)
        }

    @Test
    fun changed_signed_url_same_selected_torrent_and_strong_etag_can_resume() =
        checkWithLocalServer { base, dir ->
            val guard = VerifiedResumeSource()
            val part = File(dir, "fixture.wav.part")
            guard.inspectAndBind(DownloadPlatformRequest(item(base, "/old-token")), part)
            createPartialAndControl(part)
            val rotated = guard.inspectAndBind(DownloadPlatformRequest(item(base, "/new-token")), part)
            assertTrue(rotated.resumeExistingPartial)
            assertEquals("\"fixture-content\"", rotated.ifRangeEtag)
        }

    @Test
    fun different_same_size_file_is_rejected_and_part_is_preserved() =
        checkWithLocalServer { base, dir ->
            val guard = VerifiedResumeSource()
            val part = File(dir, "fixture.wav.part")
            guard.inspectAndBind(DownloadPlatformRequest(item(base, "/original")), part)
            createPartialAndControl(part)
            assertFailsWith<IllegalStateException> {
                guard.inspectAndBind(DownloadPlatformRequest(item(base, "/changed-content")), part)
            }
            assertEquals(3L, part.length())
            assertTrue(File(part.absolutePath + ".aria2").exists())
        }

    @Test
    fun orphaned_bitfield_with_no_media_bytes_is_preserved_and_blocked() =
        checkWithLocalServer { base, dir ->
            val part = File(dir, "fixture.wav.part")
            val controller = File(part.absolutePath + ".aria2")
            controller.writeBytes(byteArrayOf(0x42))
            assertFailsWith<IllegalStateException> {
                VerifiedResumeSource().inspectAndBind(
                    DownloadPlatformRequest(item(base, "/original")), part,
                )
            }
            assertTrue(controller.exists())
            assertFalse(part.exists())
        }
    @Test
    fun unknown_old_partial_without_provenance_fails_closed() =
        checkWithLocalServer { base, dir ->
            val part = File(dir, "fixture.wav.part")
            createPartialAndControl(part)
            assertFailsWith<IllegalStateException> {
                VerifiedResumeSource().inspectAndBind(
                    DownloadPlatformRequest(item(base, "/original")), part,
                )
            }
            assertEquals(3L, part.length())
        }

    @Test
    fun weak_or_missing_etag_disables_restart_resumption() =
        checkWithLocalServer { base, dir ->
            val part = File(dir, "fixture.wav.part")
            val guard = VerifiedResumeSource()
            assertFalse(guard.inspectAndBind(
                DownloadPlatformRequest(item(base, "/no-etag")), part,
            ).resumeExistingPartial)
            createPartialAndControl(part)
            assertFailsWith<IllegalStateException> {
                guard.inspectAndBind(DownloadPlatformRequest(item(base, "/no-etag")), part)
            }
            assertEquals(3L, part.length())
        }

    @Test
    fun range_ignored_on_reconnect_is_not_accepted() =
        checkWithLocalServer { base, dir ->
            val guard = VerifiedResumeSource()
            val part = File(dir, "fixture.wav.part")
            guard.inspectAndBind(DownloadPlatformRequest(item(base, "/original")), part)
            createPartialAndControl(part)
            assertFailsWith<IllegalStateException> {
                guard.inspectAndBind(DownloadPlatformRequest(item(base, "/no-range")), part)
            }
            assertEquals(3L, part.length())
        }

    @Test
    fun same_etag_wrong_torrent_file_is_not_sufficient() =
        checkWithLocalServer { base, dir ->
            val guard = VerifiedResumeSource()
            val part = File(dir, "fixture.wav.part")
            guard.inspectAndBind(DownloadPlatformRequest(item(base, "/old-token")), part)
            createPartialAndControl(part)
            assertFailsWith<IllegalStateException> {
                guard.inspectAndBind(DownloadPlatformRequest(
                    item(base, "/new-token", torrent = "b".repeat(40)),
                ), part)
            }
            assertEquals(3L, part.length())
        }
}