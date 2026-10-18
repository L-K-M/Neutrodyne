// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.StoragePaths
import okio.Path
import okio.Path.Companion.toPath

// The Auto Backup lane's file names (05 Auto Backup / Snapshot production). Both writers —
// AutoSnapshotProducer and RestoreRunner's retirement — must agree on every one of these.

/** `files/backup/` — the app-private directory `Auto Backup`'s rules XML includes. */
internal fun backupDirOf(storagePaths: StoragePaths): Path = storagePaths.filesDir.toPath() / "backup"

/** `files/backup/auto-snapshot.zip` — the pending snapshot the Android transport uploads. */
internal const val AUTO_SNAPSHOT_FILE = "auto-snapshot.zip"

internal fun autoSnapshotPathOf(storagePaths: StoragePaths): Path = backupDirOf(storagePaths) / AUTO_SNAPSHOT_FILE

/** A retired snapshot's name (restore success, Discard, or the unrestorable-file rule). */
internal fun restoredSnapshotName(atMs: Long): String = "restored-$atMs.zip"

/** `restored-{epochMs}.zip` files only — every other name under `files/backup/` stays put. */
internal fun isRetiredSnapshotFile(name: String): Boolean = name.startsWith("restored-") && name.endsWith(".zip")

/** `cache/restore/` — the stager's lane for backup previews and restore runs. */
internal fun restoreStagingDirOf(storagePaths: StoragePaths): Path = storagePaths.cacheDir.toPath() / "restore"
