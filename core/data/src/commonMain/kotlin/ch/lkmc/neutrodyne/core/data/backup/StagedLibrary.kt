// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1

/**
 * 05's `StagedLibrary`: a decoded library held for `RestoreMerger` — over an `ArchiveReader`
 * for restores or a staging directory for the first sync link (both written under the
 * archive's entry names by `BackupCodec`). `library.json` materialises; `episodes.jsonl`
 * streams so a large backup never lives in memory twice.
 */
internal interface StagedLibrary {
    /** The archive's `manifest.createdAt`, used as the `addedAt` of restored rows. */
    val createdAtMs: Long

    /** `library.json`: podcasts, groups with members, optional credentials. */
    suspend fun library(): LibraryV1

    /** `episodes.jsonl`, streamed one line at a time; undecodable lines are counted, not thrown. */
    suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit)

    /** `queue.json`, or null when the archive carries none. */
    suspend fun queue(): QueueV1?

    /** `settings.json`, or null when the archive carries none. */
    suspend fun settings(): SettingsV1?
}
