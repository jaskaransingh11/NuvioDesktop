package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklV2AuthTest {
    @Test
    fun deviceRequestNeedsReadAndWrite() {
        assertEquals("media:read media:write", SimklV2DeviceRequest("public-id").scope)
    }

    @Test
    fun deviceAuthPrefersPrefilledLinkAndHonoursTimeLimits() {
        val auth = SimklV2DeviceResponse(
            deviceCode = "do-not-display-or-store",
            userCode = "BDWP-HQPK",
            verificationUri = "https://simkl.com/pin",
            verificationUriComplete = "https://simkl.com/pin?user_code=BDWP-HQPK",
            expiresIn = 900L,
            interval = 3,
        ).toPendingAuthorization(nowEpochMs = 1000L)
        assertNotNull(auth)
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", auth.verificationUrl)
        assertEquals(5, auth.intervalSeconds)
        assertEquals(901_000L, auth.expiresAtEpochMs)
        assertEquals("BDWP-HQPK", auth.userCode)
    }

    @Test
    fun invalidDeviceAuthorizationCannotBeginPolling() {
        assertNull(
            SimklV2DeviceResponse(
                deviceCode = "",
                userCode = "BDWP-HQPK",
                verificationUri = "https://simkl.com/pin",
            ).toPendingAuthorization(0L),
        )
    }

    @Test
    fun refreshingCredentialsBeforeRequestMutexCannotDeadlock() = runBlocking {
        lateinit var client: SimklApiClient
        val seen = mutableListOf<String>()
        val engine = SimklHttpEngine { _, url, headers, _ ->
            seen += url
            RawHttpResponse(
                status = 200,
                statusText = "OK",
                url = url,
                body = "{}",
                headers = emptyMap(),
            )
        }
        client = SimklApiClient(
            engine = engine,
            accessToken = {
                client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.POST,
                        path = "/oauth2/token",
                        requiresAuthentication = false,
                        retryPolicy = SimklRetryPolicy.NEVER,
                    ),
                )
                "fresh"
            },
            onUnauthorized = {},
            nowEpochMs = { 0L },
            sleep = {},
            retryJitterMs = { 0L },
        )
        val response = withTimeout(2_000L) {
            client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities"))
        }
        assertEquals(200, response.status)
        assertEquals(2, seen.size)
        assertTrue("/oauth2/token" in seen.first())
        assertTrue("/sync/activities" in seen.last())
    }
}
