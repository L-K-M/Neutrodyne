// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.benchmark

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Minified benchmarkRelease fallback: this never claims published release APK coverage. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeJourneyTest {
    private val app = SystemTestApp()
    private lateinit var server: FixtureServer

    @Before fun prepare() {
        server = FixtureServer(FixtureServer.Journey.SMOKE)
        app.reset()
    }

    @After fun cleanUp() {
        try {
            app.clear()
        } finally {
            if (::server.isInitialized) server.close()
        }
    }

    @Test fun fiveDestinationsSubscribePlayDownloadAndBack() {
        val destinations = listOf("nav_library", "nav_up_next", "nav_downloads", "nav_discover", "nav_feeds")
        for (destination in destinations) {
            app.node(By.res(destination)).click()
            // Selection is exposed by the real navigation control semantics.
            app.await("selected $destination") { app.node(By.res(destination)).isSelected }
        }
        app.subscribe(server)
        app.node(By.text(FixtureServer.EPISODE)).click()
        app.node(By.text("Download"))
        assertTrue("feed fetched through app networking", server.requests.any { it.path == "/feed.xml" })
        app.clickText("Play")
        app.node(By.text("Pause"))
        val started = SystemClock.elapsedRealtime()
        // Check the player throughout the ten seconds, so a failed decoder or early end fails.
        while (SystemClock.elapsedRealtime() - started < PLAY_MS) {
            assertTrue("player must remain playing", app.device.hasObject(By.text("Pause")))
            SystemClock.sleep(PLAY_POLL_MS)
        }
        assertTrue("player requested real fixture audio", server.requests.any { it.path == "/episode.wav" })
        app.clickText("Pause")
        app.clickText("Download")
        var completed = JSONObject()
        app.await("UI-requested download completes", SystemTestApp.TRANSFER_TIMEOUT_MS) {
            completed = app.probe("status")
            completed.optString("state") == "COMPLETED"
        }
        assertEquals(server.media.size.toLong(), completed.getLong("finalBytes"))
        assertEquals(server.sha256, completed.getString("sha256"))
        app.device.pressBack()
        app.node(By.text(FixtureServer.PODCAST))
    }

    private companion object {
        const val PLAY_MS = 10_000L
        const val PLAY_POLL_MS = 250L
    }
}
