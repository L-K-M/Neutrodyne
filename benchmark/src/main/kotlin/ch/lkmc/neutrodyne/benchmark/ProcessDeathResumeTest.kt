// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.benchmark

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** E10: the app dies, this instrumentation and its HTTP server remain alive. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathResumeTest {
    private val app = SystemTestApp()
    private lateinit var server: FixtureServer

    @Before fun prepare() {
        // A wrong device is a failed gate, never an assumption/skipped test.
        assertEquals("E10 requires bench34", 34, Build.VERSION.SDK_INT)
        server = FixtureServer(FixtureServer.Journey.PROCESS_DEATH)
        app.reset()
    }

    @After fun cleanUp() {
        try {
            app.clear()
        } finally {
            if (::server.isInitialized) server.close()
        }
    }

    @Test fun killReconcilesSystemAndResumesFromPartLength() {
        app.subscribe(server)
        app.background()
        app.probe("seed", server.mediaUrl)
        var downloading = JSONObject()
        app.await("real engine has written a partial strong-ETag transfer") {
            downloading = app.probe("status")
            downloading.optString("state") == "DOWNLOADING" &&
                downloading.optString("etag") == FixtureServer.ETAG &&
                downloading.optLong("partBytes") >= MIN_PART_BYTES &&
                downloading.optLong("partBytes") < server.media.size
        }
        assertFalse(downloading.isNull("token"))
        val oldPid = downloading.getInt("pid")
        app.shell("am kill ${SystemTestApp.PACKAGE}")
        app.await("am kill removed original target PID") {
            oldPid.toString() !in app.shell("pidof ${SystemTestApp.PACKAGE}").split(' ')
        }
        // No force-stop, stopservice, pause API or cancellation: the persisted row must still
        // carry the dead token until the actual startup reconciler resets it.
        app.launch()
        val after = app.probe("status")
        assertTrue("a new target process must start", after.getInt("pid") != oldPid)
        val reset = after.getJSONObject("reconciled")
        assertEquals("QUEUED", reset.getString("state"))
        assertEquals("SYSTEM", reset.getString("waitReason"))
        assertTrue("stale runner token cleared", reset.isNull("token"))
        assertEquals(-1, reset.getInt("lastStopReason"))
        val partBytes = reset.getLong("partBytes")
        assertTrue(partBytes in MIN_PART_BYTES until server.media.size.toLong())
        // The persisted byte checkpoint is deliberately not the source of the expected Range.
        assertEquals(FixtureServer.ETAG, reset.getString("etag"))
        app.background()
        app.probe("resume")
        app.await("server observed Range from surviving part length") {
            server.requests.any { it.range == "bytes=$partBytes-" && it.ifRange == FixtureServer.ETAG }
        }
        var completed = JSONObject()
        app.await("resumed download completes", SystemTestApp.TRANSFER_TIMEOUT_MS) {
            completed = app.probe("status")
            completed.optString("state") == "COMPLETED"
        }
        assertEquals(server.media.size.toLong(), completed.getLong("finalBytes"))
        assertEquals(server.sha256, completed.getString("sha256"))
        assertEquals(0L, completed.getLong("partBytes"))
        val mediaRequests = server.requests.filter { it.path == "/episode.wav" }
        assertEquals("one initial GET and one resumed GET", 2, mediaRequests.size)
        assertEquals(null, mediaRequests.first().range)
        assertEquals("bytes=$partBytes-", mediaRequests.last().range)
    }

    private companion object {
        const val MIN_PART_BYTES = 131_072L
    }
}
