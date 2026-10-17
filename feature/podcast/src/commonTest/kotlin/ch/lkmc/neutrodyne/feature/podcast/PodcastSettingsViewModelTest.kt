// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeScopeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testFeedInfo
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PodcastSettingsViewModel` on the desktop JVM (09): the combined detail/feed-info state, 05's
 * effective order and every settings write the screen exposes (03 Edit URL, Basic auth).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastSettingsViewModelTest : MainDispatcherTest() {
    private val podcasts = FakePodcastRepository()
    private val feeds = FakeFeedRepository()

    private val scopes = FakeScopeSettingsRepository().apply { seedPodcast(7) }

    private fun viewModel(podcastId: Long = 7) = PodcastSettingsViewModel(podcastId, podcasts, feeds, scopes)

    private fun TestScope.collect(viewModel: PodcastSettingsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun effectiveSettingsObserveEveryAttributionAndManualOnlyGroup() =
        runTest {
            val vm = viewModel()
            collect(vm)
            val news = SettingSource.Group("news-id", "news")
            val tech = SettingSource.Group("tech-id", "tech")
            val sources = listOf(SettingSource.AppDefault, news, SettingSource.Groups(listOf(news, tech)))
            for (source in sources) {
                scopes.seedPodcast(
                    7,
                    ScopedSettingsView(
                        SettingOverrides(),
                        SettingOverrides(null, true),
                        SettingField.entries.associateWith { source },
                    ),
                )
                advanceUntilIdle()
                val scoped = vm.uiState.value.scoped!!
                assertNull(scoped.effective.refreshIntervalMinutes)
                assertEquals(true, scoped.effective.notifyNewEpisodes)
                assertEquals(source, scoped.sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES))
                assertEquals(source, scoped.sources.getValue(SettingField.NOTIFY_NEW_EPISODES))
            }
            vm.setRefreshIntervalMinutes(60)
            vm.setNotifyNewEpisodes(false, null)
            advanceUntilIdle()
            assertEquals(
                SettingOverrides(60, false),
                vm.uiState.value.scoped!!
                    .effective,
            )
            assertTrue(
                vm.uiState.value.scoped!!
                    .sources.values
                    .all { it == SettingSource.PodcastOverride },
            )
        }

    @Test
    fun inheritClearsOnlyTheChosenOverrideAndRestoresGroupManualOnly() =
        runTest {
            val group = SettingSource.Group("news-id", "news")
            scopes.seedPodcast(
                7,
                ScopedSettingsView(
                    SettingOverrides(),
                    SettingOverrides(null, true),
                    SettingField.entries.associateWith { group },
                ),
            )
            val vm = viewModel()
            collect(vm)
            vm.setRefreshIntervalMinutes(120)
            vm.setNotifyNewEpisodes(false, null)
            advanceUntilIdle()
            vm.setRefreshIntervalMinutes(null)
            advanceUntilIdle()
            assertEquals(
                SettingOverrides(null, false),
                vm.uiState.value.scoped!!
                    .own,
            )
            assertNull(
                vm.uiState.value.scoped!!
                    .effective.refreshIntervalMinutes,
            )
            assertEquals(
                group,
                vm.uiState.value.scoped!!
                    .sources[SettingField.REFRESH_INTERVAL_MINUTES],
            )
            vm.setNotifyNewEpisodes(null, null)
            advanceUntilIdle()
            assertEquals(
                SettingOverrides(),
                vm.uiState.value.scoped!!
                    .own,
            )
            assertEquals(
                true,
                vm.uiState.value.scoped!!
                    .effective.notifyNewEpisodes,
            )
        }

    @Test
    fun manualOnlyWritesZeroAndInvalidIntervalStaysInline() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.setRefreshIntervalMinutes(0)
            advanceUntilIdle()
            assertEquals(
                0,
                vm.uiState.value.scoped!!
                    .own.refreshIntervalMinutes,
            )
            assertNull(
                vm.uiState.value.scoped!!
                    .effective.refreshIntervalMinutes,
            )
            vm.setRefreshIntervalMinutes(7)
            advanceUntilIdle()
            assertEquals(setOf(SettingField.REFRESH_INTERVAL_MINUTES), vm.uiState.value.invalidFields)
            assertEquals(
                0,
                vm.uiState.value.scoped!!
                    .own.refreshIntervalMinutes,
            )
            vm.setRefreshIntervalMinutes(null)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.invalidFields
                    .isEmpty(),
            )
        }

    @Test
    fun contextualPermissionOnlyOnExplicitEnableAndDenialKeepsTheWish() =
        runTest {
            val requester = RecordingPermissionRequester()
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            vm.setNotifyNewEpisodes(false, requester)
            vm.setNotifyNewEpisodes(null, requester)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(1, requester.requests)
            assertEquals(
                true,
                vm.uiState.value.scoped!!
                    .own.notifyNewEpisodes,
            )
            assertTrue(vm.uiState.value.notificationsBlocked)
        }

    @Test
    fun grantedAndAbsentRequestersNeverPrompt() =
        runTest {
            val requester = RecordingPermissionRequester(granted = true)
            val vm = viewModel()
            collect(vm)
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            assertEquals(false, vm.uiState.value.notificationsBlocked)
            // PlatformActions is null on desktop and Android below API 33.
            vm.setNotifyNewEpisodes(true, null)
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.notificationsBlocked)
            val granting = RecordingPermissionRequester(result = true)
            vm.setNotifyNewEpisodes(true, granting)
            advanceUntilIdle()
            assertEquals(1, granting.requests)
            assertEquals(false, vm.uiState.value.notificationsBlocked)
        }

    @Test
    fun failedScopedEnableReportsFailureWithoutRequestingPermission() =
        runTest {
            val failing =
                object : ScopeSettingsRepository by scopes {
                    override suspend fun updatePodcast(
                        podcastId: Long,
                        change: (SettingOverrides) -> SettingOverrides,
                    ): Outcome<Unit, SettingsError> = Outcome.Failure(SettingsError.WriteFailed)
                }
            val vm = PodcastSettingsViewModel(7, podcasts, feeds, failing)
            val requester = RecordingPermissionRequester()
            collect(vm)
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            assertEquals(
                false,
                vm.uiState.value.scoped!!
                    .effective.notifyNewEpisodes,
            )
            assertEquals(
                Res.string.write_failed,
                (
                    vm.uiState.value.messages
                        .single()
                        .text as UiText.Res
                ).id,
            )
        }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            assertEquals(PodcastSettingsUiState(), viewModel().uiState.value)
        }

    @Test
    fun detailAndFeedInfoLoad() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.detail.value = testPodcastDetail(7, displayTitle = "Serial Box")
            podcasts.feedInfo.value = testFeedInfo(feedUrl = "https://feeds.example.com/sb")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertEquals("Serial Box", state.detail?.displayTitle)
            assertEquals("https://feeds.example.com/sb", state.feedInfo?.feedUrl)
        }

    @Test
    fun loadedNullDetailIsGone() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.gone)
        }

    @Test
    fun effectiveOrderFollowsShowTypeAndOverride() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            podcasts.detail.value = testPodcastDetail(7, showType = ShowType.SERIAL, episodeOrder = null)
            advanceUntilIdle()
            assertEquals(FeedOrder.OLDEST_FIRST, viewModel.uiState.value.effectiveOrder)

            podcasts.detail.value =
                testPodcastDetail(7, showType = ShowType.SERIAL, episodeOrder = FeedOrder.NEWEST_FIRST)
            advanceUntilIdle()
            assertEquals(FeedOrder.NEWEST_FIRST, viewModel.uiState.value.effectiveOrder)
        }

    @Test
    fun setCustomTitleWritesTheRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setCustomTitle("My title")
            advanceUntilIdle()
            viewModel.setCustomTitle(null)
            advanceUntilIdle()
            assertEquals(listOf("setCustomTitle(7, My title)", "setCustomTitle(7, null)"), podcasts.calls)
        }

    @Test
    fun setOrderPersistsPerPodcast() =
        runTest {
            val viewModel = viewModel()
            viewModel.setOrder(FeedOrder.OLDEST_FIRST)
            advanceUntilIdle()
            assertEquals(
                mapOf<FeedSource, FeedOrder>(FeedSource.Podcast(7) to FeedOrder.OLDEST_FIRST),
                feeds.orders.value,
            )
        }

    @Test
    fun setIncludeInAllWritesTheRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setIncludeInAll(false)
            advanceUntilIdle()
            viewModel.setIncludeInAll(true)
            advanceUntilIdle()
            assertEquals(
                listOf("setIncludeInAll(7, false)", "setIncludeInAll(7, true)"),
                podcasts.calls,
            )
        }

    @Test
    fun editFeedUrlReturnsTheOutcome() =
        runTest {
            val viewModel = viewModel()

            val ok = viewModel.editFeedUrl("https://new.example.com/feed")
            assertIs<Outcome.Success<*>>(ok)
            assertEquals(listOf("editFeedUrl(7, https://new.example.com/feed)"), podcasts.calls)

            podcasts.editUrlOutcome = Outcome.Failure(AddPodcastError.InvalidUrl)
            val bad = viewModel.editFeedUrl("not a url")
            assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), bad)
        }

    @Test
    fun setCredentialsReturnsTheOutcome() =
        runTest {
            val viewModel = viewModel()
            assertIs<Outcome.Success<*>>(viewModel.setCredentials(BasicCredentials("u", "p")))
            assertEquals(listOf("setCredentials(7, u)"), podcasts.calls)

            podcasts.credentialsOutcome = Outcome.Failure(AddPodcastError.Network(NetError.Offline))
            assertIs<Outcome.Failure<*>>(viewModel.setCredentials(BasicCredentials("u", "p")))
        }

    /** A throwing edit returns null for the dialog's inline error and never escapes into the UI. */
    @Test
    fun editFeedUrlFaultReturnsNull() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            assertNull(viewModel.editFeedUrl("https://new.example.com/feed"))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun setCredentialsFaultPostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            assertNull(viewModel.setCredentials(BasicCredentials("u", "p")))
            advanceUntilIdle()
            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.write_failed, (message.text as UiText.Res).id)
        }

    /** A throwing repository write becomes a snackbar message — not an uncaught exception (01). */
    @Test
    fun setCustomTitleWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            viewModel.setCustomTitle("My show")
            advanceUntilIdle()

            assertTrue(podcasts.calls.isEmpty())
            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.write_failed, (message.text as UiText.Res).id)

            viewModel.onMessageShown(message.id)
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun setIncludeInAllWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            viewModel.setIncludeInAll(false)
            advanceUntilIdle()

            assertTrue(podcasts.calls.isEmpty())
            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test
    fun feedOrderWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            feeds.writeError = Exception("disk full")

            viewModel.setOrder(FeedOrder.OLDEST_FIRST)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }
}

private class RecordingPermissionRequester(
    override var granted: Boolean = false,
    private val result: Boolean = false,
) : NotificationPermissionRequester {
    var requests = 0

    override fun request(onResult: (Boolean) -> Unit) {
        requests++
        granted = result
        onResult(result)
    }

    override fun openSettings() = Unit
}
