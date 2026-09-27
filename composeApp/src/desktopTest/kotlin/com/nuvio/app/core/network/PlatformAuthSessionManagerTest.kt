package com.nuvio.app.core.network

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformAuthSessionManagerTest {
    @Test
    fun same_directory_and_backend_yield_one_scoped_key() {
        val root = Paths.get("NuvioIsolatedTest", "Roaming")
        val normalized = Paths.get("NuvioIsolatedTest", "transient", "..", "Roaming")
        val first = isolatedAuthSessionKey(root, "https://backend.example.test/")
        val same = isolatedAuthSessionKey(normalized, "https://backend.example.test")
        assertEquals(first, same)
        assertTrue(first.startsWith("nuvio_isolated_test_auth_"))
    }

    @Test
    fun different_profile_or_backend_cannot_reuse_the_same_key() {
        val root = Paths.get("NuvioIsolatedTest", "Roaming")
        val other = Paths.get("NuvioIsolatedTest2", "Roaming")
        val first = isolatedAuthSessionKey(root, "https://backend-a.example.test")
        assertNotEquals(first, isolatedAuthSessionKey(other, "https://backend-a.example.test"))
        assertNotEquals(first, isolatedAuthSessionKey(root, "https://backend-b.example.test"))
        assertTrue(!first.contains("backend-a"))
        assertTrue(!first.contains("NuvioIsolatedTest"))
    }
}
