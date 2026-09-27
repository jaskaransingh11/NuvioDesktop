package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

internal actual fun platformIsolatedAuthSessionManager(backendUrl: String): SessionManager? = null
