// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.add_open
import ch.lkmc.neutrodyne.core.ui.resources.add_subscribed_toast
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * The `AddPodcastKey` route (08 Add podcast sheet) — the nav host renders it inside
 * `NdModalBottomSheet` through `NdSceneMetadata.bottomSheet()`.
 *
 * On a completed subscribe the sheet closes and the root snackbar offers "Open" → `PodcastKey`.
 * Popping disposes this entry mid-effect, so the close + snackbar run in `NonCancellable`; the
 * snackbar's result still arrives — the `SnackbarHostState` is root-scoped.
 *
 * The effect acknowledges `done` through `consumeDone` before acting on it (01): the consume
 * re-checks the live operation generation — a newer resolve may have started after `done` was
 * collected, and only the latest operation may close the sheet (UI review round 2) — and a
 * replayed effect then finds nothing to re-pop over the screen beneath.
 */
@Composable
internal fun AddPodcastRoute(
    key: AddPodcastKey,
    viewModel: AddPodcastViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current

    LaunchedEffect(state.done) {
        val done = viewModel.consumeDone() ?: return@LaunchedEffect
        withContext(NonCancellable) {
            navigator.pop()
            // Long, not the action-label default (Indefinite): the root host drains every
            // screen's messages through one mutex, so an ignored snackbar must release it
            // on its own (08's durations rule).
            val result =
                snackbar.showSnackbar(
                    message = getString(Res.string.add_subscribed_toast, done.title),
                    actionLabel = getString(Res.string.add_open),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) {
                navigator.pushDetail(PodcastKey(done.podcastId))
            }
        }
    }

    AddPodcastSheet(
        initialInput = key.input,
        state = state,
        onResolve = viewModel::resolve,
        onCredentials = viewModel::resolveWithCredentials,
        onCancelResolve = viewModel::cancelResolve,
        onInputChange = viewModel::onInputChanged,
        onCandidate = viewModel::resolve,
        onSubscribe = viewModel::subscribe,
        onOpenPodcast = { id ->
            navigator.pop()
            navigator.pushDetail(PodcastKey(id))
        },
        onDismiss = { navigator.pop() },
    )
}
