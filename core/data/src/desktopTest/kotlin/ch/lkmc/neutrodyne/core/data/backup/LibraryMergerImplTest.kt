// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.MergeValueOrigin
import ch.lkmc.neutrodyne.core.model.MergedRecord
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeDownloadLifecycle
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Path as NioPath

/**
 * The [LibraryMergerImpl] port over a real staging directory (05 RestoreMerger Inputs): the
 * four entries under their archive names plus the pull-time metadata, decoded through the
 * STAGING codec (the `fc` field clocks survive), merged through the same merger pipeline a
 * restore runs — never a second implementation (D92).
 */
@OptIn(ExperimentalPathApi::class)
class LibraryMergerImplTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val secrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val settingsRepository = FakeSettingsRepository()
    private val settingsSync = FakeSettingsSyncPort()
    private val root: NioPath = createTempDirectory("nd-library-merger")
    private val fileSystem = FileSystem.SYSTEM

    private val merger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, FakeRefreshScheduler(), clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = TestOrderKeys,
            clock = clock,
            downloads =
                Provider { FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) } },
        )

    private val impl =
        LibraryMergerImpl(
            merger = merger,
            episodes =
                RestoreEpisodeMerger(
                    db = db,
                    episodes = EpisodeMatcher(db),
                    writes = RestoreWrites(db),
                    orderKeys = TestOrderKeys,
                    clock = clock,
                ),
            settings = RestoreSettingsMerge(settingsRepository, db, settingsSync),
            fileSystem = fileSystem,
            io = Dispatchers.Default,
        )

    private val pullAt = 1_800_000_000_000L
    private val playedAt = 1_790_000_000_000L
    private val june = 1_780_000_000_000L
    private val syncId = "00000000-0000-4000-8000-00000000000f"
    private var stagingCounter = 0

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun mergeStagedAppliesTheStagingDirectoryUnderLinkMerge() =
        runTest {
            // A local episode the staged `g:e` line matches, with an older June mark.
            val localPodcast =
                db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://s/feed", syncId = syncId))
            val localEpisode =
                db.episodeDao().insertStub(episodeEntity(podcastId = localPodcast, identityKey = "g:e"))
            db.episodeStateDao().upsert(
                EpisodeStateEntity(episodeId = localEpisode, playedAt = june, updatedAt = june),
            )
            val dir = stagingDir()
            val applied = ArrayList<MergedRecord>()
            val unmatched = ArrayList<Pair<String, String>>()

            val outcome =
                impl.mergeStaged(
                    dir.toString(),
                    MergePolicy.LINK_MERGE,
                    onUnmatched = { podcastSyncId, identityKey -> unmatched += podcastSyncId to identityKey },
                    progress = { _, _ -> },
                    onApplied = { applied += it },
                )

            val summary = assertIs<Outcome.Success<MergeSummary>>(outcome).value
            assertEquals(1, summary.podcastsMatched)
            val row = assertNotNull(db.podcastDao().bySyncId(syncId))
            // The played, nothing-special unmatched lines went to onUnmatched, not to stubs.
            assertEquals(listOf(syncId to "g:plain"), unmatched)
            // The played winner reports with the record's own clock.
            val episode = applied.first { it.collection == "episode" }
            assertEquals(syncId + "g:e", episode.recordId)
            assertEquals(MergeValueOrigin.STAGED, episode.fields.getValue("played").origin)
            // The newer staged mark replaced the local June one.
            assertEquals(playedAt, db.episodeStateDao().byEpisode(localEpisode)!!.playedAt)
            assertEquals(playedAt, episode.fields.getValue("played").originalAtMs)
            // The synced setting reports as a durable effect; no DataStore value was written.
            val setting = applied.first { it.collection == "setting" }
            assertEquals(GroupsSettingKeys.SHOW_UNGROUPED_TAB.name, setting.recordId)
            assertTrue(settingsSync.applied.isEmpty())
            // The queue ref appended the record's entry with a fresh key.
            assertEquals(1, db.queueDao().entries().size)
        }

    /**
     * MS2 acceptance 3 through the real port: a local podcast stays and a newer remote
     * "unplayed" clears an older local played mark. This is the database behaviour the old
     * engine-merge shortcut owned; the first-link orchestration around it is pinned by
     * `:sync:impl`'s `FirstLinkMergerTest`.
     */
    @Test
    fun mergeKeepsLocalPodcastsAndANewerRemoteUnplayedWins() =
        runTest {
            val localPodcast =
                db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://s/feed", syncId = syncId))
            val localEpisode =
                db.episodeDao().insertStub(episodeEntity(podcastId = localPodcast, identityKey = "g:e"))
            db.episodeStateDao().upsert(
                EpisodeStateEntity(episodeId = localEpisode, playedAt = june, updatedAt = june),
            )
            val dir =
                writeStagingDir(
                    podcasts = listOf(podcastV1("https://s/feed", syncId)),
                    episodeLines =
                        listOf(
                            EpisodeLineV1(
                                p = "https://s/feed",
                                k = "g:e",
                                u = "https://s/e.mp3",
                                pl = null,
                                ts = playedAt,
                                fc = mapOf("played" to playedAt),
                            ),
                        ),
                    queue = null,
                )

            val outcome = impl.mergeStaged(dir.toString(), MergePolicy.LINK_MERGE, { _, _ -> }, { _, _ -> })

            assertIs<Outcome.Success<MergeSummary>>(outcome)
            assertNotNull(db.podcastDao().bySyncId(syncId))
            val state = db.episodeStateDao().byEpisode(localEpisode)
            assertNull(state?.playedAt)
            assertEquals(playedAt, state?.updatedAt)
        }

    /**
     * 10 Use the server's library through the real port: the server's Up-next refs replace
     * the local list in the server's order, a ref whose key differs from the local episode's
     * key is kept through the mapper's match-hint rewrite (the `g:alias` row), and local-only
     * podcasts and queue entries are pruned. The mapping itself is pinned by `:sync:impl`'s
     * `useServerMapsTheServerUpNextWithOrderKeysAndMatchHints`.
     */
    @Test
    fun useServerAppliesTheServerUpNextAndPrunesLocalOnlyEntries() =
        runTest {
            val shared =
                db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://s/feed", syncId = syncId))
            val localOnlySyncId = "00000000-0000-4000-8000-00000000000b"
            val localOnly =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "https://s/lone", syncId = localOnlySyncId),
                )
            db.episodeDao().insertStub(episodeEntity(podcastId = shared, identityKey = "g:srv1"))
            db.episodeDao().insertStub(episodeEntity(podcastId = shared, identityKey = "g:srv2"))
            db.episodeDao().insertStub(episodeEntity(podcastId = shared, identityKey = "g:local"))
            db.episodeDao().insertStub(
                episodeEntity(podcastId = shared, identityKey = "g:alias") {
                    copy(enclosureUrl = "https://cdn.example.com/alias.mp3")
                },
            )
            db.episodeDao().insertStub(episodeEntity(podcastId = localOnly, identityKey = "g:lone"))
            val dir =
                writeStagingDir(
                    podcasts = listOf(podcastV1("https://s/feed", syncId)),
                    episodeLines = emptyList(),
                    queue =
                        QueueV1(
                            upNext =
                                listOf(
                                    UpNextRefV1(p = "https://s/feed", k = "g:srv1", ok = "0.2"),
                                    UpNextRefV1(p = "https://s/feed", k = "g:srv2", ok = "0.4"),
                                    UpNextRefV1(p = "https://s/feed", k = "g:alias", ok = "0.6"),
                                ),
                        ),
                )

            val outcome = impl.mergeStaged(dir.toString(), MergePolicy.LINK_REPLACE, { _, _ -> }, { _, _ -> })

            assertIs<Outcome.Success<MergeSummary>>(outcome)
            assertEquals(
                listOf("g:srv1", "g:srv2", "g:alias"),
                db.queueDao().entries().mapNotNull { db.episodeDao().byId(it.episodeId)?.identityKey },
            )
            assertEquals(
                "0.6",
                db
                    .queueDao()
                    .entryByEpisode(
                        db
                            .episodeDao()
                            .all()
                            .first { it.identityKey == "g:alias" }
                            .id,
                    )?.orderKey,
            )
            assertNull(db.podcastDao().bySyncId(localOnlySyncId))
            assertNotNull(db.podcastDao().bySyncId(syncId))
        }

    @Test
    fun mergeStagedRefusesADirectoryWithoutMetadata() =
        runTest {
            val dir = root.resolve("bare").toString().toPath()
            fileSystem.createDirectories(dir)
            fileSystem.write(dir / BackupCodec.ENTRY_LIBRARY) { writeUtf8("""{"podcasts":[],"groups":[]}""") }

            val outcome = impl.mergeStaged(dir.toString(), MergePolicy.LINK_MERGE, { _, _ -> }, { _, _ -> })

            val failure = assertIs<Outcome.Failure<BackupError>>(outcome)
            assertEquals(BackupError.NotABackup, failure.error)
        }

    @Test
    fun mergeStagedRefusesRestorePolicies() =
        runTest {
            val outcome =
                impl.mergeStaged(stagingDir().toString(), MergePolicy.RESTORE_MERGE, { _, _ -> }, { _, _ -> })
            assertEquals(BackupError.Unsupported, assertIs<Outcome.Failure<BackupError>>(outcome).error)
        }

    /** Writes one full staging directory through the STAGING codec (10 Merge step 2). */
    private fun stagingDir(): Path =
        writeStagingDir(
            podcasts =
                listOf(
                    PodcastV1(
                        key = "https://s/feed",
                        feedUrl = "https://s/feed",
                        title = "Server title",
                        subscribedAt = pullAt,
                        syncId = syncId,
                    ),
                ),
            episodeLines =
                listOf(
                    EpisodeLineV1(
                        p = "https://s/feed",
                        k = "g:e",
                        u = "https://s/e.mp3",
                        pl = playedAt,
                        ts = playedAt,
                        fc = mapOf("played" to playedAt),
                    ),
                    EpisodeLineV1(
                        p = "https://s/feed",
                        k = "g:plain",
                        u = "https://s/p.mp3",
                        pl = 1L,
                        ts = 1L,
                        fc = mapOf("played" to 1L),
                    ),
                    EpisodeLineV1(p = "https://s/feed", k = "g:q", u = "https://s/q.mp3", ts = 0L),
                ),
            queue = QueueV1(upNext = listOf(UpNextRefV1("https://s/feed", "g:q"))),
            settings =
                listOf(
                    SettingValueV1(GroupsSettingKeys.SHOW_UNGROUPED_TAB.name, "Bool", JsonPrimitive(true)),
                ),
        )

    /** Writes a staging directory with the given entries through the STAGING codec. */
    private fun writeStagingDir(
        podcasts: List<PodcastV1>,
        episodeLines: List<EpisodeLineV1>,
        queue: QueueV1?,
        settings: List<SettingValueV1> = emptyList(),
    ): Path {
        val dir = root.resolve("staging-${stagingCounter++}").toString().toPath()
        fileSystem.createDirectories(dir)
        val codec = BackupCodec(BackupCodec.Mode.STAGING)
        fileSystem.write(dir / BackupCodec.ENTRY_LIBRARY) {
            writeUtf8(codec.encodeLibrary(LibraryV1(podcasts = podcasts, groups = emptyList())))
        }
        fileSystem.write(dir / BackupCodec.ENTRY_EPISODES) {
            for (line in episodeLines) codec.writeEpisodeLine(this, line)
        }
        if (queue != null) {
            fileSystem.write(dir / BackupCodec.ENTRY_QUEUE) { writeUtf8(codec.encodeQueue(queue)) }
        }
        fileSystem.write(dir / BackupCodec.ENTRY_SETTINGS) {
            writeUtf8(codec.encodeSettings(SettingsV1(values = settings)))
        }
        fileSystem.write(dir / STAGING_META_ENTRY) { writeUtf8("""{"createdAt":$pullAt}""") }
        return dir
    }

    private fun podcastV1(
        key: String,
        syncId: String,
    ): PodcastV1 =
        PodcastV1(
            key = key,
            feedUrl = key,
            title = "Server title",
            subscribedAt = pullAt,
            syncId = syncId,
        )
}
