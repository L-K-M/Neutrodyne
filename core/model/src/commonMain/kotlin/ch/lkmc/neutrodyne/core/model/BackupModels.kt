// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlinx.serialization.json.JsonElement

/*
 * 05 Full backup and restore — the preview/request/status types of BackupRepository plus
 * the merge types of LibraryMerger.
 */

enum class RestoreMode {
    /** Union with the local library, local marks never lost (the default). */
    MERGE,

    /** Exactly the backup's library; local-only podcasts/groups are removed. */
    REPLACE,
}

/** Subscriptions and groups are always restored; the checkboxes pick the rest (05). */
enum class RestoreCategory {
    HISTORY,
    UP_NEXT,
    SETTINGS,
}

/** How far a Replace reaches while the device is linked (05 Restore while linked). */
enum class LinkedRestoreReach {
    THIS_DEVICE,
    ALL_DEVICES,
}

data class RestoreRequest(
    val mode: RestoreMode,
    val categories: Set<RestoreCategory>,
    /** Ignored unless REPLACE while linked (05). */
    val linkedReach: LinkedRestoreReach = LinkedRestoreReach.THIS_DEVICE,
)

/**
 * What `inspect` reads out of an archive for the restore preview (05 Validation and
 * preview): counts for the preview sentence and Replace's confirmation.
 */
data class BackupPreview(
    val createdAt: Long,
    val appVersionName: String,
    val automatic: Boolean,
    val podcasts: Int,
    val groups: Int,
    val played: Int,
    val inProgress: Int,
    val upNext: Int,
    val settings: Int,
    val includesCredentials: Boolean,
    val localPodcastsNotInBackup: Int,
    val localGroupsNotInBackup: Int,
    val warnings: List<ImportWarning>,
    /** Non-null while this device is linked to a sync server (05 Restore while linked). */
    val linkedServerHost: String?,
    /** `AppInfoV1.platform`, for "made on Android" in the preview. */
    val writtenOn: String?,
)

data class BackupSummary(
    val bytes: Long,
    val podcasts: Int,
    val episodeLines: Int,
)

/** Auto Backup state for Settings › Backup (05 Auto Backup). */
data class SnapshotStatus(
    /** False on the desktop — it has no automatic backup. */
    val supported: Boolean,
    val enabled: Boolean,
    val lastWrittenAt: Long?,
    val bytes: Long?,
    /** 05's drop level, 0–3: full, slim old history, drop old history, active lines only. */
    val level: Int,
    val lastError: String?,
    /** A snapshot from another installation waits (05 First-launch restore). */
    val foreignPending: Boolean,
)

/**
 * One `writeSnapshotNow` attempt's status pair (05 Testing with bmgr): the producer reads
 * both halves inside its write mutex, so [after] differing from [before] in the
 * publication fields (`lastWrittenAt`, `bytes`, `level`) proves this pass published:
 * `SnapshotNowReceiver`'s `ok` line can neither invent a write nor be raced by a second
 * one.
 */
data class SnapshotWrite(
    val before: SnapshotStatus,
    val after: SnapshotStatus,
)

/** The R1.9 private-links/passwords warning counts for a backup (05 Writing). */
data class PrivacyCounts(
    val privateLinks: Int,
    val passwords: Int,
)

/**
 * A finished manual backup held in the private cache until the user consents (05
 * Writing). `BackupRepository.prepareBackup` writes the archive once and returns this
 * descriptor; [summary] and [privacy] are measured from that same write, so the warning
 * shown to the user always matches the bytes `savePreparedBackup` later copies out.
 * Opaque on purpose: only `:core:data` can build one, so a caller cannot fabricate a
 * cache path or a receipt.
 */
interface PreparedBackup {
    /** Display/document name, `neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip` (05 Writing). */
    val fileName: String

    /** Byte/row counts measured while the archive bytes were written. */
    val summary: BackupSummary

    /** R1.9 warning counts measured while the archive bytes were written. */
    val privacy: PrivacyCounts
}

/**
 * The policies `RestoreMerger` merges a staged library with (05 RestoreMerger):
 * `RESTORE_*` are the Merge and Replace columns of the rules table and are implemented in
 * M3; `LINK_*` is the First-link column, representable here but unsupported until MS2.
 */
enum class MergePolicy {
    RESTORE_MERGE,
    RESTORE_REPLACE,

    /** MS2 (10's FirstLinkMerger); not implemented in M3. */
    LINK_MERGE,

    /** MS2 ("Use the server's library on this device"); not implemented in M3. */
    LINK_REPLACE,
}

/** Counts only — 10's durable effects travel through the `onApplied` callback. */
data class MergeSummary(
    val podcastsAdded: Int,
    val podcastsMatched: Int,
    val groupsAdded: Int,
    val groupsMatched: Int,
    val episodeLines: Int,
    val stubs: Int,
    val unmatched: Int,
    val skippedLines: Int,
)

/** One merged record handed to `onApplied` inside its projection transaction (MS2). */
data class MergedRecord(
    val collection: String,
    val recordId: String,
    val fields: Map<String, MergedField>,
)

/** A winning field value with the clock of the input it came from (05 First-link metadata). */
data class MergedField(
    val value: JsonElement,
    val originalAtMs: Long,
    val origin: MergeValueOrigin,
)

enum class MergeValueOrigin {
    LOCAL,
    STAGED,
}
