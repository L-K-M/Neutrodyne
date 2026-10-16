// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AddToGroupsSheetTest {
    private var previousLocale = Locale.getDefault()
    private val groups = FakeGroupRepository()
    private val podcasts = FakePodcastRepository()
    private lateinit var vm: AddToGroupsViewModel
    private var cancelled = false
    private val lifecycleOwner = ResumedLifecycleOwner()

    @BeforeTest fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        podcasts.tiles.value = listOf(testLibraryTile(1), testLibraryTile(2), testLibraryTile(3))
        runBlocking {
            val all = (groups.create(GroupDraft("Tech")) as Outcome.Success).value
            val some = (groups.create(GroupDraft("News")) as Outcome.Success).value
            groups.create(GroupDraft("Fiction"))
            groups.members.value = mapOf(all to setOf(1, 2, 3), some to setOf(1))
        }
        vm = AddToGroupsViewModel(listOf(1, 2, 3), groups, podcasts)
    }

    @AfterTest fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test fun mixedChipsExposeAccessibleStateAndOnlyChangedChipIsApplied() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("Add 3 podcasts to groups").assertIsDisplayed()
            onNodeWithText("Tech").assert(selection(ToggleableState.On))
            onNodeWithText("News").assert(selection(ToggleableState.Indeterminate)).performClick()
            onNodeWithText("News").assert(selection(ToggleableState.On)).performClick()
            onNodeWithText("News").assert(selection(ToggleableState.Off))
            onNodeWithText("Fiction").assert(selection(ToggleableState.Off))
            onNodeWithText("Done").performClick()
            waitUntil { vm.uiState.value.doneCount != null }
            assertEquals(setOf(1L, 2L, 3L), groups.members.value[1])
            assertEquals(emptySet(), groups.members.value[2])
        }

    @Test fun inlineNewGroupIsCheckedAndCancelDismisses() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("+ New group").performClick()
            onNode(hasSetTextAction()).performTextInput("Science")
            onNodeWithText("Create group").performClick()
            waitUntil {
                vm.uiState.value.chips
                    .any { it.group.name == "Science" }
            }
            onNodeWithText("Science").assert(selection(ToggleableState.On))
            onNodeWithText("Cancel").performClick()
            assertEquals(true, cancelled)
        }

    @Test fun onePodcastUsesItsTitle() =
        runComposeUiTest {
            vm = AddToGroupsViewModel(listOf(1), groups, podcasts)
            setSheet()
            onAllNodesWithText("Groups for Podcast 1").fetchSemanticsNodes().let {
                assertEquals(1, it.size)
            }
        }

    private fun ComposeUiTest.setSheet() {
        setContent {
            val state by vm.uiState.collectAsStateWithLifecycle(lifecycle = lifecycleOwner.lifecycle)
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                AddToGroupsSheet(state, vm::toggle, vm::editName, vm::createGroup, vm::done, { cancelled = true })
            }
        }
        waitUntil { vm.uiState.value.ready }
        waitForIdle()
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle =
            LifecycleRegistry.createUnsafe(this).apply {
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
    }

    private fun selection(state: ToggleableState) =
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)
}
