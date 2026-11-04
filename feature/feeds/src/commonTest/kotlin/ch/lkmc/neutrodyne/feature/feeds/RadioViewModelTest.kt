// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.core.ui.radio.RadioEpisode
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val episodes = FakeEpisodeRepository()
    private val controller = FakePlaybackController().apply { playResult = PlayResult.Started }
    private val live = FakeEpisodeLiveStateSource()
    private val settings = FakeSettingsRepository()
    private val feeds = FakeFeedRepository()
    private val podcasts = FakePodcastRepository()
    private val rows = (1L..3L).map { RadioEpisode(it, "Episode $it", "Podcast", 60_000) }

    private suspend fun TestScope.withRadio(block: suspend (RadioViewModel, Long) -> Unit) {
        val id = (groups.create(GroupDraft("First"), setOf(1)) as Outcome.Success).value
        podcasts.tiles.value = listOf(testLibraryTile(1))
        val model =
            RadioViewModel(
                groups,
                feeds,
                podcasts,
                episodes,
                live,
                controller,
                controller.state,
                settings,
                testPlatformInfo,
            )
        val store = ViewModelStore().apply { put("radio", model) }
        try {
            runCurrent()
            model.onRows(id, rows)
            model.onVisibleIds(rows.map { it.id }.toSet())
            runCurrent()
            block(model, id)
        } finally {
            store.clear()
        }
    }

    @Test fun selectingSameEpisodeIsInertAndOtherSelectionUsesSavedPosition() =
        runTest {
            live.live.value = mapOf(2L to RowLive(positionMs = 12_000))
            withRadio { model, _ ->
                val before = controller.calls.size
                model.selectEpisode(1)
                assertEquals(before, controller.calls.size)
                model.selectEpisode(2)
                assertEquals(12_000, model.state.value.elapsedMs)
                assertFalse(model.state.value.playing)
                assertEquals(before, controller.calls.size)
            }
        }

    @Test fun playUsesGroupContextAndPendingNeedleSeek() =
        runTest {
            withRadio { model, id ->
                model.seek(25_000)
                assertTrue(
                    controller.calls.none {
                        it is FakePlaybackController.Call.Transport &&
                            it.action == FakePlaybackController.Action.SEEK
                    },
                )
                model.togglePlay()
                runCurrent()
                val start = controller.calls.filterIsInstance<FakePlaybackController.Call.Feed>().single()
                assertEquals(FeedSource.Group(id), start.source)
                assertEquals(1L, start.startId)
                assertTrue(start.filters.unplayedOnly)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.SEEK, 25_000),
                    controller.calls.last(),
                )
            }
        }

    @Test fun discardSelectedAdvancesWhileOtherRemovalLeavesPlayerAloneAndUndoDoesNotRewind() =
        runTest {
            withRadio { model, _ ->
                controller.state.nowPlaying.value = testNowPlaying(1).copy(isPlaying = true)
                runCurrent()
                model.discard(1)
                runCurrent()
                assertEquals(2L, model.state.value.selectedId)
                assertEquals(
                    2L,
                    controller.calls
                        .filterIsInstance<FakePlaybackController.Call.Feed>()
                        .last()
                        .startId,
                )
                assertEquals("setPlayed([1], true)", episodes.calls.last())
                model.undoDiscard()
                runCurrent()
                assertEquals(
                    listOf(1L, 2L, 3L),
                    model.state.value.episodes
                        .map { it.id },
                )
                assertEquals(2L, model.state.value.selectedId)
                assertEquals("setPlayed([1], false)", episodes.calls.last())
                val calls = controller.calls.size
                model.discard(3)
                runCurrent()
                assertEquals(calls, controller.calls.size)
            }
        }

    @Test fun removingLastStopsAndUndoLeavesSelectionEmpty() =
        runTest {
            withRadio { model, id ->
                model.onRows(id, rows.take(1))
                model.discard(1)
                runCurrent()
                assertNull(model.state.value.selectedId)
                assertFalse(model.state.value.playing)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.DISMISS),
                    controller.calls.last(),
                )
                model.undoDiscard()
                runCurrent()
                assertNull(model.state.value.selectedId)
                assertEquals(
                    listOf(1L),
                    model.state.value.episodes
                        .map { it.id },
                )
            }
        }

    @Test fun switchingPausesSelectsFirstAndScopesRemovalFeedback() =
        runTest {
            withRadio { model, first ->
                model.discard(3)
                runCurrent()
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                model.selectPlaylist(second)
                model.onRows(second, rows.reversed())
                runCurrent()
                assertEquals(3L, model.state.value.selectedId)
                assertFalse(model.state.value.playing)
                assertNull(model.state.value.removed)
                model.selectPlaylist(first)
                model.onRows(first, rows.take(2))
                runCurrent()
                assertEquals(1L, model.state.value.selectedId)
                assertEquals(
                    3L,
                    model.state.value.removed
                        ?.id,
                )
            }
        }

    @Test fun createUsesExistingNameRulesAndRealMembershipAndClearsUndo() =
        runTest {
            withRadio { model, first ->
                model.discard(3)
                runCurrent()
                model.createPlaylist("  Evening  ", setOf(1))
                runCurrent()
                val created = groups.groups.value.single { it.name == "Evening" }
                assertEquals(setOf(1L), groups.members.value[created.id])
                assertEquals(created.id, model.state.value.playlistId)
                assertNull(model.state.value.removed)
                model.createPlaylist("evening", setOf(1))
                runCurrent()
                assertTrue(model.state.value.createError != null)
                model.createPlaylist("x".repeat(41), setOf(1))
                runCurrent()
                assertEquals(2, groups.groups.value.size)
                assertTrue(first != created.id)
            }
        }

    @Test fun queueAutoAdvanceOutsideGroupStillDrivesRadioAndResumesContext() =
        runTest {
            withRadio { model, id ->
                controller.state.nowPlaying.value =
                    testNowPlaying(99).copy(
                        context =
                            ch.lkmc.neutrodyne.core.model.PlayContextInfo(
                                ch.lkmc.neutrodyne.core.model.ContextType.GROUP,
                                id,
                                "First",
                                ch.lkmc.neutrodyne.core.model.FeedOrder.NEWEST_FIRST,
                                0,
                                ch.lkmc.neutrodyne.core.model.MediaFilter.ALL,
                            ),
                    )
                runCurrent()
                model.onRows(id, rows)
                assertEquals(99L, model.state.value.selectedId)
                assertEquals(
                    99L,
                    model.state.value.selected
                        ?.id,
                )
            }
        }

    @Test fun failedMeteredAlwaysDoesNotGrantConsent() =
        runTest {
            withRadio { model, _ ->
                val prompt = PlayResult.NeedsMeteredConsent(1)
                controller.playResult = prompt
                controller.state.pendingMeteredConsent.value = prompt
                model.togglePlay()
                runCurrent()
                settings.failNextSet = ch.lkmc.neutrodyne.core.domain.SettingsError.WriteFailed
                model.playback.consentAlways()
                runCurrent()
                assertTrue(
                    controller.calls.none {
                        it ==
                            FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT)
                    },
                )
            }
        }

    @Test fun incompletePageShowsRepositoryCountAndUndoKeepsChronology() =
        runTest {
            withRadio { model, id ->
                feeds.unplayedCounts[FeedSource.Group(id) to null] = 500
                val dated = rows.map { it.copy(sortDate = 100 - it.id) }
                model.onRows(id, dated, complete = false)
                runCurrent()
                assertEquals(500, model.state.value.episodeCount)
                assertFalse(model.state.value.complete)
                model.discard(2)
                runCurrent()
                val newest = RadioEpisode(99, "New", "Podcast", 60_000, 200)
                model.onRows(id, listOf(newest) + dated.filterNot { it.id == 2L })
                model.undoDiscard()
                runCurrent()
                assertEquals(
                    listOf(99L, 1L, 2L, 3L),
                    model.state.value.episodes
                        .map { it.id },
                )
            }
        }

    @Test fun pagingAccessUsesAbsoluteOffsetsAndIgnoresStaleRows() {
        assertEquals(401, radioPageIndex(42, 400, listOf(41, 42, 43)))
        assertEquals(1, radioPageIndex(42, 0, listOf(41, 42, 43)))
        assertNull(radioPageIndex(1, 400, listOf(41, 42, 43)))
    }

    @Test fun deletingAndUndoingTheActiveGroupDoesNotReselectIt() =
        runTest {
            withRadio { model, id ->
                val uuid =
                    groups
                        .observeGroups()
                        .first()
                        .first { it.id == id }
                        .uuid
                settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef
                        .Group(uuid)
                        .encode(),
                )
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                val token = (groups.delete(id) as Outcome.Success).value
                runCurrent()
                assertEquals(second, model.state.value.playlistId)
                groups.undoDelete(token)
                runCurrent()
                assertEquals(second, model.state.value.playlistId)
            }
        }

    @Test fun failedFallbackWriteDoesNotLetGroupUndoRestoreSelection() =
        runTest {
            withRadio { model, id ->
                val uuid =
                    groups
                        .observeGroups()
                        .first()
                        .first { it.id == id }
                        .uuid
                settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef
                        .Group(uuid)
                        .encode(),
                )
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                settings.failNextSet = ch.lkmc.neutrodyne.core.domain.SettingsError.WriteFailed
                val token = (groups.delete(id) as Outcome.Success).value
                runCurrent()
                groups.undoDelete(token)
                runCurrent()
                assertEquals(second, model.state.value.playlistId)
                model.selectPlaylist(id)
                runCurrent()
                assertEquals(id, model.state.value.playlistId)
            }
        }

    @Test fun droppingEarlierPagesKeepsThePausedSelectionAndItsResumePosition() =
        runTest {
            live.live.value = mapOf(2L to RowLive(positionMs = 12_000))
            withRadio { model, id ->
                model.selectEpisode(2)
                model.onRows(id, (100L..200L).map { RadioEpisode(it, "Episode $it", "Podcast") }, complete = false)
                runCurrent()
                assertEquals(2L, model.state.value.selectedId)
                assertEquals(
                    "Episode 2",
                    model.state.value.selected
                        ?.title,
                )
                assertEquals(12_000L, model.state.value.elapsedMs)
            }
        }

    @Test fun loadedPagesDoNotBecomeAnOffscreenLiveQueryWindow() =
        runTest {
            val id = (groups.create(GroupDraft("Bounded"), setOf(1)) as Outcome.Success).value
            val model =
                RadioViewModel(
                    groups,
                    feeds,
                    podcasts,
                    episodes,
                    live,
                    controller,
                    controller.state,
                    settings,
                    testPlatformInfo,
                )
            val store = ViewModelStore().apply { put("radio", model) }
            try {
                runCurrent()
                model.onRows(id, (1L..200L).map { RadioEpisode(it, "Episode $it", "Podcast") })
                runCurrent()
                assertTrue(live.lastVisibleIds.value.isEmpty())
                model.onVisibleIds(setOf(100, 101, 102))
                runCurrent()
                assertEquals(setOf(1L, 100L, 101L, 102L), live.lastVisibleIds.value)
                model.selectEpisode(100)
                runCurrent()
                assertEquals(setOf(100L, 101L, 102L), live.lastVisibleIds.value)
                model.onVisibleIds(emptySet())
                runCurrent()
                assertTrue(live.lastVisibleIds.value.isEmpty())
            } finally {
                store.clear()
            }
        }

    @Test fun failedDiscardDoesNotRemoveAnythingOrOfferUndo() =
        runTest {
            withRadio { model, _ ->
                episodes.writeError = IllegalStateException("write refused")
                model.discard(1)
                runCurrent()
                assertEquals(rows, model.state.value.episodes)
                assertNull(model.state.value.removed)
                assertEquals(1L, model.state.value.selectedId)
            }
        }
}
