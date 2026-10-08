// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState

/** `import_session` (02): one row per import; `finishedAt` starts the 7-day cleanup window. */
@Entity(tableName = "import_session")
data class ImportSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val finishedAt: Long? = null,
    /** Android `OpenableColumns.DISPLAY_NAME`; desktop file name. */
    val sourceName: String? = null,
    val sourceFormat: ImportFormat,
    val state: ImportState,
    @ColumnInfo(defaultValue = "0") val recoveredBySalvage: Boolean = false,
    /** Relative to the cache directory (`import/{id}.bin`). */
    val payloadPath: String? = null,
    val optionsJson: String? = null,
    val warningsJson: String? = null,
)

/** `import_item` (02): one row per previewed URL; `ordinal` preserves file order. */
@Entity(
    tableName = "import_item",
    primaryKeys = ["sessionId", "ordinal"],
    indices = [Index("sessionId", "status"), Index("podcastId")],
    foreignKeys = [
        ForeignKey(
            entity = ImportSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class ImportItemEntity(
    val sessionId: Long,
    val ordinal: Int,
    val title: String? = null,
    val originalUrl: String,
    val normalizedUrl: String? = null,
    val kind: ImportItemKind,
    val groupNamesJson: String,
    val selected: Boolean,
    val status: ImportItemStatus,
    val podcastId: Long? = null,
    val errorDetail: String? = null,
)
