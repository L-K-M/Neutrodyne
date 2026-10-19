// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingSource
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EffectivePlaybackApplierTest {
    @Test fun appliesSpeedAndSilenceBeforePrepare() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(1)
                val podcast = f.lookup.get(ids[0])!!.podcastId
                f.settingsResolver.playbackValues.value = mapOf((podcast to null) to value(1.75f, true))
                f.attach()
                var beforePrepare = false
                f.player.beforePrepare = {
                    beforePrepare = f.exo.playbackParameters.speed == 1.75f && f.exo.skipSilenceEnabled
                }
                f.start(ids)
                assertTrue(beforePrepare)
            } finally {
                f.close()
            }
        }

    @Test fun liveChangesApplyWithoutPrepareAndExternalSpeedResetsOnTransition() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                val podcast = f.lookup.get(ids[0])!!.podcastId
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                f.settingsResolver.playbackValues.value = mapOf((podcast to null) to value(1.5f, true))
                runCurrent()
                assertEquals(1.5f, f.exo.playbackParameters.speed)
                assertTrue(f.exo.skipSilenceEnabled)
                f.exo.setPlaybackSpeed(2.5f)
                assertEquals(2.5f, f.exo.playbackParameters.speed)
                f.db.podcastDao().setCustomTitle(podcast, "While playing")
                awaitAdapter {
                    f.player.currentMediaItem!!
                        .mediaMetadata.albumTitle
                        .toString() == "While playing"
                }
                assertEquals(2.5f, f.exo.playbackParameters.speed)
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.ids() == ids.drop(1) }
                assertEquals(1.5f, f.exo.playbackParameters.speed)
                assertEquals(1, f.player.calls.count { it == "prepare" })
            } finally {
                f.close()
            }
        }

    @Test fun groupCacheAndNullCurrentHaveCorrectAttribution() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                val row = f.lookup.get(id)!!
                val group = SettingSource.Group("group-uuid", "News")
                val resolved = EffectivePlayback(Effective(1.25f, group), Effective(true, group))
                f.settingsResolver.playbackValues.value = mapOf((row.podcastId to 9L) to resolved)
                f.applier.attach(f.exo)
                f.applier.prepareWindow(listOf(row), 9)
                f.applier.applyFor(id)
                runCurrent()
                assertEquals(1.25f, f.exo.playbackParameters.speed)
                assertEquals(resolved, f.settings.effectivePlayback.value)
                f.applier.applyFor(null)
                runCurrent()
                assertNull(f.settings.effectivePlayback.value)
            } finally {
                f.close()
            }
        }

    private fun value(
        speed: Float,
        silence: Boolean,
    ) = EffectivePlayback(Effective(speed, SettingSource.AppDefault), Effective(silence, SettingSource.AppDefault))
}
