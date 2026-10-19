// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.Manifest
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.testing.Nightly
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Separate processes on either side of a host-controlled reboot; never reboot instrumentation. */
@Nightly
@RunWith(AndroidJUnit4::class)
class RebootResumptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val device = UiDevice.getInstance(instrumentation)
    private val saved get() = app.getSharedPreferences("m5-reboot-evidence", 0)
    private val evidence get() = File(app.getExternalFilesDir(null), "m5-reboot").apply { mkdirs() }

    @Test fun seedPausedEpisodeWithLocalArtwork() =
        runBlocking {
            hostOnly()
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    app.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            graph.databaseOpener.awaitOpen()
            graph.playbackController.dismiss()
            val url = checkNotNull(InstrumentationRegistry.getArguments().getString("sourceUrl"))
            val key = ArtworkKeys.forUrl("https://reboot.invalid/local-cover.png")
            val image = File(app.filesDir, "artwork/$key.png")
            image.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val db = graph.databaseOpener.requireDatabase()
            db.artworkDao().upsert(
                ArtworkEntity(
                    key,
                    url = "https://reboot.invalid/local-cover.png",
                    localPath = image.name,
                    width = 128,
                    height = 128,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            val podcast = db.podcastDao().insertPodcast(podcastEntity(title = "Reboot fixture", artworkKey = key))
            val id =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast, title = TITLE) {
                        copy(enclosureUrl = url, durationMs = 120_000)
                    },
                )
            ActivityScenario.launch(MainActivity::class.java).use {
                assertEquals(PlayResult.Started, graph.playbackController.playEpisode(id))
                val controller = connect()
                try {
                    await { withContext(Dispatchers.Main) { controller.isPlaying } }
                    withContext(Dispatchers.Main) {
                        controller.seekTo(SAVED_POSITION_MS)
                        controller.pause()
                    }
                    await { (db.positionDao().byEpisode(id)?.positionMs ?: 0) >= SAVED_POSITION_MS }
                    check(
                        saved
                            .edit()
                            .putLong(
                                "episode",
                                id,
                            ).putLong("position", db.positionDao().byEpisode(id)!!.positionMs)
                            .commit(),
                    )
                    record("seed")
                } finally {
                    withContext(Dispatchers.Main) { controller.release() }
                }
            }
        }

    @Test fun offlineSystemUiCardHasLocalArtworkWithoutAutoplay() =
        runBlocking {
            hostOnly()
            graph.databaseOpener.awaitOpen()
            device.openQuickSettings()
            val card =
                checkNotNull(device.wait(Until.findObject(By.text(TITLE)), WAIT_MS)) { "System UI recent card missing" }
            assertTrue(card.visibleBounds.width() > 0)
            record("offline-card")
            val art =
                checkNotNull(device.findObject(By.res("com.android.systemui", "album_art"))) { "Card artwork missing" }
            val bounds = art.visibleBounds
            val screenshot = checkNotNull(BitmapFactory.decodeFile(File(evidence, "offline-card.png").path))
            var blue = 0
            var pixels = 0
            for (y in bounds.top until bounds.bottom step 4) {
                for (x in bounds.left until bounds.right step 4) {
                    val color = screenshot.getPixel(x, y)
                    if (Color.blue(color) > 100 && Color.red(color) < 80 && Color.green(color) < 80) blue++
                    pixels++
                }
            }
            screenshot.recycle()
            assertTrue("System UI must display the seeded local blue cover", pixels > 0 && blue > pixels / 10)
            assertEquals(
                saved.getLong("episode", -1),
                graph
                    .playSessionStore()
                    .virtualQueue(0)
                    .current
                    ?.episodeId,
            )
            assertEquals(
                saved.getLong("position", -1),
                graph
                    .positionStore()
                    .observe(saved.getLong("episode", -1))
                    .first()
                    ?.positionMs,
            )
            assertFalse(
                graph.playbackStateSource.nowPlaying.value
                    ?.isPlaying == true,
            )
            assertTrue(app.getSystemService(AudioManager::class.java).activePlaybackConfigurations.isEmpty())
        }

    @Test fun tapSystemUiCardResumesSavedPosition() =
        runBlocking {
            hostOnly()
            graph.databaseOpener.awaitOpen()
            device.openQuickSettings()
            val play =
                checkNotNull(device.wait(Until.findObject(By.descContains("Play")), WAIT_MS)) {
                    "System UI recent card play action missing"
                }
            play.click()
            val controller = connect()
            try {
                await {
                    withContext(Dispatchers.Main) {
                        controller.isPlaying &&
                            controller.currentPosition >= SAVED_POSITION_MS
                    }
                }
                withContext(Dispatchers.Main) {
                    assertEquals("episode:${saved.getLong("episode", -1)}", controller.currentMediaItem?.mediaId)
                    assertTrue(controller.currentPosition < SAVED_POSITION_MS + 10_000)
                }
                val before = withContext(Dispatchers.Main) { controller.currentPosition }
                await { withContext(Dispatchers.Main) { controller.currentPosition > before + 200 } }
                assertTrue(app.getSystemService(AudioManager::class.java).activePlaybackConfigurations.isNotEmpty())
                record("resumed")
            } finally {
                withContext(Dispatchers.Main) { controller.release() }
                graph.playbackController.dismiss()
            }
        }

    private fun hostOnly() =
        assumeTrue(
            "Run with scripts/ci/playback-reboot.py",
            InstrumentationRegistry.getArguments().getString("hostReboot") == "true",
        )

    private suspend fun connect(): MediaController =
        withContext(Dispatchers.Main) {
            MediaController
                .Builder(
                    app,
                    SessionToken(app, ComponentName(app, NeutrodynePlaybackService::class.java)),
                ).buildAsync()
                .await()
        }

    private fun record(phase: String) {
        device.takeScreenshot(File(evidence, "$phase.png"))
        device.dumpWindowHierarchy(File(evidence, "$phase.xml"))
        File(evidence, "$phase.txt").writeText(
            "episode=${saved.getLong("episode", -1)} position=${saved.getLong("position", -1)} " +
                "build=${Build.FINGERPRINT}\n",
        )
    }

    private suspend fun await(condition: suspend () -> Boolean) =
        withTimeout(WAIT_MS) { while (!condition()) delay(100) }

    private companion object {
        const val TITLE = "M5 reboot evidence"
        const val SAVED_POSITION_MS = 9_000L
        const val WAIT_MS = 60_000L
    }
}
