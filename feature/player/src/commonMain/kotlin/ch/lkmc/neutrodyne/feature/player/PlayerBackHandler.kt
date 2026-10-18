// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/** Registered after NavDisplay, so an expanded player consumes Back before the underlying screen. */
@Composable
internal fun PlayerBackHandler(state: PlayerSheetState) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReducedMotion.current
    val handler =
        remember(state, scope, reducedMotion) {
            object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
                override fun onBackStarted(event: NavigationEvent) {
                    state.previewBack(0f)
                }

                override fun onBackProgressed(event: NavigationEvent) {
                    state.previewBack(event.progress)
                }

                override fun onBackCancelled() {
                    scope.launch { state.cancelBack(reducedMotion) }
                }

                override fun onBackCompleted() {
                    scope.launch { state.commitBack(reducedMotion) }
                }
            }
        }
    SideEffect { handler.isBackEnabled = state.isExpanded || state.drag.targetValue == PlayerSheetValue.Expanded }
    DisposableEffect(dispatcher, handler) {
        dispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
}
