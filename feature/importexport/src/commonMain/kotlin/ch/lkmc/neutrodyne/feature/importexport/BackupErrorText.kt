// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_corrupt
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_expired
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_io
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_large
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_newer
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_not_backup
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_running
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_unsupported
import ch.lkmc.neutrodyne.core.ui.resources.backup_import_empty
import ch.lkmc.neutrodyne.core.ui.resources.backup_import_hostile
import ch.lkmc.neutrodyne.core.ui.resources.backup_import_large
import ch.lkmc.neutrodyne.core.ui.resources.backup_import_single
import ch.lkmc.neutrodyne.core.ui.resources.backup_import_unreadable
import ch.lkmc.neutrodyne.core.ui.resources.backup_import_unsupported
import ch.lkmc.neutrodyne.core.ui.resources.export_err_destination
import ch.lkmc.neutrodyne.core.ui.resources.export_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.import_err_gone
import ch.lkmc.neutrodyne.core.ui.resources.import_err_network

internal fun backupErrorText(error: BackupError): UiText =
    when (error) {
        BackupError.NotABackup -> UiText.Res(Res.string.backup_err_not_backup)
        is BackupError.NewerFormat -> UiText.Res(Res.string.backup_err_newer)
        is BackupError.Corrupt -> UiText.Res(Res.string.backup_err_corrupt, listOf(error.entry))
        BackupError.TooLarge -> UiText.Res(Res.string.backup_err_large)
        BackupError.Io -> UiText.Res(Res.string.backup_err_io)
        BackupError.Storage -> UiText.Res(Res.string.export_err_storage)
        BackupError.RestoreRunning -> UiText.Res(Res.string.backup_err_running)
        BackupError.DestinationUnavailable -> UiText.Res(Res.string.export_err_destination)
        BackupError.PreparedExpired -> UiText.Res(Res.string.backup_err_expired)
        BackupError.Unsupported -> UiText.Res(Res.string.backup_err_unsupported)
    }

internal fun acquisitionErrorText(error: ImportError): UiText =
    when (error) {
        ImportError.TooLarge -> {
            UiText.Res(Res.string.backup_import_large)
        }

        ImportError.Unreadable -> {
            UiText.Res(Res.string.backup_import_unreadable)
        }

        is ImportError.Hostile -> {
            UiText.Res(Res.string.backup_import_hostile)
        }

        ImportError.Empty -> {
            UiText.Res(Res.string.backup_import_empty)
        }

        ImportError.Storage -> {
            UiText.Res(Res.string.export_err_storage)
        }

        ImportError.SessionGone -> {
            UiText.Res(Res.string.import_err_gone)
        }

        is ImportError.Network -> {
            UiText.Res(Res.string.import_err_network)
        }

        is ImportError.Unsupported -> {
            UiText.Res(
                if (error.kind == UnsupportedSource.SINGLE_FEED) {
                    Res.string.backup_import_single
                } else {
                    Res.string.backup_import_unsupported
                },
            )
        }
    }
