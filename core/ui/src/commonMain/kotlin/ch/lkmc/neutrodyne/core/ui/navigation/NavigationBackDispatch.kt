// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey

private val LocalEntryBackDispatch = compositionLocalOf<EntryBackOwner?> { null }

/** Native handlers drive preview only; one real handler owns each completed Back event. */
@Composable
@Suppress("FunctionNaming") // Compose functions use PascalCase.
internal fun NavigationBackDispatch(panes: PaneSceneStrategy, content: @Composable () -> Unit) {
    val parent = LocalNavigationEventDispatcherOwner.current ?: rememberNavigationEventDispatcherOwner()

    val events = remember(parent, panes) { BackEventOwner(panes) }
    val entries = remember(parent, panes) { EntryBackOwner(parent, panes) }
    DisposableEffect(events) {
        events.attach(parent.navigationEventDispatcher)
        onDispose { events.dispose() }
    }
    val enabled = panes.canGoBack
    val selectionGeneration = panes.selectionGeneration
    SideEffect {
        events.cancelPreviewIfSelectionChanged(selectionGeneration)
        events.isBackEnabled = enabled
    }

    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides events,
        LocalEntryBackDispatch provides entries,
    ) { content() }
}

private class BackEventOwner(
    private val panes: PaneSceneStrategy,
) : NavigationEventDispatcherOwner {
    override val navigationEventDispatcher = NavigationEventDispatcher()
    private val preview = DirectNavigationEventInput()
    private var origin: BackOrigin? = null
    private var previewActive = false
    private val handler =
        object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
            override fun onBackStarted(event: NavigationEvent) {
                origin = BackOrigin(panes.currentTab, panes.selectionGeneration)
                previewActive = true
                preview.backStarted(event)
            }

            override fun onBackProgressed(event: NavigationEvent) {
                cancelPreviewIfSelectionChanged(panes.selectionGeneration)
                if (previewActive) preview.backProgressed(event)
            }

            override fun onBackCancelled() {
                cancelPreview()
                origin = null
            }

            override fun onBackCompleted() {
                // Completion-only input reaches this once too, without a synthetic gesture token.
                panes.completeBack(
                    origin?.tab ?: panes.currentTab,
                    origin?.selectionGeneration ?: panes.selectionGeneration,
                )
                // End native seeking after the real stack changed, without executing its repeat callbacks.
                cancelPreview()
                origin = null
            }
        }

    fun attach(parent: NavigationEventDispatcher) {
        navigationEventDispatcher.addInput(preview)
        parent.addHandler(handler)
    }

    fun cancelPreviewIfSelectionChanged(selectionGeneration: Long) {
        val gesture = origin ?: return
        if (gesture.selectionGeneration == selectionGeneration) return

        // Keep the origin until completion so cancellation cannot become a completion-only Back.
        cancelPreview()
    }

    private fun cancelPreview() {
        if (!previewActive) return

        previewActive = false
        preview.backCancelled()
    }

    var isBackEnabled: Boolean
        get() = handler.isBackEnabled
        set(value) {
            handler.isBackEnabled = value
        }

    fun dispose() {
        handler.remove()
        navigationEventDispatcher.dispose()
    }
}

private data class BackOrigin(
    val tab: TopLevelKey,
    val selectionGeneration: Long,
)

private class EntryBackOwner(
    val parent: NavigationEventDispatcherOwner,
    val panes: PaneSceneStrategy,
)

/** Entry selection and other screen handlers retain precedence over navigation completion. */
@Composable
@Suppress("FunctionNaming") // Compose functions use PascalCase.
internal fun EntryBackDispatch(entry: NavEntry<NavKey>, content: @Composable () -> Unit) {
    val dispatch = checkNotNull(LocalEntryBackDispatch.current)

    val owner =
        remember(
            dispatch,
        ) { EntryDispatcherOwner(NavigationEventDispatcher(dispatch.parent.navigationEventDispatcher)) }
    DisposableEffect(owner) { onDispose { owner.navigationEventDispatcher.dispose() } }
    val active = dispatch.panes.isCurrentEntry(entry.contentKey)
    SideEffect { owner.navigationEventDispatcher.isEnabled = active }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { content() }
}

private class EntryDispatcherOwner(
    override val navigationEventDispatcher: NavigationEventDispatcher,
) : NavigationEventDispatcherOwner
