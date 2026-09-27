package com.nuvio.app.core.network

import java.nio.file.Paths
import java.util.UUID
import java.util.prefs.Preferences
import com.russhwolf.settings.PreferencesSettings
import io.github.jan.supabase.auth.SettingsSessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
    @Test
    fun test_session_manager_cannot_import_a_siblings_legacy_session() {
        // Never inspect or mutate the real Preferences.userRoot() session.
        val root = Preferences.userRoot()
            .node("/com/nuvio/unit-test-isolation-${UUID.randomUUID()}")
        val simulatedProduction = root.node("ordinary-production")
        val isolated = root.node("isolated-test")
        try {
            simulatedProduction.put("session", "synthetic-marker-not-a-credential")
            root.flush()
            assertNull(isolated.get("session", null))
            // SDK 3.4.1 migrates a legacy "session" for custom keys.
            // The isolated node and ordinary key together prevent that.
            SettingsSessionManager(
                settings = PreferencesSettings(isolated),
                key = "session",
            )
            assertEquals(
                "synthetic-marker-not-a-credential",
                simulatedProduction.get("session", null),
            )
            assertNull(isolated.get("session", null))
        } finally {
            root.removeNode()
            root.flush()
        }
    }

}
