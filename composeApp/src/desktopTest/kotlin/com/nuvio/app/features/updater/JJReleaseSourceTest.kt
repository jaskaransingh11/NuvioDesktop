package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JJReleaseSourceTest {
    @Test
    fun jjBuildReadsOnlyPersonalForkReleases() {
        val source = AppUpdaterPlatform.releaseSource
        assertEquals("jaskaransingh11", source.owner)
        assertEquals("NuvioDesktop", source.repo)
        assertEquals(true, source.includePrereleases)
        assertEquals("NuvioJJ", source.userAgent)
        assertTrue(AppVersionConfig.DESKTOP_VERSION_NAME.startsWith("0.1."))
    }
}
