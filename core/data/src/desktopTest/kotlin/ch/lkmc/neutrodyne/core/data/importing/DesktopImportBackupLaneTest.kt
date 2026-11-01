// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.CommitCancellingDriver
import ch.lkmc.neutrodyne.core.data.FakePayloadSource
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.StubSourceAdapter
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.adapterParsed
import ch.lkmc.neutrodyne.core.data.backup.BackupStager
import ch.lkmc.neutrodyne.core.data.backup.BackupWriter
import ch.lkmc.neutrodyne.core.data.backup.EpisodeMatcher
import ch.lkmc.neutrodyne.core.data.backup.PodcastMatcher
import ch.lkmc.neutrodyne.core.data.backup.RestoreEpisodeMerger
import ch.lkmc.neutrodyne.core.data.backup.RestoreMerger
import ch.lkmc.neutrodyne.core.data.backup.RestoreRunner
import ch.lkmc.neutrodyne.core.data.backup.RestoreSettingsMerge
import ch.lkmc.neutrodyne.core.data.backup.RestoreWrites
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importItem
import ch.lkmc.neutrodyne.core.data.newNetworkClients
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.notifications.NewEpisodeNotifier
import ch.lkmc.neutrodyne.core.data.parsedFeed
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeDownloadLifecycle
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeImportReportPoster
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The `import-backup` lane (05 §7's desktop column, 11's lane table) over the real
 * `RestoreRunner`, `ImportFetchRunner` and `ImportSessionCleaner` against a real Room
 * database. Covers the run order (pending restores oldest first, then committed imports,
 * then the 24 h cleanup), the offline split (local restore/cleanup still run; fetches wait
 * for the monitor), online-only `laneAttempts`, scheduler-driven cancellation that leaves
 * durable work for replay, and a mid-restore cancel that the next run replays from the
 * durable intent — exactly what a process quit leaves behind.
 */
class DesktopImportBackupLaneTest {
    private val driver = CommitCancellingDriver()
    private val clock = TestClock()
    private val db = TestDb.inMemory(driver = driver, clock = clock, queryContext = Dispatchers.Default)
    private val sourceDb = TestDb.inMemory(clock = clock)
    private val settings = FakeSettingsRepository()
    private val sourceSettings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val sourceSecrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val fetchScheduler = FakeRefreshScheduler()
    private val refresh = FakeRefreshController()
    private val snapshots = FakeSnapshotScheduler()
    private val reports = FakeImportReportPoster()
    private val poster = FakeNewEpisodePoster()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val pokes = CopyOnWriteArrayList<String>()
    private val work = LaneImportWorkScheduler(db, Provider { JobLanePoker { pokes += it } })

    private val root = createTempDirectory("nd-import-backup-lane").toFile()
    private val storagePaths = storagePathsFor(root)
    private var nextArchive = 0

    /** Records the payload paths the stager inspects — the restore order, written on I/O threads. */
    private val inspected = CopyOnWriteArrayList<String>()
    private val fileSystem =
        object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun metadataOrNull(path: Path): okio.FileMetadata? {
                if (path.name.startsWith("restore-")) inspected += path.name
                return super.metadataOrNull(path)
            }
        }
    private val payloads =
        PayloadStore(
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            payloads = FakePayloadSource(),
            httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
            netErrors = testClassifier,
            io = Dispatchers.Default,
        )
    private val merger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, fetchScheduler, clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = TestOrderKeys,
            clock = clock,
            downloads =
                Provider {
                    FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) }
                },
        )
    private val episodeMerger =
        RestoreEpisodeMerger(
            db = db,
            episodes = EpisodeMatcher(db),
            writes = RestoreWrites(db),
            orderKeys = TestOrderKeys,
            clock = clock,
        )
    private val stager = BackupStager(db, ZipGuard(), merger, fileSystem, Dispatchers.IO)
    private val restores =
        RestoreRunner(
            db = db,
            stager = stager,
            merger = merger,
            episodes = episodeMerger,
            settingsMerge = RestoreSettingsMerge(settings),
            payloads = payloads,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            workScheduler = work,
            refresh = refresh,
            channels = channels,
            snapshots = snapshots,
            clock = clock,
            io = Dispatchers.IO,
        )
    private val cleaner = ImportSessionCleaner(db, payloads, clock, Dispatchers.IO)
    private val adapter = StubSourceAdapter()
    private val lane = laneFor(adapter)

    @AfterTest
    fun tearDown() {
        db.close()
        sourceDb.close()
        root.deleteRecursively()
    }

    // --- Ordering ---------------------------------------------------------------------------------

    @Test
    fun pendingRestoresRunOldestIntentFirstThenCommittedImports() =
        runTest {
            seedSourcePodcast(FEED_A)
            // The committed import is *older* than both restores — it still waits for them.
            val importId = committedSession(createdAt = 50)
            // Created second but requested first: `restoreRequestedAt` owns the order (05's
            // "oldest session whose restoreRequestedAt is set"), not `createdAt`.
            val first = restoreSession(writeArchive(), createdAt = 200, requestedAt = 100)
            val second = restoreSession(writeArchive(), createdAt = 100, requestedAt = 200)
            queueItem(importId, ordinal = 0, url = FEED_B, podcastId = pendingPodcast(FEED_B))
            adapter.results[FEED_A] = adapterParsed(parsedFeed())
            adapter.results[FEED_B] = adapterParsed(parsedFeed())

            lane.run(now())

            assertEquals(
                listOf("restore-0.bin", "restore-1.bin"),
                inspected,
                "the restore pass ran the oldest durable intent first",
            )
            // Both restores committed and fed their restored podcasts into the same run's
            // import pass — possible only when the restore pass finished before it. The pass
            // itself is `activeSessions()`' order: oldest createdAt first.
            assertEquals(
                listOf(importId, second, first),
                reports.posts.map { it.sessionId },
                "the import pass ran after the restores, oldest session first",
            )
            assertEquals(ImportState.DONE, session(importId).state)
        }

    // --- Offline split ------------------------------------------------------------------------------

    @Test
    fun aFailedRestorePostsItsReportAndDoesNotBlockImports() =
        runTest {
            val archive = writeArchive()
            fileSystem.write(archive) { writeUtf8("not a zip") }
            val failed = restoreSession(archive, createdAt = clock.now())
            val healthy = committedSession()
            queueItem(healthy, ordinal = 0, url = FEED_B, podcastId = pendingPodcast(FEED_B))
            adapter.results[FEED_B] = adapterParsed(parsedFeed())

            lane.run(now())

            assertEquals(ImportState.CANCELLED, session(failed).state)
            assertEquals(listOf(failed), reports.restoreFailures.map { it.sessionId })
            assertEquals(ImportState.DONE, session(healthy).state)
        }

    @Test
    fun offlineRunsRestoresAndCleanupButNeverFetches() =
        runTest {
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            seedSourcePodcast(FEED_A)
            val restoreId = restoreSession(writeArchive())
            val importId = committedSession()
            queueItem(importId, ordinal = 0, url = FEED_B, podcastId = pendingPodcast(FEED_B))
            adapter.results[FEED_A] = adapterParsed(parsedFeed())
            adapter.results[FEED_B] = adapterParsed(parsedFeed())
            val expiredId = expiredSession()

            lane.run(now())

            assertNotNull(
                db.podcastDao().byFeedKey(feedKey(FEED_A)),
                "the restore is local work — it runs while offline",
            )
            assertEquals(ImportState.COMMITTED, session(restoreId).state)
            assertEquals(ImportState.COMMITTED, session(importId).state, "offline: never fetched")
            assertTrue(adapter.calls.isEmpty())
            assertNull(db.importDao().sessionById(expiredId), "cleanup is local work too")
            assertEquals(clock.now(), settings.get(DesktopSettingKeys.LAST_IMPORT_CLEANUP_AT))

            network.setStatus(FakeNetworkMonitor.ONLINE)
            lane.run(now())

            assertEquals(ImportState.DONE, session(importId).state)
            assertTrue(adapter.calls.isNotEmpty())
        }

    // --- laneAttempts (05 §7) -------------------------------------------------------------------------

    @Test
    fun laneAttemptsOnlyCountRetriesThatEndedWhileOnline() =
        runTest {
            val podcast = pendingPodcast(FEED_A)
            val sessionId = committedSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcast)

            // A retry that ends while the monitor still shows online burns an attempt.
            adapter.results[FEED_A] =
                AdapterResult.Failed(FeedErrorKind.OFFLINE, http = null, retryAfterMs = null, transient = true)
            lane.run(now())
            assertEquals(1, laneAttemptsOf(sessionId), "an online-ended retry counts")

            // A fully offline pass returns before any fetch — the counter is never touched.
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            lane.run(now())
            assertEquals(1, laneAttemptsOf(sessionId), "an offline pass burns nothing")

            // A disconnect inside the run: the outcome is OFFLINE and the monitor agrees by
            // the time the lane scores it — the retry stays free.
            var disconnected = false
            val flip =
                StubSourceAdapter(
                    onFetch = { _, _ ->
                        if (disconnected) return@StubSourceAdapter null
                        disconnected = true
                        network.setStatus(FakeNetworkMonitor.OFFLINE)
                        AdapterResult.Failed(FeedErrorKind.OFFLINE, http = null, retryAfterMs = null, transient = true)
                    },
                )
            network.setStatus(FakeNetworkMonitor.ONLINE)
            laneFor(flip).run(now())
            assertEquals(1, laneAttemptsOf(sessionId), "a retry that ended offline is free")

            // Back online, the next pass resumes the same session and finishes it.
            network.setStatus(FakeNetworkMonitor.ONLINE)
            flip.results[FEED_A] = adapterParsed(parsedFeed())
            laneFor(flip).run(now())
            assertEquals(ImportState.DONE, session(sessionId).state)
        }

    @Test
    fun anExhaustedLaneFinishesTheSessionWithoutFetching() =
        runTest {
            val podcast = pendingPodcast(FEED_A)
            // 05 §7's cap (`ImportFetchRunner`'s private `MAX_LANE_ATTEMPTS` = 20).
            val sessionId = committedSession(laneAttempts = 20)
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcast)

            lane.run(now())

            assertTrue(adapter.calls.isEmpty(), "the capped lane never fetches")
            assertEquals(ImportState.DONE, session(sessionId).state)
            assertEquals(
                ImportItemStatus.FETCH_FAILED,
                db
                    .importDao()
                    .itemsOf(sessionId)
                    .single { it.ordinal == 0 }
                    .status,
            )
            assertEquals(sessionId, reports.posts.single().sessionId)
        }

    @Test
    fun aUserRetryStartsANewFetchCampaignAfterTheAttemptCap() =
        runTest {
            val podcast = pendingPodcast(FEED_A)
            val sessionId = committedSession(laneAttempts = 20)
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcast)
            lane.run(now())
            val podcasts = FakePodcastRepository()
            val fixUps = ImportFixUps(db, podcasts, UnsubscribeUseCase(podcasts, poster), work, clock)

            fixUps.retry(sessionId, setOf(0))
            adapter.results[FEED_A] = adapterParsed(parsedFeed())
            lane.run(now())

            assertTrue(adapter.calls.isNotEmpty(), "a user retry must fetch after automatic attempts are exhausted")
            assertEquals(
                ImportItemStatus.SUBSCRIBED,
                db
                    .importDao()
                    .itemsOf(sessionId)
                    .single()
                    .status,
            )
            assertEquals(0, laneAttemptsOf(sessionId))
        }

    @Test
    fun aCorruptSessionDoesNotBlockLaterImportsOrCleanup() =
        runTest {
            val corrupt = committedSession(createdAt = MARKER - 1)
            db.importDao().setOptionsJson(corrupt, "{broken")
            queueItem(corrupt, ordinal = 0, url = FEED_A, podcastId = pendingPodcast(FEED_A))
            val healthy = committedSession()
            queueItem(healthy, ordinal = 0, url = FEED_B, podcastId = pendingPodcast(FEED_B))
            adapter.results[FEED_B] = adapterParsed(parsedFeed())
            val expired = expiredSession()

            lane.run(now())

            assertEquals(ImportState.DONE, session(healthy).state)
            assertNull(db.importDao().sessionById(expired))
            assertEquals("{broken", session(corrupt).optionsJson, "unknown metadata must never be reset to defaults")
        }

    // --- Cancellation and replay -----------------------------------------------------------------

    @Test
    fun schedulerCancelDropsTheInFlightFetchAndTheRowStaysReplayable() =
        runTest {
            val podcast = pendingPodcast(FEED_A)
            val sessionId = committedSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcast)

            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val gated =
                StubSourceAdapter(
                    results = mutableMapOf(FEED_A to adapterParsed(parsedFeed())),
                    onFetch = { feed, _ ->
                        if (feed.id == podcast) {
                            started.complete(Unit)
                            gate.await()
                        }
                        null
                    },
                )
            val job = launch(start = CoroutineStart.LAZY) { laneFor(gated).run(now()) }
            job.start()
            started.await()

            work.cancel(sessionId)
            // `withTimeout` on the test clock measures virtual time; the lane's Room work runs
            // on real threads, so the bound has to be real too.
            withContext(Dispatchers.Default) { withTimeout(20_000) { job.join() } }

            // The run stopped at the fetch boundary; the row keeps its live state, so the
            // next run (or the repository's CANCELLED commit, whichever lands) owns it.
            assertEquals(DesktopImportBackupLane.NAME, pokes.last())
            assertEquals(ImportState.FETCHING, session(sessionId).state)

            gate.complete(Unit)
            laneFor(gated).run(now())
            assertEquals(ImportState.DONE, session(sessionId).state)
        }

    @Test
    fun aQuitDuringARestoreLeavesTheDurableIntentForTheNextRun() =
        runTest {
            // A quit (or lane cancel) mid-merge aborts the run but the PREVIEW row keeps its
            // `restoreRequestedAt` — the next process start's first lane run replays it.
            seedSourcePodcast(FEED_A)
            val sessionId = restoreSession(writeArchive())

            val job = launch(start = CoroutineStart.LAZY) { lane.run(now()) }
            driver.arm("INSERT OR ABORT INTO `podcast`") { job.cancel() }
            job.start()
            job.join()

            // The hook fired at the merge transaction's commit — like a quit mid-restore. The
            // row stays PREVIEW with its intent; whether part of the merge already committed
            // does not matter, the replay is idempotent.
            assertEquals(1, driver.cancellations, "the commit hook fired")
            assertTrue(job.isCancelled)
            assertEquals(ImportState.PREVIEW, session(sessionId).state)
            assertNotNull(
                ImportOptionsJson.decode(checkNotNull(session(sessionId).optionsJson)).restoreRequestedAt,
            )

            // The next run — a new process' first tick — replays the durable intent; the
            // restored session is COMMITTED and its queued fetch runs the same pass.
            adapter.results[FEED_A] = adapterParsed(parsedFeed())
            lane.run(now())

            assertEquals(ImportState.DONE, session(sessionId).state)
            assertNotNull(db.podcastDao().byFeedKey(feedKey(FEED_A)))
        }

    // --- The 24 h cleanup ----------------------------------------------------------------------------

    @Test
    fun cleanupRunsOncePer24hAndStampsOnlyASuccessfulPass() =
        runTest {
            val expiredId = expiredSession()
            lane.run(now())
            assertNull(db.importDao().sessionById(expiredId))
            assertEquals(clock.now(), settings.get(DesktopSettingKeys.LAST_IMPORT_CLEANUP_AT))

            // Inside the window a newly expired session waits for the next pass.
            val younger = expiredSession()
            clock.nowMs += 60_000
            lane.run(now())
            assertNotNull(db.importDao().sessionById(younger), "the second run skipped cleanup")

            // Past the window the pass sweeps again and re-stamps.
            clock.nowMs += DesktopImportBackupLane.CLEANUP_INTERVAL_MS
            lane.run(now())
            assertNull(db.importDao().sessionById(younger))
            assertEquals(clock.now(), settings.get(DesktopSettingKeys.LAST_IMPORT_CLEANUP_AT))
        }

    @Test
    fun aFailedCleanupPassRetriesOnTheNextRun() =
        runTest {
            // The stamp write fails — the pass ran but nothing was recorded, so the next run
            // cleans again rather than trusting a stamp that never landed.
            val expiredId = expiredSession()
            settings.failNextSet = SettingsError.WriteFailed
            lane.run(now())

            assertEquals(0L, settings.get(DesktopSettingKeys.LAST_IMPORT_CLEANUP_AT))
            assertNull(db.importDao().sessionById(expiredId), "the sweep itself ran")

            val younger = expiredSession()
            clock.nowMs += 60_000
            lane.run(now())
            assertNull(db.importDao().sessionById(younger), "an unstamped pass re-runs cleanup")
        }

    // --- Fixtures ------------------------------------------------------------------------------------

    private fun laneFor(stub: StubSourceAdapter): DesktopImportBackupLane {
        val engine =
            newRefresher(
                db,
                mapOf(SourceType.RSS to stub),
                clock,
                settings,
                notifier = NewEpisodeNotifier(db, EffectiveSettingsResolverImpl(db, settings), poster),
            )
        return DesktopImportBackupLane(
            db = db,
            restores = restores,
            fetches = ImportFetchRunner(db, engine, snapshots, reports, clock),
            cleaner = cleaner,
            work = work,
            settings = settings,
            network = network,
            reports = reports,
        )
    }

    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now())

    private fun feedKey(feedUrl: String): String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))

    private suspend fun seedSourcePodcast(feedUrl: String): Long =
        sourceDb.podcastDao().insertPodcast(podcastEntity(feedUrl = feedUrl, feedKey = feedKey(feedUrl)))

    private suspend fun pendingPodcast(feedUrl: String): Long =
        seedPodcast(
            db,
            feedUrl = feedUrl,
            status = PodcastStatus.PENDING_FIRST_FETCH,
            initialFetch = true,
            subscribedAt = MARKER - 1,
        )

    private suspend fun writeArchive(): Path {
        val archive = root.absolutePath.toPath() / "archive-$nextArchive.zip"
        val writer =
            BackupWriter(
                sourceDb,
                sourceSettings,
                sourceSecrets,
                ZipArchiveWriter(),
                ZipGuard(),
                OpmlExporter(sourceDb, sourceSecrets, clock, Dispatchers.Default),
                testBuildInfo(),
                clock,
                fileSystem,
                Dispatchers.IO,
            )
        assertIs<Outcome.Success<*>>(writer.write(BackupKind.MANUAL, archive, includeCredentials = false))
        return archive
    }

    /** Adopts the archive into the import cache; returns its `payloadPath`. */
    private fun adopt(archive: Path): String {
        val name = "import/restore-${nextArchive++}.bin"
        val target = storagePaths.cacheDir.toPath() / name
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.copy(archive, target)
        return name
    }

    private suspend fun restoreSession(
        archive: Path,
        createdAt: Long = 0,
        requestedAt: Long = clock.now(),
    ): Long {
        val sessionId =
            db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = RestoreMode.MERGE,
                        restoreCategories = setOf(RestoreCategory.HISTORY),
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                createdAt = createdAt,
                payloadPath = adopt(archive),
            )
        assertIs<Outcome.Success<*>>(db.requestImportRestore(sessionId, requestedAt))
        return sessionId
    }

    private suspend fun committedSession(
        createdAt: Long = MARKER,
        laneAttempts: Int = 0,
    ): Long =
        db.seedImportSession(
            options =
                ImportOptions(
                    committedAt = MARKER,
                    attemptMarkerAt = MARKER,
                    laneAttempts = laneAttempts,
                ),
            format = ImportFormat.OPML,
            state = ImportState.COMMITTED,
            createdAt = createdAt,
        )

    private suspend fun expiredSession(): Long =
        db.seedImportSession(
            state = ImportState.DONE,
            createdAt = clock.now() - SESSION_TTL_MS - 2_000,
            finishedAt = clock.now() - SESSION_TTL_MS - 1_000,
        )

    private suspend fun queueItem(
        sessionId: Long,
        ordinal: Int,
        url: String,
        podcastId: Long?,
    ) {
        db
            .importDao()
            .insertItems(
                listOf(
                    importItem(
                        ordinal,
                        url,
                        status = ImportItemStatus.QUEUED,
                        podcastId = podcastId,
                    ).copy(sessionId = sessionId),
                ),
            )
    }

    private suspend fun session(sessionId: Long) = checkNotNull(db.importDao().sessionById(sessionId))

    private suspend fun laneAttemptsOf(sessionId: Long): Int =
        ImportOptionsJson.decode(checkNotNull(session(sessionId).optionsJson)).laneAttempts

    private companion object {
        const val FEED_A = "https://a.example.com/feed.xml"
        const val FEED_B = "https://b.example.com/feed.xml"
        const val MARKER = TestClock.DEFAULT_NOW

        /** 05 §9's seven-day cleanup window (`ImportSessionCleaner`'s private constant). */
        const val SESSION_TTL_MS = 7L * 86_400_000
    }
}
