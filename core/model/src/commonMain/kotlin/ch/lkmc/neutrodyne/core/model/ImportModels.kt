// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/*
 * 05 OPML import — the preview/commit/report contract types ImportRepository hands to the
 * feature layer. Session and item state enums live in ImportEnums.kt.
 */

/** The file or URL a session is created from: `content://` (Android), `file://` (desktop), `https://`. */
data class ImportSource(
    val uri: String,
    val displayName: String?,
)

/** "Import OPML into this group" (05). */
data class CreateImportOptions(
    val targetGroupId: Long? = null,
)

/**
 * Session-wide options persisted in `import_session.optionsJson` (05 Preview contract);
 * `:core:data` serialises them through a @Serializable mirror. The `restore*` fields apply
 * to `NEUTRODYNE_BACKUP` sessions only (05 Full backup and restore).
 */
data class ImportOptions(
    /** Master switch "Import folders as groups". */
    val importGroups: Boolean = true,
    /** nameKeys excluded from import; wrapper and gPodder sections pre-fill it. */
    val excludedGroupKeys: Set<String> = emptySet(),
    /** nameKey → new name (validated with the GroupNames rules). */
    val renamedGroups: Map<String, String> = emptyMap(),
    /** "Put everything into group…" — an existing group id or a name to create. */
    val targetGroupId: Long? = null,
    val targetGroupName: String? = null,
    /** D66 toggle: mark existing episodes of imported podcasts played (except the newest). */
    val treatExistingAsPlayed: Boolean = false,
    /** Writes `podcast_settings.notifyNewEpisodes = true` for imported podcasts. */
    val notifyNewEpisodes: Boolean = false,
    /** Backup sessions only: the restore mode the user picked in the preview. */
    val restoreMode: RestoreMode? = null,
    val restoreCategories: Set<RestoreCategory> = emptySet(),
    /** Backup sessions: episodes of `dl = true` lines offered for re-download (M6, ≤ 2,000). */
    val redownloadIds: List<Long> = emptyList(),
    /** Session adopted from `files/backup/auto-snapshot.zip` (Android first-launch restore). */
    val fromAndroidBackup: Boolean = false,
    /** Backup sessions: `restore()` was called; the host runs it (05 Restore algorithm). */
    val restoreRequestedAt: Long? = null,
    /** Backup sessions on a linked device, Replace only (05 Restore while linked). */
    val linkedReach: LinkedRestoreReach? = null,
    /** Desktop: runs of the import-backup lane that ended in retry while online. */
    val laneAttempts: Int = 0,
    /** Groups found at parse time, in proposal order (05 Group mapping). */
    val sourceGroups: List<SourceGroup> = emptyList(),
    /** Worker bookkeeping: when commit ran and the fetch-attempt marker (05 6–7). */
    val committedAt: Long? = null,
    val attemptMarkerAt: Long? = null,
)

/** A group found in the source file; [excludedReason] pre-fills [ImportOptions.excludedGroupKeys]. */
data class SourceGroup(
    val nameKey: String,
    val name: String,
    val colorArgb: Int?,
    val iconKey: String?,
    val excludedReason: String?,
)

/** The session row as the preview/report screen needs it (05 Preview contract). */
data class ImportSessionView(
    val id: Long,
    val sourceName: String?,
    val format: ImportFormat,
    val state: ImportState,
    val recoveredBySalvage: Boolean,
    val warnings: List<ImportWarning>,
    val options: ImportOptions,
    val counts: Map<ImportItemStatus, Int>,
    val selectedCount: Int,
    /** Selected YouTube items — the header's "and N YouTube channels". */
    val youtubeCount: Int,
    val createdAt: Long,
)

/** One importable entry in preview order; rows show monogram, title and host — never the URL. */
data class ImportItemView(
    val ordinal: Int,
    val title: String,
    val host: String,
    val kind: ImportItemKind,
    val status: ImportItemStatus,
    val selected: Boolean,
    /** False rows skip the selection toggle (05's status table). */
    val selectable: Boolean,
    val groupNames: List<String>,
    val isPrivate: Boolean,
    val errorDetail: String?,
    val podcastId: Long?,
)

/** One proposed group card: switch, validated rename, or the excluded reason (05). */
data class GroupProposal(
    val nameKey: String,
    val name: String,
    val memberCount: Int,
    val existingGroupId: Long?,
    val included: Boolean,
    val excludedReason: String?,
)

/** Header warnings; [code] is one of SALVAGED, IGNORED_OUTLINES, TRUNCATED_NAMES, LINKED_LISTS. */
data class ImportWarning(
    val code: String,
    val count: Int = 0,
    val detail: String? = null,
)

/** Report filter → item statuses; the mapping table of 05 Session and item states. */
enum class ImportItemFilter {
    ALL,
    NEW_ONLY,
    NOT_IMPORTED,
    NEEDS_ATTENTION,
}
