// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeScopeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffectivePlaybackSettingsTest {
    private val globals = FakeSettingsRepository()
    private val scopes =
        FakeScopeSettingsRepository(settings = globals).apply {
            seedPodcast(1)
            seedPodcast(2)
            seedGroup(10, "news", "News")
            seedGroup(20, "other", "Other")
            seedMembership(1, 10)
        }
    private val resolver = FakeEffectiveSettingsResolver()
    private val subject = EffectivePlaybackSettings(resolver, scopes, globals)

    @Test fun windowSnapshotsUseEachPodcastAndContextAndDropRemovedItems() =
        runTest {
            val member = playback(1.5f, SettingSource.Group("news", "News"))
            val outsider = playback(1f, SettingSource.AppDefault)
            resolver.playbackValues.value = mapOf((1L to 10L) to member, (2L to 10L) to outsider)
            assertEquals(mapOf(1L to member, 2L to outsider), subject.prepareWindow(listOf(1, 1, 2), 10))
            assertEquals(member, subject.cached(1, 10))
            assertNull(subject.cached(1, 20))
            subject.prepareWindow(listOf(2), 20)
            assertNull(subject.cached(1, 10))
        }

    @Test fun currentObservationTracksEditsContextChangesAndDismissal() =
        runTest {
            val first = follow()
            val renamed = playback(1.8f, SettingSource.Group("news", "Renamed"))
            resolver.playbackValues.value = mapOf((1L to 10L) to renamed)
            assertEquals(renamed, subject.effectivePlayback.value)
            assertEquals(renamed, subject.cached(1, 10))
            first.cancel()
            first.join()
            val second = follow(groupId = 20)
            val other = playback(2f, SettingSource.Group("other", "Other"))
            resolver.playbackValues.value = mapOf((1L to 10L) to renamed, (1L to 20L) to other)
            assertEquals(other, subject.effectivePlayback.value)
            resolver.playbackValues.value = mapOf((1L to 20L) to other)
            assertEquals(other, subject.effectivePlayback.value)
            assertEquals(emptyList(), globals.calls)
            second.cancel()
            second.join()
            subject.followCurrent(null, null)
            assertNull(subject.effectivePlayback.value)
        }

    @Test fun speedClampsRoundsAndSilenceWritesThroughTheSameScopePath() =
        runTest {
            follow()
            for ((input, normalized) in listOf(-2f to 0.5f, 1.234f to 1.25f, 9f to 3f)) {
                assertEquals(ScopeWriteResult.APPLIED, subject.setSpeed(input, SettingScope.PODCAST))
                assertEquals(
                    normalized,
                    podcast(1).playbackSpeed,
                )
            }
            assertEquals(
                ScopeWriteResult.APPLIED,
                subject.writeScope(PlaybackSettingChange.Speed(2.97f), SettingScope.PODCAST),
            )
            assertEquals(
                2.95f,
                podcast(1).playbackSpeed,
            )
            assertEquals(ScopeWriteResult.APPLIED, subject.setSkipSilence(true, SettingScope.PODCAST))
            assertEquals(
                true,
                podcast(1).skipSilence,
            )
        }

    @Test fun failedWritesDoNotPublishSuccess() =
        runTest {
            follow()
            val previous = subject.effectivePlayback.value
            scopes.failNextWrite = SettingsError.WriteFailed
            assertEquals(ScopeWriteResult.FAILED, subject.setSpeed(1.1f, SettingScope.GROUP))
            assertEquals(previous, subject.effectivePlayback.value)
        }

    @Test fun cycleUsesEffectiveSourceAndNextHigherPresetWithWrap() =
        runTest {
            follow()
            globals.set(PlaybackSettingKeys.SPEED_PRESETS, "0.8,1.2,1.8")
            for ((source, speed, expected) in listOf(
                Triple(SettingSource.PodcastOverride, 1f, 1.2f),
                Triple(SettingSource.Group("news", "News"), 1.2f, 1.8f),
                Triple(SettingSource.AppDefault, 2f, 0.8f),
            )) {
                resolver.playbackValues.value = mapOf((1L to 10L) to playback(speed, source))
                assertEquals(ScopeWriteResult.APPLIED, subject.cycleSpeed())
                when (source) {
                    SettingSource.PodcastOverride -> {
                        assertEquals(
                            expected,
                            podcast(1).playbackSpeed,
                        )
                    }

                    is SettingSource.Group -> {
                        assertEquals(
                            expected,
                            group(10).playbackSpeed,
                        )
                    }

                    else -> {
                        assertEquals(expected, globals.get(PlaybackSettingKeys.SPEED))
                    }
                }
            }
        }

    @Test fun aSuspendedCycleKeepsItsOriginalPodcastWhenThePlayingItemChanges() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed =
                object : EffectiveSettingsResolver by resolver {
                    override suspend fun playback(
                        podcastId: Long,
                        contextGroupId: Long?,
                    ): EffectivePlayback {
                        entered.complete(Unit)
                        release.await()
                        return this@EffectivePlaybackSettingsTest.playback(1f, SettingSource.PodcastOverride)
                    }
                }
            val player = EffectivePlaybackSettings(delayed, scopes, globals)
            val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.followCurrent(1, 10) }
            val cycle = async { player.cycleSpeed() }
            entered.await()
            first.cancel()
            first.join()
            val other = playback(2f, SettingSource.AppDefault)
            resolver.playbackValues.value = mapOf((2L to null) to other)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.followCurrent(2, null) }
            release.complete(Unit)
            assertEquals(ScopeWriteResult.APPLIED, cycle.await())
            assertEquals(
                1.2f,
                podcast(1).playbackSpeed,
            )
            assertNull(
                podcast(2).playbackSpeed,
            )
            assertEquals(other, player.effectivePlayback.value)
        }

    @Test fun noCurrentAndNonFiniteSpeedNeverWrite() =
        runTest {
            assertEquals(ScopeWriteResult.NOTHING_PLAYING, subject.setSpeed(1f, SettingScope.GLOBAL))
            assertEquals(ScopeWriteResult.NOTHING_PLAYING, subject.cycleSpeed())
            follow(podcastId = 2)
            assertEquals(ScopeWriteResult.NO_CONTEXT_GROUP, subject.setSpeed(1.5f, SettingScope.GROUP))
            for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertEquals(ScopeWriteResult.FAILED, subject.setSpeed(speed, SettingScope.PODCAST))
            }
            assertNull(
                podcast(1).playbackSpeed,
            )
        }

    @Test fun runtimePreferencesObserveLiveSkipAndStorageChanges() =
        runTest {
            subject.observePreferences().test {
                val initial = awaitItem()
                assertEquals(10_000, initial.skipBackMs)
                assertEquals(30_000, initial.skipForwardMs)
                assertEquals(500, initial.streamCacheMb)
                globals.set(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                assertEquals(initial.copy(skipBackMs = 20_000), awaitItem())
                globals.set(PlaybackSettingKeys.STREAM_CACHE_MB, 250)
                assertEquals(250, awaitItem().streamCacheMb)
                globals.set(PlaybackSettingKeys.SPEED_PRESETS, "1.0,2.0")
                assertEquals(listOf(1f, 2f), awaitItem().speedPresets)
            }
        }

    private suspend fun podcast(id: Long) = requireNotNull(scopes.observePodcast(id).first()).own

    private suspend fun group(id: Long) = requireNotNull(scopes.observeGroup(id).first()).own

    private fun TestScope.follow(
        podcastId: Long = 1,
        groupId: Long? = 10,
    ): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.followCurrent(podcastId, groupId) }

    private fun playback(
        speed: Float,
        source: SettingSource,
    ) = EffectivePlayback(Effective(speed, source), Effective(false, SettingSource.AppDefault))
}
