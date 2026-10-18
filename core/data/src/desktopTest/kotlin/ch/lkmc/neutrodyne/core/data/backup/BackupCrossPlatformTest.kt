// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakePayloadSource
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.execSql
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.newNetworkClients
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN M3 acceptance 5/11, the JVM half: a MANUAL archive the real `BackupWriter` labels
 * `android`/`arm64-v8a` and one labelled `linux`/`linux-x64` both stage through `ZipGuard` +
 * `BackupCodec` and replay through the real `BackupStager` + `RestoreRunner` + mergers into a
 * Room target — Replace reconstructing the logical library, Merge following the merge rules
 * (played = OR, newer `posAt` wins the position, favourites = OR, matched rows keep their
 * local identity, backup `orderKey`s are adopted).
 *
 * What this does *not* prove (the gate's manual halves stay manual): a `BuildInfo` label is
 * not Android libcore or Keystore, and the Linux JVM is not Windows or macOS file I/O. The
 * Android↔desktop round trip on real devices, `bmgr`, drag/drop and the native UI journeys
 * are separate gates.
 */
class BackupCrossPlatformTest {
    private val clock = TestClock()
    private val root = createTempDirectory("nd-backup-xplat").toFile()
    private val fileSystem = FileSystem.SYSTEM
    private val storagePaths = storagePathsFor(root)
    private val codec = BackupCodec()
    private var nextArchive = 0

    // --- The writing device ------------------------------------------------------------------

    private val sourceDb = TestDb.inMemory(clock = clock)
    private val sourceSettings = FakeSettingsRepository()
    private val sourceSecrets = FakeSecretStore()

    @AfterTest
    fun tearDown() {
        sourceDb.close()
        root.deleteRecursively()
    }

    private fun newWriter(buildInfo: BuildInfo) =
        BackupWriter(
            sourceDb,
            sourceSettings,
            sourceSecrets,
            ZipArchiveWriter(),
            ZipGuard(),
            OpmlExporter(sourceDb, sourceSecrets, clock, Dispatchers.Default),
            buildInfo,
            clock,
            fileSystem,
            Dispatchers.IO,
        )

    private suspend fun writeArchive(buildInfo: BuildInfo): Path {
        val archive = root.absolutePath.toPath() / "archive-${nextArchive++}.zip"
        assertIs<Outcome.Success<*>>(newWriter(buildInfo).write(BackupKind.MANUAL, archive, includeCredentials = false))
        return archive
    }

    // --- The restoring target -----------------------------------------------------------------

    private class Target(
        val db: NeutrodyneDatabase,
        val settings: FakeSettingsRepository,
        val runner: RestoreRunner,
    )

    private fun newTarget(): Target {
        val db = TestDb.inMemory(clock = clock)
        val secrets = FakeSecretStore()
        val coordinator = FakeCredentialCommitCoordinator()
        val channels = FakeGroupChannelSync()
        val fetchScheduler = FakeRefreshScheduler()
        val settings = FakeSettingsRepository()
        val merger =
            RestoreMerger(
                db = db,
                podcasts = PodcastMatcher(db),
                writes = RestoreWrites(db),
                groupMerger = GroupMerger(db, clock, channels),
                unsubscribe = newUnsubscribe(db, fetchScheduler, clock, secrets, coordinator),
                secrets = secrets,
                coordinator = coordinator,
                channelSync = channels,
                orderKeys = ProtocolOrderKeys,
                clock = clock,
            )
        val stager = BackupStager(db, ZipGuard(), merger, fileSystem, Dispatchers.IO)
        val runner =
            RestoreRunner(
                db = db,
                stager = stager,
                merger = merger,
                episodes =
                    RestoreEpisodeMerger(
                        db = db,
                        episodes = EpisodeMatcher(db),
                        writes = RestoreWrites(db),
                        orderKeys = ProtocolOrderKeys,
                        clock = clock,
                    ),
                settingsMerge = RestoreSettingsMerge(settings),
                payloads =
                    PayloadStore(
                        storagePaths = storagePaths,
                        fileSystem = fileSystem,
                        payloads = FakePayloadSource(),
                        httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                        netErrors = testClassifier,
                        io = Dispatchers.Default,
                    ),
                storagePaths = storagePaths,
                fileSystem = fileSystem,
                workScheduler = FakeImportWorkScheduler(),
                refresh = FakeRefreshController(),
                channels = channels,
                snapshots = FakeSnapshotScheduler(),
                clock = clock,
                io = Dispatchers.IO,
            )
        return Target(db, settings, runner)
    }

    /** Stages [archive] into the payload lane and drives the real host, Restore algorithm and all. */
    private suspend fun restore(
        target: Target,
        archive: Path,
        mode: RestoreMode,
        categories: Set<RestoreCategory> =
            setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT, RestoreCategory.SETTINGS),
    ) {
        val payloadPath = "import/restore-$nextArchive.bin"
        val staged = storagePaths.cacheDir.toPath() / payloadPath
        fileSystem.createDirectories(checkNotNull(staged.parent))
        fileSystem.copy(archive, staged)
        val sessionId =
            target.db.seedImportSession(
                options = ImportOptions(restoreMode = mode, restoreCategories = categories),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = payloadPath,
            )
        assertIs<Outcome.Success<*>>(target.db.requestImportRestore(sessionId, clock.now()))
        val result = target.runner.run(sessionId)
        assertIs<RestoreRunResult.Completed>(result)
        assertEquals(RestoreProgress.Finished(sessionId, auto = false), target.runner.progress.first())
    }

    private fun manifest(archive: Path): BackupManifest =
        ZipGuard().open(archive, ArchiveCaps.BACKUP).use { reader ->
            reader.open(BackupCodec.ENTRY_MANIFEST).use { codec.decodeManifest(it) }
        }

    // --- Seeds + logical snapshots -------------------------------------------------------------

    private data class Seeded(
        val feedKeyA: String,
        val feedKeyB: String,
        val groupCommute: String,
        val groupNews: String,
    )

    /**
     * Two ordered groups (one coloured, one empty after the members), two podcasts with fixed
     * syncIds, a played+favourite episode with a position, a queued episode, a GROUP play
     * session and two portable settings.
     */
    private suspend fun seedSource(): Seeded {
        val keys = ProtocolOrderKeys.rewrite(3)
        val commute =
            sourceDb
                .groupDao()
                .insertGroupOrIgnore(
                    groupEntity(name = "Commute", orderKey = keys[0])
                        .copy(colorArgb = 0xFF336699.toInt()),
                )
        val news =
            sourceDb
                .groupDao()
                .insertGroupOrIgnore(groupEntity(name = "News", orderKey = keys[1]))
        sourceDb
            .groupDao()
            .insertGroupOrIgnore(groupEntity(name = "Empty shelf", orderKey = keys[2]))

        val a =
            sourceDb
                .podcastDao()
                .insertPodcast(
                    podcastEntity(
                        feedUrl = FEED_A,
                        feedKey = feedKey(FEED_A),
                        title = "Alpha",
                        subscribedAt = 1_000L,
                        syncId = "11111111-2222-4222-8222-333333333333",
                    ),
                )
        val b =
            sourceDb
                .podcastDao()
                .insertPodcast(
                    podcastEntity(
                        feedUrl = FEED_B,
                        feedKey = feedKey(FEED_B),
                        title = "Beta",
                        subscribedAt = 2_000L,
                        syncId = "aaaaaaaa-2222-4222-8222-aaaaaaaaaaaa",
                    ),
                )
        val memberKeys = ProtocolOrderKeys.rewrite(2)
        sourceDb.groupDao().insertMember(memberEntity(commute, a, memberKeys[0], addedAt = 10L))
        sourceDb.groupDao().insertMember(memberEntity(commute, b, memberKeys[1], addedAt = 20L))
        sourceDb.groupDao().insertMember(memberEntity(news, b, ProtocolOrderKeys.rewrite(1)[0], addedAt = 30L))

        // Lines carry state only when they can materialize: 05's stub rule needs an enclosure
        // (`u`) or a YouTube id, so every seeded episode gets an enclosureUrl.
        val e1 =
            sourceDb
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = a, identityKey = "ep-a-1", sortDate = 100L) {
                            copy(enclosureUrl = "https://cdn.example.com/ep-a-1.mp3")
                        },
                    ),
                ).single()
        val e2 =
            sourceDb
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = a, identityKey = "ep-a-2", sortDate = 90L) {
                            // The line's `dur` is this feed column; restore writes it back as
                            // the position row's durationMs.
                            copy(enclosureUrl = "https://cdn.example.com/ep-a-2.mp3", durationMs = 61_000L)
                        },
                    ),
                ).single()
        val e3 =
            sourceDb
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = b, identityKey = "ep-b-1", sortDate = 80L) {
                            copy(enclosureUrl = "https://cdn.example.com/ep-b-1.mp3")
                        },
                    ),
                ).single()

        sourceDb
            .episodeStateDao()
            .upsert(
                episodeStateEntity(e1, playedAt = 111L, updatedAt = 112L) {
                    copy(playCount = 2, lastPlayedAt = 111L, isFavorite = true, measuredDurationMs = 61_000L)
                },
            )
        // e1's position predates its played mark (25 < 111): restore resets it to 0 — the
        // mark-played rule. e2 is unplayed: its position replays verbatim.
        sourceDb.positionDao().upsert(EpisodePositionEntity(e1, 12_000L, 61_000L, PositionSource.STREAM, 25L))
        sourceDb.positionDao().upsert(EpisodePositionEntity(e2, 42_000L, 61_000L, PositionSource.DOWNLOAD, 30L))
        sourceDb.episodeStateDao().ensureAll(listOf(e2), 40L)
        sourceDb.episodeStateDao().upsert(episodeStateEntity(e3, updatedAt = 50L))
        sourceDb.queueDao().insert(queueEntryEntity(e3, ProtocolOrderKeys.rewrite(1)[0]))

        // `play_session` is the id=0 singleton ensured at onCreate: a session is an UPDATE.
        sourceDb.playSessionDao().ensureNow(1L)
        sourceDb.useWriterConnection {
            it.execSql(
                "UPDATE play_session SET currentEpisodeId = $e1, contextType = 'GROUP'," +
                    " contextId = $commute, contextOrder = 'OLDEST_FIRST', contextFilterFlags = 3," +
                    " contextMediaFilter = 'AUDIO', contextMinSortDate = 99," +
                    " contextAnchorEpisodeId = $e2, contextAnchorSortDate = 88 WHERE id = 0",
            )
        }

        sourceSettings.set(AppearanceSettingKeys.LIBRARY_TITLES, true)
        sourceSettings.set(AppearanceSettingKeys.LIBRARY_SORT, LibrarySort.MOST_UNPLAYED)
        sourceSettings.set(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
        return Seeded(feedKey(FEED_A), feedKey(FEED_B), "Commute", "News")
    }

    /** The logical library AC5 compares — stable identities only, no row ids or generation. */
    private suspend fun assertReplayed(
        target: Target,
        seeded: Seeded,
    ) {
        val db = target.db

        // Groups: names in order, "Empty shelf" preserved, colour carried by the grouped row.
        val groups = db.groupDao().groupsInOrder()
        assertEquals(listOf("Commute", "News", "Empty shelf"), groups.map { it.name })
        assertEquals(0xFF336699.toInt(), groups[0].colorArgb)

        // Memberships: Commute = {A, B} in order, News = {B}, Empty shelf = {}.
        val commute = groups.single { it.name == "Commute" }
        val news = groups.single { it.name == "News" }
        val emptyShelf = groups.single { it.name == "Empty shelf" }
        assertEquals(
            listOf(seeded.feedKeyA, seeded.feedKeyB),
            db.groupDao().membersOf(commute.id).map { checkNotNull(db.podcastDao().byId(it.podcastId)).feedKey },
        )
        assertEquals(
            listOf(seeded.feedKeyB),
            db.groupDao().membersOf(news.id).map { checkNotNull(db.podcastDao().byId(it.podcastId)).feedKey },
        )
        assertTrue(db.groupDao().membersOf(emptyShelf.id).isEmpty())

        // Podcasts: identity keys and original subscribedAt/syncId; fresh rows, stable keys.
        val a = assertNotNull(db.podcastDao().byFeedKey(seeded.feedKeyA))
        val b = assertNotNull(db.podcastDao().byFeedKey(seeded.feedKeyB))
        assertEquals("Alpha", a.title)
        assertEquals(1_000L, a.subscribedAt)
        assertEquals("11111111-2222-4222-8222-333333333333", a.syncId)
        assertEquals(2_000L, b.subscribedAt)

        // Played/favourite with original timestamps; position value, source and stamp.
        val e1 = assertNotNull(db.episodeDao().byIdentityKey(a.id, "ep-a-1"))
        val state1 = assertNotNull(db.episodeStateDao().byEpisode(e1.id))
        assertEquals(111L, state1.playedAt)
        assertEquals(112L, state1.updatedAt)
        assertEquals(2, state1.playCount)
        assertTrue(state1.isFavorite)
        assertEquals(61_000L, state1.measuredDurationMs)
        // The played mark's post rule zeroed e1's older position; e2's unplayed position
        // replays verbatim and derives the in-progress proxy `startedAt` from posAt.
        val reset = assertNotNull(db.positionDao().byEpisode(e1.id))
        assertEquals(0L, reset.positionMs, "a position older than the played mark resets")
        assertEquals(111L, reset.updatedAt)
        val e2 = assertNotNull(db.episodeDao().byIdentityKey(a.id, "ep-a-2"))
        val pos = assertNotNull(db.positionDao().byEpisode(e2.id))
        assertEquals(42_000L, pos.positionMs)
        assertEquals(61_000L, pos.durationMs)
        assertEquals(PositionSource.DOWNLOAD, pos.positionSource)
        assertEquals(30L, pos.updatedAt)
        assertEquals(30L, assertNotNull(db.episodeStateDao().byEpisode(e2.id)).startedAt)

        // Up next in order + the session's current episode and group context.
        val e3 = assertNotNull(db.episodeDao().byIdentityKey(b.id, "ep-b-1"))
        assertEquals(listOf(e3.id), db.queueDao().entries().map { it.episodeId })
        val session = assertNotNull(db.playSessionDao().get())
        assertEquals(e1.id, session.currentEpisodeId)
        assertEquals(ContextType.GROUP, session.contextType)
        assertEquals(commute.id, session.contextId)
        assertEquals(FeedOrder.OLDEST_FIRST, session.contextOrder)
        assertEquals(3, session.contextFilterFlags)
        assertEquals(MediaFilter.AUDIO, session.contextMediaFilter)
        assertEquals(99L, session.contextMinSortDate)

        // Portable settings replay.
        assertEquals(true, target.settings.get(AppearanceSettingKeys.LIBRARY_TITLES))
        assertEquals(LibrarySort.MOST_UNPLAYED, target.settings.get(AppearanceSettingKeys.LIBRARY_SORT))
        assertEquals(true, target.settings.get(GroupsSettingKeys.SHOW_UNGROUPED_TAB))
    }

    // --- AC11: each platform's label restores identically --------------------------------------

    @Test
    fun androidLabeledBackupReplaysIdentically() =
        runTest {
            val seeded = seedSource()
            val archive =
                writeArchive(
                    testBuildInfo()
                        .copy(platform = BuildInfo.Platform.ANDROID, apkAbi = "arm64-v8a", desktop = null),
                )
            manifest(archive).let {
                assertEquals("android", it.app.platform)
                assertEquals("arm64-v8a", it.app.abi)
            }

            val target = newTarget()
            try {
                restore(target, archive, RestoreMode.REPLACE)
                assertReplayed(target, seeded)
            } finally {
                target.db.close()
            }
        }

    @Test
    fun desktopLabeledBackupReplaysIdentically() =
        runTest {
            val seeded = seedSource()
            val archive =
                writeArchive(
                    testBuildInfo()
                        .copy(
                            platform = BuildInfo.Platform.DESKTOP,
                            apkAbi = null,
                            desktop =
                                BuildInfo.Desktop(
                                    os = DesktopOs.LINUX,
                                    arch = DesktopArch.X64,
                                    installKind = InstallKind.TAR_GZ,
                                    runtime = "bundled",
                                ),
                        ),
                )
            manifest(archive).let {
                assertEquals("linux", it.app.platform)
                assertEquals("linux-x64", it.app.abi)
            }

            val target = newTarget()
            try {
                restore(target, archive, RestoreMode.REPLACE)
                assertReplayed(target, seeded)
            } finally {
                target.db.close()
            }
        }

    // --- AC5: a Merge restore follows the merge rules -------------------------------------------

    @Test
    fun mergeRestoreFollowsTheMergeRules() =
        runTest {
            val seeded = seedSource()
            val archive = writeArchive(testBuildInfo())

            val target = newTarget()
            try {
                val db = target.db
                // A subscribed the device already has, with a different local identity.
                val localA =
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                feedUrl = FEED_A,
                                feedKey = feedKey(FEED_A),
                                title = "My Alpha",
                                subscribedAt = 42L,
                                syncId = "cccccccc-2222-4222-8222-cccccccccccc",
                            ),
                        )
                // A local-only podcast and group survive Merge (only Replace unsubscribes).
                val localOnly =
                    db.podcastDao().insertPodcast(podcastEntity(feedUrl = FEED_LOCAL, feedKey = feedKey(FEED_LOCAL)))
                val localGroup =
                    db.groupDao().insertGroupOrIgnore(
                        groupEntity(name = "Local", orderKey = ProtocolOrderKeys.rewrite(1)[0]),
                    )
                db.groupDao().insertMember(memberEntity(localGroup, localA, ProtocolOrderKeys.rewrite(1)[0]))

                // Played = OR: backup ep-a-1 is played; local marks ep-local-1 played — both kept.
                val localPlayed =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = localA, identityKey = "ep-a-1", sortDate = 100L)),
                        ).single()
                db
                    .episodeStateDao()
                    .upsert(
                        episodeStateEntity(localPlayed, playedAt = 7L, updatedAt = 8L) {
                            copy(playCount = 5, lastPlayedAt = 70L)
                        },
                    )
                // Newer position wins: local stamp (200) beats the backup's posAt (25).
                db.positionDao().upsert(EpisodePositionEntity(localPlayed, 5_000L, null, PositionSource.DOWNLOAD, 200L))
                // Favourite = OR on the unmatched line: backup ep-a-2 is not favourite, local is.
                val localE2 =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = localA, identityKey = "ep-a-2", sortDate = 90L)),
                        ).single()
                db
                    .episodeStateDao()
                    .upsert(episodeStateEntity(localE2, updatedAt = 9L) { copy(isFavorite = true) })
                // Played = OR in the other direction: backup's played mark lands on an
                // episode the target has too (same identity key) — merged state, not replace.
                // (ep-a-1 covers that; here the backup line adds nothing the target lacks.)

                restore(target, archive, RestoreMode.MERGE)

                // Matched row keeps its local identity.
                val a = assertNotNull(db.podcastDao().byFeedKey(seeded.feedKeyA))
                assertEquals(localA, a.id)
                assertEquals("cccccccc-2222-4222-8222-cccccccccccc", a.syncId)
                assertEquals(42L, a.subscribedAt)
                assertEquals("My Alpha", a.title)
                assertNotNull(db.podcastDao().byFeedKey(feedKey(FEED_LOCAL)))
                assertNotNull(db.groupDao().groupsInOrder().single { it.name == "Local" })

                // Backup groups appended after the local tail; membership union (Local + Commute).
                val groupNames = db.groupDao().groupsInOrder().map { it.name }
                assertEquals(listOf("Local", "Commute", "News", "Empty shelf"), groupNames)
                val commute = db.groupDao().groupsInOrder().single { it.name == "Commute" }
                assertEquals(
                    setOf(seeded.feedKeyA, seeded.feedKeyB),
                    db
                        .groupDao()
                        .membersOf(commute.id)
                        .map { checkNotNull(db.podcastDao().byId(it.podcastId)).feedKey }
                        .toSet(),
                )
                val local = db.groupDao().groupsInOrder().single { it.name == "Local" }
                assertEquals(
                    setOf(seeded.feedKeyA),
                    db
                        .groupDao()
                        .membersOf(local.id)
                        .map { checkNotNull(db.podcastDao().byId(it.podcastId)).feedKey }
                        .toSet(),
                )

                // Merge rules on episodes (05 Merge): playedAt/lastPlayedAt/playCount = MAX —
                // the backup's 111 beats the local 7 — favourite = OR both ways, and a strictly
                // older posAt (25) never displaces the local position (200).
                val e1 = assertNotNull(db.episodeDao().byIdentityKey(a.id, "ep-a-1"))
                val e1State = assertNotNull(db.episodeStateDao().byEpisode(e1.id))
                assertEquals(111L, e1State.playedAt, "playedAt merges as MAX")
                assertEquals(5, e1State.playCount, "playCount keeps the larger local count")
                assertEquals(111L, e1State.lastPlayedAt, "lastPlayedAt keeps the larger backup stamp")
                assertTrue(e1State.isFavorite, "favourite OR lands the backup's mark")
                assertEquals(112L, e1State.updatedAt, "a backup-won field stamps the line's ts")
                val pos = assertNotNull(db.positionDao().byEpisode(e1.id))
                assertEquals(5_000L, pos.positionMs, "newer local position wins")
                assertEquals(PositionSource.DOWNLOAD, pos.positionSource)
                assertEquals(200L, pos.updatedAt)
                val e2 = db.episodeDao().byIdentityKey(a.id, "ep-a-2")
                assertTrue(
                    assertNotNull(db.episodeStateDao().byEpisode(assertNotNull(e2).id)).isFavorite,
                    "favourite OR keeps the local mark",
                )
            } finally {
                target.db.close()
            }
        }

    private fun feedKey(feedUrl: String): String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))

    private companion object {
        const val FEED_A = "https://alpha.example.com/feed.xml"
        const val FEED_B = "https://beta.example.com/feed.xml"
        const val FEED_LOCAL = "https://local.example.com/feed.xml"
    }
}
