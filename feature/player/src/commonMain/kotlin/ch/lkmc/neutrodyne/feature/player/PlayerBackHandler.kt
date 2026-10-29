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

internal enum class PlayerBackAvailability { ACTIVE, INACTIVE }

/** Registered after NavDisplay, below its modal priority but ahead of ordinary content. */
@Composable
internal fun PlayerBackHandler(
    state: PlayerSheetState,
    availability: PlayerBackAvailability = PlayerBackAvailability.ACTIVE,
) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    val scope = rememberCoroutineScope()
    val motion = if (LocalReducedMotion.current) SheetMotion.IMMEDIATE else SheetMotion.ANIMATED
    val handler =
        remember(state, scope, motion) {
            object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
                override fun onBackStarted(event: NavigationEvent) {
                    state.previewBack(0f)
                }

                override fun onBackProgressed(event: NavigationEvent) {
                    state.previewBack(event.progress)
                }

                override fun onBackCancelled() {
                    scope.launch { state.cancelBack(motion) }
                }

                override fun onBackCompleted() {
                    scope.launch { state.commitBack(motion) }
                }
            }
        }
    // Observe state here; SideEffect reads cannot invalidate this composition.
    val enabled = availability == PlayerBackAvailability.ACTIVE && state.canCollapse
    SideEffect { handler.isBackEnabled = enabled }
    DisposableEffect(dispatcher, handler) {
        dispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
}
