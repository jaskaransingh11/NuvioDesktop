package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedResumeSourceTest {
    private val original = VerifiedResumeBinding(
        sourceSha256 = "original-url-hash",
        selectedTorrentSha256 = "same-verified-torrent-and-file",
        strongEtag = "\"content-a\"",
        contentLength = 67108864,
    )
    private val same = RangeSourceProof(
        strongEtag = "\"content-a\"",
        contentLength = 67108864,
        respondsWithExactRange = true,
    )

    @Test
    fun same_url_strong_etag_and_bitfield_may_resume() =
        assertTrue(canUseVerifiedPartial(original, "original-url-hash",
            "same-verified-torrent-and-file", same, true))

    @Test
    fun refreshed_url_requires_same_torrent_selected_file_and_etag() =
        assertTrue(canUseVerifiedPartial(original, "fresh-signed-url",
            "same-verified-torrent-and-file", same, true))

    @Test
    fun missing_or_corrupted_manifest_never_reuses_partial() =
        assertFalse(canUseVerifiedPartial(null, "original-url-hash",
            "same-verified-torrent-and-file", same, true))

    @Test
    fun old_manifest_version_is_not_trusted() =
        assertFalse(canUseVerifiedPartial(original.copy(version = 2), "original-url-hash",
            "same-verified-torrent-and-file", same, true))

    @Test
    fun aria2_bitfield_missing_never_reuses_partial() =
        assertFalse(canUseVerifiedPartial(original, "original-url-hash",
            "same-verified-torrent-and-file", same, false))

    @Test
    fun weak_etag_is_never_an_identity_validator() =
        assertFalse(canUseVerifiedPartial(original.copy(strongEtag = "W/\"content-a\""),
            "original-url-hash", "same-verified-torrent-and-file", same, true))

    @Test
    fun same_size_different_etag_must_not_mix_bytes() =
        assertFalse(canUseVerifiedPartial(original, "fresh-signed-url",
            "same-verified-torrent-and-file",
            same.copy(strongEtag = "\"different-content\""), true))

    @Test
    fun same_etag_but_different_file_size_must_fail_closed() =
        assertFalse(canUseVerifiedPartial(original, "fresh-signed-url",
            "same-verified-torrent-and-file",
            same.copy(contentLength = 67108863), true))

    @Test
    fun same_etag_on_wrong_torrent_file_does_not_authorize_changed_url() =
        assertFalse(canUseVerifiedPartial(original, "fresh-signed-url",
            "a-different-selected-file", same, true))

    @Test
    fun same_etag_without_torrent_identity_cannot_authorize_changed_url() =
        assertFalse(canUseVerifiedPartial(original.copy(selectedTorrentSha256 = null),
            "fresh-signed-url", null, same, true))

    @Test
    fun server_ignoring_range_is_not_safe_to_resume() =
        assertFalse(canUseVerifiedPartial(original, "original-url-hash",
            "same-verified-torrent-and-file",
            same.copy(respondsWithExactRange = false), true))

    @Test
    fun server_without_strong_etag_is_not_safe_to_resume() =
        assertFalse(canUseVerifiedPartial(original, "original-url-hash",
            "same-verified-torrent-and-file",
            same.copy(strongEtag = null), true))
}