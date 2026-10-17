// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AllGroupsViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val feeds = FakeFeedRepository()
    private val settings = FakeSettingsRepository()

    private fun modelTest(
        repository: SettingsRepository = settings,
        block: suspend TestScope.(AllGroupsViewModel) -> Unit,
    ) = runTest {
        val store = ViewModelStore()
        val model = AllGroupsViewModel(groups, feeds, repository, AllGroupsTestCollator, TestClock())
        store.put("all-groups", model)
        try {
            backgroundScope.launch { model.uiState.collect {} }
            runCurrent()
            block(model)
        } finally {
            store.clear()
        }
    }

    @Test
    fun recentUsesDescendingVisitTimeAndCapsAtFour() {
        groups.groups.value = (1L..6L).map { allGroupsTestGroup(it, lastViewedAt = it * 100) } + allGroupsTestGroup(7)
        modelTest { model ->
            assertEquals(
                listOf(6L, 5L, 4L, 3L),
                model.uiState.value.recent
                    .map { it.group.id },
            )
            assertEquals(7, model.uiState.value.groups.size)
        }
    }

    @Test
    fun unvisitedGroupsAreNotRecentAndEqualVisitsUseTitleOrder() {
        groups.groups.value =
            listOf(
                allGroupsTestGroup(1, "Zoo", lastViewedAt = 100),
                allGroupsTestGroup(2, "Beta", lastViewedAt = 100),
                allGroupsTestGroup(3, "Unvisited"),
            )
        modelTest { model ->
            assertEquals(
                listOf("Beta", "Zoo"),
                model.uiState.value.recent
                    .map { it.group.name },
            )
        }
    }

    @Test
    fun alphabeticalOrderUsesTitleCollatorRatherThanCodepointsOrTabOrder() {
        groups.groups.value =
            listOf(
                allGroupsTestGroup(1, "Zoo"),
                allGroupsTestGroup(2, "beta"),
                allGroupsTestGroup(3, "Äpfel"),
            )
        modelTest { model ->
            assertEquals(
                listOf("Äpfel", "beta", "Zoo"),
                model.uiState.value.groups
                    .map { it.group.name },
            )
            assertTrue(
                model.uiState.value.recent
                    .isEmpty(),
            )
        }
    }

    @Test
    fun hiddenGroupsKeepVisibilityFlagAndReceiveLiveMosaicsAndCounts() {
        groups.groups.value = listOf(allGroupsTestGroup(1, showAsTab = false))
        modelTest { model ->
            assertFalse(
                model.uiState.value.groups
                    .single()
                    .group.showAsTab,
            )
            val member = GroupMosaicMember(1, 4, "A podcast", ArtworkRef("art", null, 0), null)
            groups.mosaics.value = mapOf(1L to listOf(member))
            feeds.groupCounts.value = mapOf(1L to FeedCounts(12, 3))
            runCurrent()
            assertEquals(
                listOf(member),
                model.uiState.value.groups
                    .single()
                    .members,
            )
            assertEquals(
                FeedCounts(12, 3),
                model.uiState.value.groups
                    .single()
                    .counts,
            )
            groups.groups.value = emptyList()
            runCurrent()
            assertTrue(
                model.uiState.value.groups
                    .isEmpty(),
            )
            assertTrue(model.uiState.value.loaded)
        }
    }

    @Test
    fun selectingVisibleOrHiddenGroupWritesUuidReferenceAndCompletesOnce() {
        groups.groups.value = listOf(allGroupsTestGroup(1), allGroupsTestGroup(2, showAsTab = false))
        modelTest { model ->
            for (group in groups.groups.value) {
                model.selectGroup(group.id)
                runCurrent()
                assertEquals(
                    FeedSourceRef.Group(group.uuid),
                    FeedSourceRef.decode(settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE)),
                )
                assertTrue(model.consumeSelection())
                assertFalse(model.consumeSelection())
                runCurrent()
            }
        }
    }

    @Test
    fun failedSelectionStaysOpenAndCanBeRetried() {
        groups.groups.value = listOf(allGroupsTestGroup(1))
        settings.failNextSet = SettingsError.OutOfRange("disk")
        modelTest { model ->
            model.selectGroup(1)
            runCurrent()
            assertFalse(model.consumeSelection())
            assertFalse(model.uiState.value.selecting)
            assertEquals("all", settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            assertNotNull(model.uiState.value.error)
            model.selectGroup(1)
            runCurrent()
            assertTrue(model.consumeSelection())
            assertNull(model.uiState.value.error)
        }
    }

    @Test
    fun thrownWriteAlsoStaysOpenAndReportsFailure() {
        groups.groups.value = listOf(allGroupsTestGroup(1))
        val throwing =
            object : SettingsRepository by settings {
                override suspend fun <T : Any> set(
                    key: SettingKey<T>,
                    value: T,
                ): Outcome<Unit, SettingsError> = error("disk")
            }
        modelTest(throwing) { model ->
            model.selectGroup(1)
            runCurrent()
            assertFalse(model.consumeSelection())
            assertFalse(model.uiState.value.selecting)
            assertNotNull(model.uiState.value.error)
        }
    }

    @Test
    fun pendingWriteDoesNotCompleteEarlyAndIgnoresRepeatedTaps() {
        groups.groups.value = listOf(allGroupsTestGroup(1), allGroupsTestGroup(2))
        val gate = CompletableDeferred<Unit>()
        val delayed =
            object : SettingsRepository by settings {
                override suspend fun <T : Any> set(
                    key: SettingKey<T>,
                    value: T,
                ): Outcome<Unit, SettingsError> {
                    gate.await()
                    return settings.set(key, value)
                }
            }
        modelTest(delayed) { model ->
            model.selectGroup(1)
            model.selectGroup(2)
            runCurrent()
            assertTrue(model.uiState.value.selecting)
            assertFalse(model.consumeSelection())
            gate.complete(Unit)
            runCurrent()
            assertTrue(model.consumeSelection())
            assertEquals(listOf("set(ui.feeds_selected_source)"), settings.calls)
            assertEquals(
                FeedSourceRef.Group(allGroupsTestGroup(1).uuid).encode(),
                settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE),
            )
        }
    }

    @Test
    fun missingGroupDoesNotWriteOrClose() =
        modelTest { model ->
            model.selectGroup(99)
            runCurrent()
            assertTrue(model.uiState.value.loaded)
            assertTrue(settings.calls.isEmpty())
            assertFalse(model.consumeSelection())
        }
}

internal object AllGroupsTestCollator : TitleCollator {
    override fun compare(
        a: String,
        b: String,
    ): Int = a.lowercase().replace('ä', 'a').compareTo(b.lowercase().replace('ä', 'a'))
}

internal fun allGroupsTestGroup(
    id: Long,
    name: String = "Group $id",
    lastViewedAt: Long? = null,
    showAsTab: Boolean = true,
): Group =
    Group(
        id = id,
        uuid = "00000000-0000-4000-8000-${id.toString().padStart(12, '0')}",
        name = name,
        orderKey = id.toString(),
        colorArgb = null,
        iconKey = null,
        feedOrder = FeedOrder.NEWEST_FIRST,
        playOrder = FeedOrder.NEWEST_FIRST,
        filterFlags = 0,
        mediaFilter = MediaFilter.ALL,
        hideOlderThanDays = null,
        showAsTab = showAsTab,
        lastViewedAt = lastViewedAt,
        createdAt = 0,
        memberCount = 0,
    )
