// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import ch.lkmc.neutrodyne.core.designsystem.components.NdModalBottomSheet
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata

/**
 * An [OverlayScene] rendering [entry] inside [NdModalBottomSheet] (01 overlay scene strategies).
 * On the desktop the sheet is window-backed, so it draws above the expanded player; Nav3 ships no
 * bottom-sheet strategy, so this is ours (the nav3-recipes recipe adapted to `NdModalBottomSheet`).
 * `onRemove` is not overridden at M0a: the window closes without an exit animation.
 */
private class NdSheetScene<T : Any>(
    override val key: Any,
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        val lifecycleOwner = rememberLifecycleOwner()
        NdModalBottomSheet(onDismissRequest = onBack) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                entry.Content()
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NdSheetScene<*> &&
            key == other.key &&
            previousEntries == other.previousEntries &&
            overlaidEntries == other.overlaidEntries &&
            entry == other.entry

    override fun hashCode(): Int =
        key.hashCode() * 31 +
            previousEntries.hashCode() * 31 +
            overlaidEntries.hashCode() * 31 +
            entry.hashCode()

    override fun toString(): String = "NdSheetScene(key=$key, entry=$entry)"
}

/**
 * The strategy claiming entries whose metadata carries [NdSceneMetadata.bottomSheet]; always listed
 * before the pane strategies so a sheet overlays the underlying scene.
 */
public class NdBottomSheetSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull() ?: return null
        if (lastEntry.metadata[NdSceneMetadata.KEY_OVERLAY] != NdSceneMetadata.OVERLAY_SHEET) return null
        return NdSheetScene(
            key = lastEntry.contentKey,
            entry = lastEntry,
            previousEntries = entries.dropLast(1),
            overlaidEntries = entries.dropLast(1),
            onBack = onBack,
        )
    }
}

@Composable
public fun <T : Any> rememberNdBottomSheetSceneStrategy(): NdBottomSheetSceneStrategy<T> =
    remember { NdBottomSheetSceneStrategy() }

/**
 * An [OverlayScene] rendering [entry] in a `Dialog` (like Nav3's `DialogScene`) — [NdDialog] is a
 * plain `AlertDialog` wrapper, so the raw `Dialog` is the same window surface. Kept separate from
 * Nav3's strategy because our metadata contract is [NdSceneMetadata], not `DialogSceneStrategy.dialog`.
 */
private class NdDialogScene<T : Any>(
    override val key: Any,
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        val lifecycleOwner = rememberLifecycleOwner()
        androidx.compose.ui.window.Dialog(onDismissRequest = onBack) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                entry.Content()
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NdDialogScene<*> &&
            key == other.key &&
            previousEntries == other.previousEntries &&
            overlaidEntries == other.overlaidEntries &&
            entry == other.entry

    override fun hashCode(): Int =
        key.hashCode() * 31 +
            previousEntries.hashCode() * 31 +
            overlaidEntries.hashCode() * 31 +
            entry.hashCode()

    override fun toString(): String = "NdDialogScene(key=$key, entry=$entry)"
}

/** The strategy claiming entries whose metadata carries [NdSceneMetadata.dialog]. */
public class NdDialogSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull() ?: return null
        if (lastEntry.metadata[NdSceneMetadata.KEY_OVERLAY] != NdSceneMetadata.OVERLAY_DIALOG) return null
        return NdDialogScene(
            key = lastEntry.contentKey,
            entry = lastEntry,
            previousEntries = entries.dropLast(1),
            overlaidEntries = entries.dropLast(1),
            onBack = onBack,
        )
    }
}

@Composable
public fun <T : Any> rememberNdDialogSceneStrategy(): NdDialogSceneStrategy<T> = remember { NdDialogSceneStrategy() }
