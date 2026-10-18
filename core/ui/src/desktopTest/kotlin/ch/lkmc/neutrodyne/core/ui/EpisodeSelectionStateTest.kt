// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_play_last
import ch.lkmc.neutrodyne.core.ui.resources.action_play_next
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EpisodeSelectionStateTest {
    @Test
    fun leavingAndReturningToSavedEntryDoesNotRestoreSelection() =
        runComposeUiTest {
            val selection =
                EpisodeSelectionController(
                    CoroutineScope(Dispatchers.Unconfined),
                    { _, _ -> },
                    { throw it },
                )
            var mounted by mutableStateOf(true)
            val owner =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry.createUnsafe(this).apply {
                            currentState =
                                Lifecycle.State.RESUMED
                        }
                }
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val holder = rememberSaveableStateHolder()
                    if (mounted) holder.SaveableStateProvider("feeds") { BindEpisodeSelection(selection) }
                }
            }
            runOnIdle { selection.toggle(2) }
            runOnIdle { mounted = false }
            runOnIdle {
                assertTrue(selection.ids.value.isEmpty())
                mounted = true
            }
            runOnIdle { assertTrue(selection.ids.value.isEmpty()) }
        }

    @Test
    fun selectionMenuOffersQueueActions() =
        runComposeUiTest {
            val selection =
                EpisodeSelectionController(
                    CoroutineScope(Dispatchers.Unconfined),
                    { _, _ -> },
                    { throw it },
                )
            val more = getString(Res.string.action_more)
            val nextLabel = getString(Res.string.action_play_next)
            val lastLabel = getString(Res.string.action_play_last)
            var next = 0
            var last = 0
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SelectionTopBar(selection, 2, {}, onPlayNext = { next++ }, onPlayLast = { last++ })
                }
            }
            onNodeWithContentDescription(more).performClick()
            onNodeWithText(nextLabel).performClick()
            onNodeWithContentDescription(more).performClick()
            onNodeWithText(lastLabel).performClick()
            assertEquals(1, next)
            assertEquals(1, last)
        }

    @Test
    fun restoredSnapshotKeepsDeselectedRangeAnchor() {
        val selection =
            EpisodeSelectionController(
                CoroutineScope(Dispatchers.Unconfined),
                { _, _ -> },
                { throw it },
            )
        selection.toggle(1)
        selection.toggle(3)
        selection.toggle(3)
        val saved = selection.snapshot()
        selection.clear()
        selection.restore(saved)
        selection.extend(4, listOf(1, 2, 3, 4))
        assertEquals(setOf(1L, 3L, 4L), selection.ids.value)
    }
}
