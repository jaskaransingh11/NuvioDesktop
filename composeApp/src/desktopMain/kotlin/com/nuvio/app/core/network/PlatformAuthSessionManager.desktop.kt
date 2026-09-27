package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale

private const val TEST_FLAG = "NUVIO_TEST_AUTH_ISOLATION"
private const val MARKER_FILE = ".nuvio-test-auth-isolation"
private const val MARKER_TEXT = "nuvio-auth-isolated-test-v1"

/**
 * Explicit opt-in only. The default (including existing installed users)
 * returns null, preserving the SDK's existing persisted session untouched.
 *
 * Merely changing APPDATA does NOT isolate the SDK's default JVM
 * Preferences.userRoot() session storage. In test mode the auth key must be
 * unique to both the isolated profile and the Supabase backend.
 */
internal actual fun platformIsolatedAuthSessionManager(backendUrl: String): SessionManager? {
    if (System.getenv(TEST_FLAG) != "1") return null

    val rawAppData = System.getenv("APPDATA")
        ?.takeIf(String::isNotBlank)
        ?: error("$TEST_FLAG requires an explicit isolated APPDATA directory")
    val appData = Paths.get(rawAppData).toAbsolutePath().normalize()
    val normalAppData = Paths.get(
        System.getProperty("user.home"),
        "AppData",
        "Roaming",
    ).toAbsolutePath().normalize()

    require(appData != normalAppData) {
        "$TEST_FLAG refuses the ordinary Windows roaming profile"
    }
    val marker = appData.resolve(MARKER_FILE)
    require(Files.isRegularFile(marker) && Files.readString(marker).trim() == MARKER_TEXT) {
        "$TEST_FLAG requires a test-only marker inside isolated APPDATA"
    }
    return SettingsSessionManager(key = isolatedAuthSessionKey(appData, backendUrl))
}

/**
 * The key is not a credential. Hashing avoids exposing a user's full local
 * path or backend address in preference names. Both dimensions are needed to
 * prevent cross-profile AND cross-backend session reuse.
 */
internal fun isolatedAuthSessionKey(appData: Path, backendUrl: String): String {
    val canonicalPath = appData.toAbsolutePath().normalize().toString()
        .lowercase(Locale.ROOT)
    val canonicalBackend = backendUrl.trim().trimEnd('/')
        .lowercase(Locale.ROOT)
    val bytes = "$canonicalPath\n$canonicalBackend".toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .take(20)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return "nuvio_isolated_test_auth_$digest"
}
