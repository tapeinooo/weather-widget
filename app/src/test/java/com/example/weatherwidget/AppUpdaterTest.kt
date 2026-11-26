package com.example.weatherwidget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(AppUpdater.isNewer("1.10", "1.9"))
        assertTrue(AppUpdater.isNewer("1.22", "1.21"))
        assertTrue(AppUpdater.isNewer("2.0", "1.99"))
        assertFalse(AppUpdater.isNewer("1.21", "1.21"))
        assertFalse(AppUpdater.isNewer("1.9", "1.10"))
        assertFalse(AppUpdater.isNewer("1.21", "1.21.0"))
    }

    /** 本物の GitHub に聞く（ネットにつながっているとき）。最新のリリースに APK が付いていること。 */
    @Test
    fun latestReleaseHasApk() = runBlocking {
        val r = AppUpdater.latest()
        assertEquals("v" + r.version, r.tag)
        assertTrue(r.apkUrl, r.apkUrl.endsWith(".apk"))
    }
}
