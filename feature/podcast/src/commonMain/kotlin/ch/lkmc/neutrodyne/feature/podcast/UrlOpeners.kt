// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 08's link rule: a URL nothing can open shows "No app can open this link" with "Copy link" as
 * the snackbar action — tapping it puts the URL on the clipboard (the same pattern Settings ›
 * About's `openLink` uses).
 */
@Composable
internal fun rememberNoHandlerFallback(): (String) -> Unit {
    val snackbar = LocalSnackbarHost.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val message = stringResource(Res.string.no_handler)
    val copy = stringResource(Res.string.action_copy_link)
    return remember(snackbar, clipboard, message, copy) {
        { url ->
            scope.launch {
                val result =
                    snackbar.showSnackbar(
                        message = message,
                        actionLabel = copy,
                        withDismissAction = true,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    clipboard.setText(AnnotatedString(url))
                }
            }
        }
    }
}

/**
 * Opens a URL through the shell's `ExternalUrlOpener` and answers [OpenResult.NO_HANDLER] with
 * the no-handler snackbar. For `EpisodeAction.WatchOnYouTube` the route prefers
 * `dispatchEpisodeRoute` + [rememberNoHandlerFallback] — the enum already carries the failure.
 */
@Composable
internal fun rememberUrlOpener(): (String) -> Unit {
    val urls = LocalPlatformActions.current.urls
    val noHandler = rememberNoHandlerFallback()
    return remember(urls, noHandler) {
        { url ->
            if (urls.open(url) == OpenResult.NO_HANDLER) noHandler(url)
        }
    }
}
