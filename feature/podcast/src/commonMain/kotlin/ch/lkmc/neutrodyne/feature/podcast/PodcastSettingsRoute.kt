// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.podcast_removed
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.whenOutcome
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The `PodcastSettingsKey` route (08 Podcast settings): a gone podcast pops; `editFeedUrl` and
 * `setCredentials` failures surface as snackbars through `addPodcastErrorText`.
 */
@Composable
internal fun PodcastSettingsRoute(key: PodcastSettingsKey) {
    val viewModel =
        assistedMetroViewModel<PodcastSettingsViewModel, PodcastSettingsViewModel.Factory> {
            create(key.podcastId)
        }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.gone) {
        if (state.gone) {
            snackbar.showSnackbar(getString(Res.string.podcast_removed))
            navigator.pop()
        }
    }

    PodcastSettingsScreen(
        state = state,
        onCustomTitle = viewModel::setCustomTitle,
        onOrderChange = viewModel::setOrder,
        onEditFeedUrl = { input ->
            scope.launch {
                viewModel.editFeedUrl(input).whenOutcome(
                    onSuccess = {},
                    onFailure = { snackbar.showSnackbar(addPodcastErrorText(it).resolve()) },
                )
            }
        },
        onCredentials = { credentials ->
            scope.launch {
                viewModel.setCredentials(credentials).whenOutcome(
                    onSuccess = {},
                    onFailure = { snackbar.showSnackbar(addPodcastErrorText(it).resolve()) },
                )
            }
        },
    )
}
