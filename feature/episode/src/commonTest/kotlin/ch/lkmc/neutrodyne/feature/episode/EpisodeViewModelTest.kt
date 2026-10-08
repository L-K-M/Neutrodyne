// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `EpisodeViewModel` on the desktop JVM (09): episode/notes/offline state, the
 * `feeds.show_notes_images` × metered fold into [ShowNotesImageMode] (03 Images and links,
 * PO-21), and the played/favourite writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModelTest : MainDispatcherTest() {
    private val episodes = FakeEpisodeRepository()
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    private fun viewModel(episodeId: Long = 5) = EpisodeViewModel(episodeId, episodes, settings, network)

    private fun TestScope.collect(viewModel: EpisodeViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            assertEquals(EpisodeUiState(), viewModel().uiState.value)
        }

    @Test
    fun episodeAndNotesLoad() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5, title = "The Interview"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertEquals("The Interview", state.episode?.title)
            assertTrue(!state.gone)
        }

    @Test
    fun loadedNullEpisodeIsGone() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.gone)
        }

    @Test
    fun offlineFlagTracksTheMonitor() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5))
            advanceUntilIdle()
            assertTrue(!viewModel.uiState.value.offline)

            network.setStatus(FakeNetworkMonitor.OFFLINE)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.offline)
        }

    @Test
    fun imageModeFoldsTheSettingAndMetered() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5))
            advanceUntilIdle()

            // TAP_TO_LOAD default setting → TAP_TO_LOAD even unmetered.
            assertEquals(ShowNotesImageMode.TAP_TO_LOAD, viewModel.uiState.value.imageMode)

            settings.set(FeedsSettingKeys.SHOW_NOTES_IMAGES, ShowNotesImages.ALWAYS)
            advanceUntilIdle()
            assertEquals(ShowNotesImageMode.SHOWN, viewModel.uiState.value.imageMode)

            // WIFI_ONLY shows images unmetered and blocks them on a metered link (PO-21): the
            // Wi-Fi-only setting has no tap-to-load escape (03 Images and links).
            settings.set(FeedsSettingKeys.SHOW_NOTES_IMAGES, ShowNotesImages.WIFI_ONLY)
            advanceUntilIdle()
            assertEquals(ShowNotesImageMode.SHOWN, viewModel.uiState.value.imageMode)

            network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
            advanceUntilIdle()
            assertEquals(ShowNotesImageMode.BLOCKED, viewModel.uiState.value.imageMode)
        }

    @Test
    fun setPlayedWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setPlayed(true)
            advanceUntilIdle()
            viewModel.setPlayed(false)
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([5], true)", "setPlayed([5], false)"), episodes.calls)
        }

    @Test
    fun setFavoriteWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setFavorite(true)
            advanceUntilIdle()
            assertEquals(listOf("setFavorite(5, true)"), episodes.calls)
        }

    @Test
    fun rowPlayedActionWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAction(EpisodeAction.SetPlayed(9, played = true))
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([9], true)"), episodes.calls)
        }

    @Test
    fun playerActionsAreInertAtM1a() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAction(EpisodeAction.PlayToggle(9))
            viewModel.onAction(EpisodeAction.DownloadToggle(9))
            advanceUntilIdle()
            assertTrue(episodes.calls.isEmpty())
        }
}
