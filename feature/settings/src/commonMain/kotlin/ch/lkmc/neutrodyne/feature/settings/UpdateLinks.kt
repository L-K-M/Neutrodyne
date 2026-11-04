// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.about_link_copy
import ch.lkmc.neutrodyne.feature.settings.resources.about_link_no_handler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** All update/help links share the existing About no-handler fallback. */
@Composable
internal fun updateLinkOpener(): (String) -> Unit {
    val urls = LocalPlatformActions.current.urls
    val host = LocalSnackbarHost.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val message = stringResource(Res.string.about_link_no_handler)
    val copy = stringResource(Res.string.about_link_copy)
    return { url ->
        if (urls.open(url) == OpenResult.NO_HANDLER) {
            scope.launch {
                val result = host.showSnackbar(message, copy, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    clipboard.setText(AnnotatedString(url))
                }
            }
        }
    }
}
