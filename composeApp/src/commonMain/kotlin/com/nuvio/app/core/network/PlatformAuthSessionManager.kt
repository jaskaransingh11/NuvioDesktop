package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

/**
 * Test-only auth storage on desktop. Other targets and normal desktop launches
 * preserve the SDK's existing session store.
 */
internal expect fun platformIsolatedAuthSessionManager(backendUrl: String): SessionManager?
