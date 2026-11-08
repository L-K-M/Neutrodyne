// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.LibraryMerger
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.MergedRecord
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/** The staging directory's metadata file (10 Merge step 2): `{"createdAt": <ms>}`. */
internal const val STAGING_META_ENTRY = "link-meta.json"

/**
 * A [StagedLibrary] over a first-link staging directory (05 RestoreMerger Inputs): the
 * archive's entry names written by 10's `FirstLinkMerger` with [BackupCodec.Mode.STAGING]
 * — `library.json`, `episodes.jsonl` (streamed), `queue.json`, `settings.json`, no
 * manifest. The pull time travels in [STAGING_META_ENTRY]; without it the directory is
 * not a staging directory and the merge refuses it.
 */
internal class StagedDirectoryLibrary(
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher,
    private val codec: BackupCodec,
    private val dir: Path,
    override val createdAtMs: Long,
) : StagedLibrary {
    override suspend fun library(): LibraryV1 =
        withContext(io) {
            fileSystem.source(dir / BackupCodec.ENTRY_LIBRARY).use(codec::decodeLibrary)
        }

    override suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit) {
        withContext(io) {
            val lines = codec.episodeLines(fileSystem.source(dir / BackupCodec.ENTRY_EPISODES))
            try {
                while (lines.hasNext()) {
                    block(lines.next())
                }
            } finally {
                lines.close()
            }
        }
    }

    override suspend fun queue(): QueueV1? = readOptional(BackupCodec.ENTRY_QUEUE, codec::decodeQueue)

    override suspend fun settings(): SettingsV1? = readOptional(BackupCodec.ENTRY_SETTINGS, codec::decodeSettings)

    private suspend fun <T> readOptional(
        name: String,
        decode: (okio.Source) -> T,
    ): T? =
        withContext(io) {
            val path = dir / name
            if (!fileSystem.exists(path)) return@withContext null
            fileSystem.source(path).use(decode)
        }
}

/**
 * The [LibraryMerger] port's implementation (05): wraps 10's staging directory as a
 * [StagedLibrary] and runs the same phase pipeline a restore runs — [RestoreMerger], then
 * [RestoreEpisodeMerger], then the settings rule — under the given `LINK_*` policy with
 * all three categories. Never a second merge implementation: the restore's mergers are
 * the one routine (D92).
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
internal class LibraryMergerImpl(
    private val merger: RestoreMerger,
    private val episodes: RestoreEpisodeMerger,
    private val settings: RestoreSettingsMerge,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : LibraryMerger {
    private val codec = BackupCodec(BackupCodec.Mode.STAGING)

    override suspend fun mergeStaged(
        stagingDir: String,
        policy: MergePolicy,
        onUnmatched: suspend (podcastSyncId: String, identityKey: String) -> Unit,
        progress: (done: Int, total: Int) -> Unit,
        onApplied: (suspend (MergedRecord) -> Unit)?,
    ): Outcome<MergeSummary, BackupError> {
        if (policy != MergePolicy.LINK_MERGE && policy != MergePolicy.LINK_REPLACE) {
            return Outcome.Failure(BackupError.Unsupported)
        }
        val dir = stagingDir.toPath()
        val createdAt =
            readCreatedAt(dir)
                ?: return Outcome.Failure(BackupError.NotABackup)
        val staged = StagedDirectoryLibrary(fileSystem, io, codec, dir, createdAt)
        return try {
            val settled =
                merger.mergeLibrary(
                    staged,
                    policy,
                    ALL_CATEGORIES,
                    progress = progress,
                    onApplied = onApplied,
                )
            val episodeResult =
                episodes.mergeEpisodes(
                    staged,
                    settled,
                    policy,
                    ALL_CATEGORIES,
                    progress = progress,
                    onUnmatched = onUnmatched,
                    onApplied = onApplied,
                )
            val settingCounts = settings.reportLinked(staged.settings(), createdAt, onApplied)
            Outcome.Success(
                MergeSummary(
                    podcastsAdded = settled.summary.podcastsAdded,
                    podcastsMatched = settled.summary.podcastsMatched,
                    groupsAdded = settled.summary.groupsAdded,
                    groupsMatched = settled.summary.groupsMatched,
                    episodeLines = episodeResult.summary.episodeLines,
                    stubs = episodeResult.summary.stubs,
                    unmatched = episodeResult.summary.unmatched,
                    skippedLines = episodeResult.summary.skippedLines + settingCounts.skipped,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(BackupError.Storage)
        }
    }

    /** The pull time from [STAGING_META_ENTRY]; null when the directory is not one of ours. */
    private fun readCreatedAt(dir: Path): Long? =
        runCatching {
            val text =
                fileSystem.source(dir / STAGING_META_ENTRY).buffer().use { it.readUtf8() }
            STAGING_META_REGEX
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        }.getOrNull()

    private companion object {
        val ALL_CATEGORIES = setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT, RestoreCategory.SETTINGS)
        val STAGING_META_REGEX = Regex(""""createdAt"\s*:\s*(\d+)""")
    }
}
