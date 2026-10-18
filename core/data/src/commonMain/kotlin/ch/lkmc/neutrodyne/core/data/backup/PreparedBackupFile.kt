// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import okio.Path

/**
 * The only `PreparedBackup` `BackupRepositoryImpl.savePreparedBackup` accepts (05
 * Writing): it privately owns [source], the unique cache leaf `prepareBackup` wrote,
 * so a descriptor made anywhere else — fake or foreign — is refused before any
 * destination I/O, and no caller can point the save at a file it did not prepare.
 */
internal class PreparedBackupFile(
    override val fileName: String,
    override val summary: BackupSummary,
    override val privacy: PrivacyCounts,
    val source: Path,
) : PreparedBackup
