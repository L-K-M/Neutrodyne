// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.import_resume_discard
import ch.lkmc.neutrodyne.core.ui.resources.import_resume_failed
import ch.lkmc.neutrodyne.core.ui.resources.import_resume_preview
import ch.lkmc.neutrodyne.core.ui.resources.import_resume_progress
import ch.lkmc.neutrodyne.core.ui.resources.import_resume_view
import ch.lkmc.neutrodyne.core.ui.resources.review
import ch.lkmc.neutrodyne.core.ui.resources.try_again
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Root slot, composed only after startup. Returns whether it used one banner position. */
@Composable
public fun importResumeBanner(
    available: Boolean,
    onOpen: (Long) -> Unit,
): Boolean {
    if (!available) return false
    return importResumeRoute(metroViewModel(), onOpen)
}

@Composable
internal fun importResumeRoute(
    viewModel: ImportResumeViewModel,
    onOpen: ((Long) -> Unit)? = null,
): Boolean {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var opening by remember(state.session?.id) { mutableStateOf(false) }
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    ShowUserMessages(messages, viewModel::acknowledgeMessage)
    return importResumeContent(
        state.copy(busy = state.busy || opening),
        onOpen = { id ->
            if (!opening) {
                opening = true
                if (onOpen != null) onOpen(id) else navigator.push(ImportKey(id.toString()))
            }
        },
        onDiscard = { scope.launch { viewModel.discard() } },
        onRetry = viewModel::retryObservation,
    )
}

@Composable
internal fun importResumeContent(
    state: ImportResumeUiState,
    onOpen: (Long) -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
): Boolean {
    if (state.failed) {
        NdBanner(
            message = stringResource(Res.string.import_resume_failed),
            primary = NdDialogAction(stringResource(Res.string.try_again), onClick = onRetry),
        )
        return true
    }
    val session = state.session ?: return false
    val preview = session.state == ImportState.PREVIEW
    val total =
        session.counts
            .filterKeys {
                it != ImportItemStatus.PREVIEW && it != ImportItemStatus.ALREADY_SUBSCRIBED &&
                    it != ImportItemStatus.DUPLICATE_IN_FILE && it != ImportItemStatus.INVALID_URL &&
                    it != ImportItemStatus.YOUTUBE_UNSUPPORTED_YET
            }.values
            .sum()
    val done = total - (session.counts[ImportItemStatus.QUEUED] ?: 0)
    NdBanner(
        message =
            if (preview) {
                pluralStringResource(
                    Res.plurals.import_resume_preview,
                    session.counts.values.sum(),
                    session.counts.values.sum(),
                )
            } else {
                stringResource(Res.string.import_resume_progress, done, total)
            },
        primary =
            if (state.busy) {
                null
            } else {
                NdDialogAction(
                    stringResource(if (preview) Res.string.review else Res.string.import_resume_view),
                    onClick = { onOpen(session.id) },
                )
            },
        secondary =
            if (preview && !state.busy) {
                NdDialogAction(stringResource(Res.string.import_resume_discard), onClick = onDiscard)
            } else {
                null
            },
    )
    return true
}
