// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

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
 */
@Composable
internal fun AddPodcastRoute(key: AddPodcastKey) {
    val viewModel = metroViewModel<AddPodcastViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current

    LaunchedEffect(state.done) {
        val done = state.done ?: return@LaunchedEffect
        withContext(NonCancellable) {
            navigator.pop()
            val result =
                snackbar.showSnackbar(
                    message = getString(Res.string.add_subscribed_toast, done.title),
                    actionLabel = getString(Res.string.add_open),
                    withDismissAction = true,
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
        onInputChanged = viewModel::onInputChanged,
        onCandidate = viewModel::resolve,
        onSubscribe = viewModel::subscribe,
        onOpenPodcast = { id ->
            navigator.pop()
            navigator.pushDetail(PodcastKey(id))
        },
        onDismiss = { navigator.pop() },
    )
}
