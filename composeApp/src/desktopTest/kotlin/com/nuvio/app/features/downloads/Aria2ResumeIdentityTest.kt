package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Aria2ResumeIdentityTest {
    private val identity = "torrent:hash|file:7|size:10|name:file.mkv"

    @Test
    fun persistedResumeRequiresIdentityAndAriaControlState() {
        assertTrue(matchesPersistedResumeIdentity(identity, identity, true))
        assertFalse(matchesPersistedResumeIdentity(identity, identity, false))
        assertFalse(matchesPersistedResumeIdentity(identity, "other", true))
        assertFalse(matchesPersistedResumeIdentity(null, identity, true))
    }

    @Test
    fun activeUrlRefreshRequiresAllIdentityCopiesToMatch() {
        assertTrue(matchesActiveResumeIdentity(identity, identity, identity))
        assertFalse(matchesActiveResumeIdentity(identity, "other", identity))
        assertFalse(matchesActiveResumeIdentity(identity, identity, "other"))
        assertFalse(matchesActiveResumeIdentity(null, identity, identity))
    }
}