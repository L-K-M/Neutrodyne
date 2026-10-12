// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategyScope
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaneSceneStrategyTest {
    @Test
    fun predictivePrefixHasTheCommittedAnimationKey() {
        val fixture = Fixture()
        fixture.navigation.open(FeedsKey, listOf(SettingsHomeKey, About, LicencesKey))
        val current = fixture.current()
        val preview = fixture.calculate(current.previousEntries)
        assertNotEquals(current.key, preview.key, "the prefix reused the departing pane identity")

        fixture.navigation.pop()
        val committed = fixture.current()
        assertEquals(preview.key, committed.key, "preview and committed scenes had different identities")
    }

    @Test
    fun allocationChangesKeepTheScaffoldIdentity() {
        val fixture = Fixture()
        fixture.navigation.open(FeedsKey, listOf(SettingsHomeKey, About))
        val detail = fixture.current()
        fixture.navigation.push(EpisodeKey(1))
        val extra = fixture.current()
        assertEquals(detail.key, extra.key, "opening the extra pane restarted the adaptive scaffold")

        fixture.navigation.pop()
        assertEquals(extra.key, fixture.current().key, "closing the extra pane restarted the adaptive scaffold")
    }

    @Test
    fun boundaryTargetsAgreeEvenWhenTheImmediatePopHasTheSameValue() {
        val fixture = Fixture()
        fixture.navigation.open(FeedsKey, listOf(SettingsHomeKey, About, EpisodeKey(1)))
        fixture.current()
        fixture.navigation.push(EpisodeKey(2))
        val current = fixture.current()
        assertEquals(
            listOf(FeedsKey, SettingsHomeKey, About),
            current.previousEntries.map { it.metadata.getValue(PANE_NAV_KEY) },
        )
        val preview = fixture.calculate(current.previousEntries)
        fixture.navigation.pop()
        fixture.navigation.pop()
        assertEquals(preview.key, fixture.current().key)
    }

    @Test
    fun coversClaimOnlyTheRenderedPaneTops() {
        val fixture = Fixture()
        fixture.navigation.open(FeedsKey, listOf(SettingsHomeKey, About))
        val old = fixture.current()
        fixture.navigation.push(LicencesKey)
        val covering = fixture.current()

        assertEquals(listOf(SettingsHomeKey, LicencesKey), covering.entries.map { it.metadata.getValue(PANE_NAV_KEY) })
        assertTrue(About in fixture.navigation.stack(FeedsKey), "claim masking changed logical membership")
        assertEquals(NdSceneTransition.PaneCover, classifyTransition(old, covering))
    }

    @Test
    fun libraryFallbackRemovesOneEntryWithoutTargetingFeeds() {
        val fixture = Fixture()
        fixture.navigation.open(LibraryKey, listOf(SettingsHomeKey, About))
        val scene = fixture.current()
        val preview = fixture.calculate(scene.previousEntries)
        assertEquals(SettingsHomeKey, scene.previousEntries.last().metadata[PANE_NAV_KEY])
        assertEquals(LibraryKey, preview.entries.last().metadata[NdSceneMetadata.KEY_TAB])
    }

    @Test
    fun equalRoutesInDifferentTabsHaveDifferentSceneIdentities() {
        val fixture = Fixture()
        fixture.navigation.open(FeedsKey, listOf(SettingsHomeKey, About))
        val feeds = fixture.current()
        fixture.navigation.open(LibraryKey, listOf(SettingsHomeKey, About))
        assertNotEquals(feeds.key, fixture.current().key)
    }

    private class Fixture {
        val navigation =
            NavigationState(
                TOP_LEVEL_TABS.associateWith { NavBackStack<NavKey>(it) },
                mutableStateOf(FeedsKey),
            )
        private val strategy = PaneSceneStrategy(navigation) { key, tab -> entry(key, tab) }
        private val scope = SceneStrategyScope<NavKey>()
        private val adaptive =
            ListDetailSceneStrategy<NavKey>(
                shouldHandleSinglePaneLayout = false,
                backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange,
                directive = PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = 2),
                adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
                paneExpansionDragHandle = null,
                paneExpansionState = null,
            )

        fun current(): Scene<NavKey> {
            val tabs = navigation.tabs.associateWith { tab -> navigation.stack(tab).map { entry(it, tab) } }
            strategy.updateCurrent(adaptive, tabs, emptyMap())
            return calculate(navigation.visibleTabs().flatMap { tabs.getValue(it) })
        }

        fun calculate(entries: List<NavEntry<NavKey>>): Scene<NavKey> =
            requireNotNull(with(strategy) { scope.calculateScene(entries) })

        private fun entry(
            key: NavKey,
            tab: TopLevelKey,
        ): NavEntry<NavKey> {
            val role =
                when (key) {
                    is TopLevelKey, SettingsHomeKey -> {
                        NdSceneMetadata.paneList() + ListDetailSceneStrategy.listPane(tab)
                    }

                    is EpisodeKey -> {
                        NdSceneMetadata.paneExtra() + ListDetailSceneStrategy.extraPane(tab)
                    }

                    else -> {
                        NdSceneMetadata.paneDetail() + ListDetailSceneStrategy.detailPane(tab)
                    }
                }
            val metadata = role + mapOf(NdSceneMetadata.KEY_TAB to tab, PANE_NAV_KEY to key)
            return NavEntry(key, "$tab:$key", metadata) {}
        }
    }

    private companion object {
        val About = SettingsKey(SettingsPage.ABOUT)
    }
}
