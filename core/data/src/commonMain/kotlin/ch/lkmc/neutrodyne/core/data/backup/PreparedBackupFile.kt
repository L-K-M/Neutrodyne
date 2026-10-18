// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import okio.Path

/**
 * The only `PreparedBackup` `BackupRepositoryImpl.savePreparedBackup` accepts (05
 * Writing). The issuing repository registers its reference under the backup mutex;
 * this type alone does not prove ownership because its JVM constructor is public.
 * [source] is the unique cache leaf `prepareBackup` wrote.
 */
internal class PreparedBackupFile(
    override val fileName: String,
    override val summary: BackupSummary,
    override val privacy: PrivacyCounts,
    val source: Path,
) : PreparedBackup
