// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerEpisodeViewModelTest : MainDispatcherTest() {
    private val episodes = FakeEpisodeRepository()
    private val podcasts = FakePodcastRepository()
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val store = ViewModelStore()
    private val document = ShowNotes(listOf(ShowNoteBlock.Rule))

    private fun TestScope.model(
        repository: EpisodeRepository = episodes,
        podcastRepository: PodcastRepository = podcasts,
    ): PlayerEpisodeViewModel {
        val vm = PlayerEpisodeViewModel(repository, podcastRepository, settings, network)
        store.put("episode", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.detail.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notes.collect() }
        return vm
    }

    @AfterTest fun close() = store.clear()

    @Test fun metadataNeverSubscribesToNotesUntilTheTabOpens() =
        runTest {
            var active = 0
            val repository =
                object : EpisodeRepository by episodes {
                    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> =
                        flow {
                            active++
                            try {
                                emitAll(episodes.observeShowNotes(episodeId))
                            } finally {
                                active--
                            }
                        }
                }
            val vm = model(repository)
            vm.displayEpisode(1)
            runCurrent()
            assertEquals(0, active)
            vm.showNotes(1)
            runCurrent()
            assertEquals(1, active)
            vm.showNotes(null)
            runCurrent()
            assertEquals(0, active)
        }

    @Test fun switchingToSlowNotesClearsThePreviousEpisodeDocument() =
        runTest {
            val release = CompletableDeferred<Unit>()
            episodes.notes.value = mapOf(1L to document)
            val repository =
                object : EpisodeRepository by episodes {
                    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> =
                        flow {
                            if (episodeId == 2L) release.await()
                            emitAll(episodes.observeShowNotes(episodeId))
                        }
                }
            val vm = model(repository)
            vm.showNotes(1)
            runCurrent()
            assertEquals(document, vm.notes.value.notes)
            vm.showNotes(2)
            runCurrent()
            assertNull(vm.notes.value.notes, "Old notes cannot appear beneath the new episode title")
            assertFalse(vm.notes.value.loaded)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.notes.value.loaded)
        }

    @Test fun notesReadFailureCanRetryWithoutChangingTheCurrentEpisode() =
        runTest {
            var fail = true
            episodes.notes.value = mapOf(1L to document)
            val repository =
                object : EpisodeRepository by episodes {
                    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> =
                        flow {
                            if (fail) error("Notes read failed")
                            emitAll(episodes.observeShowNotes(episodeId))
                        }
                }
            val vm = model(repository)
            vm.showNotes(1)
            runCurrent()
            assertTrue(vm.notes.value.loadFailed)
            fail = false
            vm.retryNotes()
            runCurrent()
            assertEquals(document, vm.notes.value.notes)
            assertTrue(vm.notes.value.loaded)
            assertFalse(vm.notes.value.loadFailed)
        }

    @Test fun metadataFailureDoesNotStopObservingLaterEpisodes() =
        runTest {
            episodes.episodes.value = mapOf(2L to testEpisodeDetail(2))
            val repository =
                object : EpisodeRepository by episodes {
                    override fun observeEpisode(episodeId: Long): Flow<EpisodeDetail?> =
                        flow {
                            if (episodeId == 1L) error("Metadata read failed")
                            emitAll(episodes.observeEpisode(episodeId))
                        }
                }
            val vm = model(repository)
            vm.displayEpisode(1)
            runCurrent()
            assertTrue(vm.readFailure.value)
            vm.acknowledgeReadFailure()
            vm.displayEpisode(2)
            runCurrent()
            assertEquals(2L, vm.detail.value?.id)
        }

    @Test fun wifiOnlyImagesRemainBlockedOnMeteredNetworks() =
        runTest {
            settings.set(FeedsSettingKeys.SHOW_NOTES_IMAGES, ShowNotesImages.WIFI_ONLY)
            val vm = model()
            vm.showNotes(1)
            runCurrent()
            assertEquals(ShowNotesImageMode.SHOWN, vm.notes.value.imageMode)
            network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
            runCurrent()
            assertEquals(ShowNotesImageMode.BLOCKED, vm.notes.value.imageMode)
            settings.set(FeedsSettingKeys.SHOW_NOTES_IMAGES, ShowNotesImages.TAP_TO_LOAD)
            runCurrent()
            assertEquals(ShowNotesImageMode.TAP_TO_LOAD, vm.notes.value.imageMode)
        }

    @Test fun passwordSaveDoesNotCloseAReopenedDialog() =
        runTest {
            val release = CompletableDeferred<Unit>()
            episodes.episodes.value = mapOf(1L to testEpisodeDetail(1, podcastId = 9))
            val repository =
                object : PodcastRepository by podcasts {
                    override suspend fun setCredentials(
                        podcastId: Long,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        release.await()
                        return podcasts.setCredentials(podcastId, credentials)
                    }
                }
            val vm = model(podcastRepository = repository)
            vm.requestPassword(1)
            vm.submitPassword(BasicCredentials("listener", "password"))
            assertNull(vm.passwordEpisode.value)
            vm.requestPassword(1)
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(1L, vm.passwordEpisode.value)
            assertEquals(listOf("setCredentials(9, listener)"), podcasts.calls)
        }

    @Test fun successfulWriteDoesNotEraseAnUnacknowledgedFailure() =
        runTest {
            val vm = model()
            episodes.writeError = IllegalStateException("Write failed")
            vm.markPlayed(1)
            runCurrent()
            assertTrue(vm.writeFailed.value)
            episodes.writeError = null
            vm.markPlayed(2)
            runCurrent()
            assertTrue(vm.writeFailed.value)
            assertEquals(listOf("setPlayed([2], true)"), episodes.calls)
            vm.acknowledgeFailure()
            assertFalse(vm.writeFailed.value)
        }
}
