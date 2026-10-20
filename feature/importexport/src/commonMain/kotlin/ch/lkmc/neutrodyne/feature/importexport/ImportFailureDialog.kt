// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dismiss
import ch.lkmc.neutrodyne.core.ui.resources.import_err_empty
import ch.lkmc.neutrodyne.core.ui.resources.import_err_gone
import ch.lkmc.neutrodyne.core.ui.resources.import_err_hostile
import ch.lkmc.neutrodyne.core.ui.resources.import_err_network
import ch.lkmc.neutrodyne.core.ui.resources.import_err_single_feed
import ch.lkmc.neutrodyne.core.ui.resources.import_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.import_err_title
import ch.lkmc.neutrodyne.core.ui.resources.import_err_too_large
import ch.lkmc.neutrodyne.core.ui.resources.import_err_unreadable
import ch.lkmc.neutrodyne.core.ui.resources.import_err_unreadable_desktop
import ch.lkmc.neutrodyne.core.ui.resources.import_err_unsupported
import org.jetbrains.compose.resources.stringResource

/** Acquisition errors are shared by file-picker and desktop open requests (05). */
@Composable
public fun ImportFailureDialog(
    error: ImportError,
    onDismiss: () -> Unit,
) {
    val message =
        when (error) {
            ImportError.Unreadable -> {
                if (LocalPlatformKind.current == PlatformKind.DESKTOP) {
                    Res.string.import_err_unreadable_desktop
                } else {
                    Res.string.import_err_unreadable
                }
            }

            ImportError.TooLarge -> {
                Res.string.import_err_too_large
            }

            ImportError.Storage -> {
                Res.string.import_err_storage
            }

            is ImportError.Network -> {
                Res.string.import_err_network
            }

            is ImportError.Hostile -> {
                Res.string.import_err_hostile
            }

            ImportError.Empty -> {
                Res.string.import_err_empty
            }

            is ImportError.Unsupported -> {
                if (error.kind ==
                    UnsupportedSource.SINGLE_FEED
                ) {
                    Res.string.import_err_single_feed
                } else {
                    Res.string.import_err_unsupported
                }
            }

            ImportError.SessionGone -> {
                Res.string.import_err_gone
            }
        }
    NdDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.import_err_title),
        text = stringResource(message),
        confirm = NdDialogAction(stringResource(Res.string.dismiss), onClick = onDismiss),
    )
}
