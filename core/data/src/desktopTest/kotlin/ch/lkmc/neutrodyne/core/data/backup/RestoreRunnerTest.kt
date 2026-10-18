// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.CommitCancellingDriver
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakePayloadSource
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
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
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.backup.EntryInfo
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B12b's [RestoreRunner] over real data: archives come from `BackupWriter` on a source
 * database (or hand-crafted zips through `BackupCodec` for hostile rows), staged into the
 * payload lane, and run through the real stager, mergers and Room — the durable-intent gate,
 * Merge/Replace replay, category gates, the settings whitelist, the finish's items/markers
 * and effects, and cancellation between phases and at the COMMITTED boundary.
 */
class RestoreRunnerTest {
    private val driver = CommitCancellingDriver()
    private val clock = TestClock()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val sourceDb = TestDb.inMemory(clock = clock)
    private val secrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val fetchScheduler = FakeRefreshScheduler()
    private val settings = FakeSettingsRepository()
    private val sourceSettings = FakeSettingsRepository()
    private val sourceSecrets = FakeSecretStore()
    private val workScheduler = FakeImportWorkScheduler()
    private val refresh = FakeRefreshController()
    private val snapshots = FakeSnapshotScheduler()

    @Volatile private var failSnapshotRetirement = false
    private val fileSystem =
        object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(
                source: Path,
                target: Path,
            ) {
                if (failSnapshotRetirement && source.name == "auto-snapshot.zip") {
                    throw IOException("snapshot retirement failed")
                }
                super.atomicMove(source, target)
            }
        }
    private val root = createTempDirectory("nd-restore-runner").toFile()
    private val storagePaths = storagePathsFor(root)
    private val payloads =
        PayloadStore(
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            payloads = FakePayloadSource(),
            httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
            netErrors = testClassifier,
            io = Dispatchers.Default,
        )
    private val codec = BackupCodec()
    private var nextArchive = 0

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
    private val runner =
        RestoreRunner(
            db = db,
            stager = stager,
            merger = merger,
            episodes = episodeMerger,
            settingsMerge = RestoreSettingsMerge(settings),
            payloads = payloads,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            workScheduler = workScheduler,
            refresh = refresh,
            channels = channels,
            snapshots = snapshots,
            clock = clock,
            io = Dispatchers.IO,
        )

    @AfterTest
    fun tearDown() {
        db.close()
        sourceDb.close()
        root.deleteRecursively()
    }

    // --- The real writer -> stager -> Room path -------------------------------------------------

    @Test
    fun aMergeRestoresTheWrittenBackupAndFinishes() =
        runTest {
            val sp = seedSourcePodcast(FEED_A)
            val e1 = seedSourceEpisode(sp, "ep-played")
            val e2 = seedSourceEpisode(sp, "ep-queued")
            sourceDb.episodeStateDao().upsert(episodeStateEntity(e1, playedAt = 111L, updatedAt = 112L))
            sourceDb.queueDao().insert(queueEntryEntity(e2, "0.5"))
            sourceSettings.set(AppearanceSettingKeys.LIBRARY_TITLES, true)

            val sessionId = session(writeArchive())

            val result = runner.run(sessionId)

            val completed = assertIs<RestoreRunResult.Completed>(result)
            val restored = checkNotNull(db.podcastDao().byFeedKey(feedKey(FEED_A)))
            assertEquals(1, completed.summary.podcastsAdded)
            // HISTORY + UP_NEXT honoured: the played mark and the queue entry came over.
            val restoredE1 = checkNotNull(db.episodeDao().byIdentityKey(restored.id, "ep-played"))
            val restoredE2 = checkNotNull(db.episodeDao().byIdentityKey(restored.id, "ep-queued"))
            assertEquals(111L, db.episodeStateDao().byEpisode(restoredE1.id)?.playedAt)
            assertEquals(listOf(restoredE2.id), db.queueDao().entries().map { it.episodeId })
            assertEquals(true, settings.get(AppearanceSettingKeys.LIBRARY_TITLES))
            // Finish: one QUEUED item, COMMITTED markers, effects, Finished progress.
            val item = db.importDao().itemsOf(sessionId).single()
            assertEquals(ImportItemStatus.QUEUED, item.status)
            assertEquals(restored.id, item.podcastId)
            val session = checkNotNull(db.importDao().sessionById(sessionId))
            assertEquals(ImportState.COMMITTED, session.state)
            val options = ImportOptionsJson.decode(checkNotNull(session.optionsJson))
            assertNotNull(options.committedAt)
            assertNotNull(options.attemptMarkerAt)
            assertEquals(listOf(sessionId to ImportWorkPolicy.KEEP), workScheduler.scheduled)
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(1, snapshots.requestSoonCalls)
            assertEquals(RestoreProgress.Finished(sessionId, auto = false), runner.progress.first())
        }

    @Test
    fun aReplaceRemovesWhatTheBackupLacks() =
        runTest {
            seedSourcePodcast(FEED_A)
            // The target's local-only podcast does not appear in the archive.
            val localOnly =
                db.podcastDao().insertPodcast(podcastEntity(feedUrl = FEED_B, feedKey = feedKey(FEED_B)))
            val sessionId = session(writeArchive(), mode = RestoreMode.REPLACE)

            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))

            assertNull(db.podcastDao().byId(localOnly))
            assertNotNull(db.podcastDao().byFeedKey(feedKey(FEED_A)))
        }

    @Test
    fun aReplayedRunAfterCommitWritesNothing() =
        runTest {
            seedSourcePodcast(FEED_A)
            val sessionId = session(writeArchive())
            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))
            val items = db.importDao().itemsOf(sessionId).size
            val podcasts = db.podcastDao().allForExport().size

            assertEquals(RestoreRunResult.NotPending, runner.run(sessionId))
            assertEquals(items, db.importDao().itemsOf(sessionId).size)
            assertEquals(podcasts, db.podcastDao().allForExport().size)
        }

    // --- The durable gate ------------------------------------------------------------------------

    @Test
    fun nonPendingSessionsWriteNothing() =
        runTest {
            seedSourcePodcast(FEED_A)
            val archive = writeArchive()
            // Never requested.
            val unrequested = session(archive, requested = false)
            // A non-backup session.
            val opml =
                db.seedImportSession(
                    options = ImportOptions(restoreMode = RestoreMode.MERGE),
                    payloadPath = adopt(archive),
                )
            // Already committed.
            val committed =
                db.seedImportSession(
                    options =
                        ImportOptions(
                            restoreMode = RestoreMode.MERGE,
                            restoreCategories = setOf(RestoreCategory.HISTORY),
                            restoreRequestedAt = clock.now(),
                        ),
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    state = ImportState.COMMITTED,
                    payloadPath = adopt(archive),
                )
            // Unreadable optionsJson — never overwritten with defaults.
            val corrupt =
                db.seedImportSession(
                    options = ImportOptions(restoreMode = RestoreMode.MERGE),
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    payloadPath = adopt(archive),
                )
            db.importDao().setOptionsJson(corrupt, "{not-json")

            for (id in listOf(unrequested, opml, committed, corrupt)) {
                assertEquals(RestoreRunResult.NotPending, runner.run(id), "session $id")
            }
            assertEquals("{not-json", db.importDao().sessionById(corrupt)?.optionsJson)
            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(corrupt)?.state)
            assertNull(db.podcastDao().allForExport().singleOrNull())
            assertTrue(settings.calls.none { it.startsWith("set(") })
        }

    @Test
    fun aLinkedDeviceRejectsBeforeWrites() =
        runTest {
            seedSourcePodcast(FEED_A)
            db.syncStateDao().upsert(SyncStateEntity(serverUrl = "https://sync.example.com", linkedAt = 1L))
            val sessionId = session(writeArchive())

            val result = runner.run(sessionId)

            assertEquals(RestoreRunResult.Failed(sessionId, BackupError.Unsupported), result)
            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(sessionId)?.state)
            assertNull(db.podcastDao().allForExport().singleOrNull())
            assertEquals(RestoreProgress.Failed(sessionId, BackupError.Unsupported), runner.progress.first())
        }

    // --- Validation failures ---------------------------------------------------------------------

    @Test
    fun aCorruptPayloadCancelsWithoutWritingState() =
        runTest {
            val payloadPath = stash("import/bad.bin") { writeUtf8("not a zip") }
            val sessionId = backupSession(payloadPath)

            val result = runner.run(sessionId)

            val failed = assertIs<RestoreRunResult.Failed>(result)
            assertTrue(
                failed.error is BackupError.NotABackup || failed.error is BackupError.Corrupt,
                "validation maps to a typed failure, got ${failed.error}",
            )
            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(sessionId)?.state)
            assertTrue(db.importDao().itemsOf(sessionId).isEmpty())
            assertNull(db.podcastDao().allForExport().singleOrNull())
            assertTrue(settings.calls.none { it.startsWith("set(") })
            assertEquals(RestoreProgress.Failed(sessionId, failed.error), runner.progress.first())
        }

    @Test
    fun aNewerFormatKeepsTheAutoSnapshot() =
        runTest {
            seedAutoSnapshot()
            val entries = archivePayloads()
            val archive =
                archiveOf(
                    entries,
                    manifestBytes(entries) { it.copy(minReaderVersion = BackupCodec.FORMAT_VERSION + 1) },
                )
            val sessionId = session(archive, fromAndroidBackup = true)

            val result = runner.run(sessionId)

            val failed = assertIs<RestoreRunResult.Failed>(result)
            assertIs<BackupError.NewerFormat>(failed.error)
            assertTrue(fileSystem.exists(autoSnapshot()), "NewerFormat keeps the foreign snapshot")
        }

    // --- Categories and settings ------------------------------------------------------------------

    @Test
    fun unselectedCategoriesLeaveStateUntouched() =
        runTest {
            val sp = seedSourcePodcast(FEED_A)
            val e1 = seedSourceEpisode(sp, "ep-played")
            sourceDb.episodeStateDao().upsert(episodeStateEntity(e1, playedAt = 111L, updatedAt = 112L))
            sourceDb.queueDao().insert(queueEntryEntity(e1, "0.5"))
            sourceSettings.set(AppearanceSettingKeys.LIBRARY_TITLES, true)
            val sessionId = session(writeArchive(), categories = emptySet())

            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))

            val restored = checkNotNull(db.podcastDao().byFeedKey(feedKey(FEED_A)))
            // No HISTORY/UP_NEXT: the merger short-circuits — no stub, no state, no queue.
            assertNull(db.episodeDao().byIdentityKey(restored.id, "ep-played"))
            assertTrue(db.queueDao().entries().isEmpty())
            assertTrue(settings.calls.none { it.startsWith("set(") }, "SETTINGS off: no writes")
        }

    @Test
    fun settingsApplyThePortableWhitelistAndCountSkips() =
        runTest {
            val staged =
                SettingsV1(
                    listOf(
                        SettingValueV1("appearance.library_titles", "Bool", value = JsonPrimitive(true)),
                        SettingValueV1("ui.feeds_selected_source", "Text", value = JsonPrimitive("x")),
                        SettingValueV1("madeup.key", "Bool", value = JsonPrimitive(true)),
                        SettingValueV1("appearance.library_titles", "Text", value = JsonPrimitive("yes")),
                        SettingValueV1("appearance.theme", "Choice", value = JsonPrimitive("ULTRAVIOLET")),
                        SettingValueV1("appearance.library_titles", "Bool", value = JsonPrimitive("false")),
                        SettingValueV1("groups.all_hide_older_than_days", "Int32", value = JsonPrimitive("10")),
                    ),
                )
            val entries = archivePayloads(settings = staged)
            val sessionId =
                session(archiveOf(entries, manifestBytes(entries)), categories = setOf(RestoreCategory.SETTINGS))

            val result = runner.run(sessionId)

            val completed = assertIs<RestoreRunResult.Completed>(result)
            assertEquals(1, completed.settingsApplied)
            assertEquals(6, completed.settingsSkipped)
            assertEquals(true, settings.get(AppearanceSettingKeys.LIBRARY_TITLES))
            assertEquals(0, settings.get(GroupsSettingKeys.ALL_HIDE_OLDER_THAN_DAYS))
            assertEquals(
                listOf("set(appearance.library_titles)"),
                settings.calls.filter { it.startsWith("set(") },
            )
        }

    @Test
    fun aSettingsWriteFailureRetriesWithIntentIntact() =
        runTest {
            val staged =
                SettingsV1(
                    listOf(SettingValueV1("appearance.library_titles", "Bool", value = JsonPrimitive(true))),
                )
            val entries = archivePayloads(settings = staged)
            val sessionId =
                session(archiveOf(entries, manifestBytes(entries)), categories = setOf(RestoreCategory.SETTINGS))
            settings.failNextSet = SettingsError.WriteFailed

            val retry = assertIs<RestoreRunResult.Retry>(runner.run(sessionId))
            assertEquals(BackupError.Storage, retry.error)
            // The durable intent survives: still PREVIEW + requested, so the next run replays.
            val session = checkNotNull(db.importDao().sessionById(sessionId))
            assertEquals(ImportState.PREVIEW, session.state)
            assertNotNull(ImportOptionsJson.decode(checkNotNull(session.optionsJson)).restoreRequestedAt)

            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))
            assertEquals(true, settings.get(AppearanceSettingKeys.LIBRARY_TITLES))
            assertEquals(
                ImportItemStatus.QUEUED,
                db
                    .importDao()
                    .itemsOf(sessionId)
                    .single()
                    .status,
                "a replay must still fetch the pending podcast inserted by its first attempt",
            )
        }

    // --- Finish: items, redownload ids, snapshot retirement ---------------------------------------

    @Test
    fun finishWritesItemStatusesAndRedownloadIds() =
        runTest {
            // A matched local podcast (same feedKey), a new one, and one needing credentials.
            val matched =
                db.podcastDao().insertPodcast(podcastEntity(feedUrl = FEED_B, feedKey = feedKey(FEED_B)))
            val staged =
                LibraryV1(
                    podcasts =
                        listOf(
                            podcastV1(FEED_A),
                            podcastV1(FEED_B),
                            podcastV1(FEED_C, credentialOrigin = "https://c.example.com"),
                        ),
                    groups = emptyList(),
                )
            val episodeLines =
                listOf(
                    line(FEED_A, "ep-old", d = 100L, dl = true),
                    line(FEED_A, "ep-new", d = 200L, dl = true),
                    line("https://unknown.example.com/feed", "ep-x", d = 300L, dl = true),
                ).joinToString("") { codec.encodeEpisodeLine(it) + "\n" }
            val entries = archivePayloads(library = staged, episodes = episodeLines.encodeToByteArray())
            val sessionId = session(archiveOf(entries, manifestBytes(entries)))

            val result = runner.run(sessionId)

            val completed = assertIs<RestoreRunResult.Completed>(result)
            // The unknown-podcast line counts as skipped in the composite summary.
            assertEquals(1, completed.summary.skippedLines)
            val items = db.importDao().itemsOf(sessionId)
            assertEquals(3, items.size)
            val byUrl = items.associateBy { it.originalUrl }
            assertEquals(ImportItemStatus.QUEUED, byUrl.getValue(FEED_A).status)
            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, byUrl.getValue(FEED_B).status)
            assertEquals(matched, byUrl.getValue(FEED_B).podcastId)
            assertEquals(ImportItemStatus.AUTH_REQUIRED, byUrl.getValue(FEED_C).status)
            // redownloadIds are the resolved local ids, newest `d` first — recorded by the
            // phase, not re-matched (the unknown podcast's line resolves to nothing).
            val restoredA = checkNotNull(db.podcastDao().byFeedKey(feedKey(FEED_A)))
            val idNew = checkNotNull(db.episodeDao().byIdentityKey(restoredA.id, "ep-new")).id
            val idOld = checkNotNull(db.episodeDao().byIdentityKey(restoredA.id, "ep-old")).id
            val options =
                ImportOptionsJson.decode(checkNotNull(db.importDao().sessionById(sessionId)).optionsJson!!)
            assertEquals(listOf(idNew, idOld), options.redownloadIds)
            // User options are untouched; only the worker-owned fields moved.
            assertEquals(RestoreMode.MERGE, options.restoreMode)
            assertNotNull(options.restoreRequestedAt)
        }

    @Test
    fun aSuccessfulAutoRestoreRetiresTheSnapshot() =
        runTest {
            seedAutoSnapshot()
            seedSourcePodcast(FEED_A)
            val sessionId = session(writeArchive(), fromAndroidBackup = true)

            val completed = assertIs<RestoreRunResult.Completed>(runner.run(sessionId))
            assertTrue(completed.auto)
            assertRetired()
        }

    @Test
    fun aCorruptAutoRestoreRetiresTheSnapshot() =
        runTest {
            seedAutoSnapshot()
            val payloadPath = stash("import/bad.bin") { writeUtf8("not a zip") }
            val sessionId = backupSession(payloadPath, fromAndroidBackup = true)

            assertIs<RestoreRunResult.Failed>(runner.run(sessionId))
            assertRetired()
        }

    @Test
    fun aFailedSnapshotRetirementKeepsTheRestoreReplayable() =
        runTest {
            seedAutoSnapshot()
            seedSourcePodcast(FEED_A)
            val sessionId = session(writeArchive(), fromAndroidBackup = true)
            failSnapshotRetirement = true

            assertIs<RestoreRunResult.Retry>(runner.run(sessionId))

            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sessionId)?.state)
            assertTrue(fileSystem.exists(autoSnapshot()))
            assertTrue(workScheduler.scheduled.isEmpty(), "unfinished restore must not start feed work")
            failSnapshotRetirement = false

            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))
            assertRetired()
        }

    @Test
    fun aCorruptSnapshotWhoseRetirementFailsIsRetriedBeforeCancellation() =
        runTest {
            seedAutoSnapshot()
            val payloadPath = stash("import/bad.bin") { writeUtf8("not a zip") }
            val sessionId = backupSession(payloadPath, fromAndroidBackup = true)
            failSnapshotRetirement = true

            assertIs<RestoreRunResult.Retry>(runner.run(sessionId))

            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sessionId)?.state)
            assertTrue(fileSystem.exists(autoSnapshot()))
            failSnapshotRetirement = false

            assertIs<RestoreRunResult.Failed>(runner.run(sessionId))
            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(sessionId)?.state)
            assertRetired()
        }

    @Test
    fun aFailedCancellationMarkerKeepsTheRequestReplayable() =
        runTest {
            val payloadPath = stash("import/bad.bin") { writeUtf8("not a zip") }
            val sessionId = backupSession(payloadPath)
            driver.armOnStep("UPDATE import_session SET state") { throw IOException("cancel marker failed") }

            assertIs<RestoreRunResult.Retry>(runner.run(sessionId))

            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sessionId)?.state)
            assertIs<RestoreRunResult.Failed>(runner.run(sessionId))
            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(sessionId)?.state)
        }

    // --- Cancellation ------------------------------------------------------------------------------

    @Test
    fun aCancelBetweenPhasesKeepsTheDurableIntent() =
        runTest {
            seedSourcePodcast(FEED_A)
            val sessionId = session(writeArchive())

            runCancelling(sessionId, "INSERT OR ABORT INTO `podcast`")

            // Transaction A committed; cancellation landed before COMMITTED — the durable
            // PREVIEW request survives for a replay, and no false success was recorded.
            val session = checkNotNull(db.importDao().sessionById(sessionId))
            assertEquals(ImportState.PREVIEW, session.state)
            assertNotNull(ImportOptionsJson.decode(checkNotNull(session.optionsJson)).restoreRequestedAt)
            assertTrue(runner.progress.first() !is RestoreProgress.Finished)

            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))
            assertEquals(ImportState.COMMITTED, db.importDao().sessionById(sessionId)?.state)
            assertEquals(
                ImportItemStatus.QUEUED,
                db
                    .importDao()
                    .itemsOf(sessionId)
                    .single()
                    .status,
            )
        }

    @Test
    fun aCancelInsideAnEpisodeChunkKeepsTheDurableIntent() =
        runTest {
            seedSourcePodcast(FEED_A)
            val entries =
                archivePayloads(
                    library = LibraryV1(listOf(podcastV1(FEED_A)), emptyList()),
                    episodes =
                        (codec.encodeEpisodeLine(line(FEED_A, "ep-stub")) + "\n").encodeToByteArray(),
                )
            val sessionId = session(archiveOf(entries, manifestBytes(entries)))

            lateinit var caller: Job
            caller = launch { runner.run(sessionId) }
            driver.armOnStep("INSERT OR IGNORE INTO `episode`") { caller.cancel() }
            caller.join()

            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sessionId)?.state)

            assertIs<RestoreRunResult.Completed>(runner.run(sessionId))
            assertEquals(ImportState.COMMITTED, db.importDao().sessionById(sessionId)?.state)
            assertEquals(
                ImportItemStatus.QUEUED,
                db
                    .importDao()
                    .itemsOf(sessionId)
                    .single()
                    .status,
            )
        }

    @Test
    fun aCancelAtTheCommitBoundaryStillFinishesEffects() =
        runTest {
            seedSourcePodcast(FEED_A)
            val sessionId = session(writeArchive())

            runCancelling(sessionId, "UPDATE import_session SET state = 'COMMITTED'")

            // The marker landed and every effect owed to it ran despite the cancel.
            assertEquals(ImportState.COMMITTED, db.importDao().sessionById(sessionId)?.state)
            assertEquals(1, db.importDao().itemsOf(sessionId).size)
            assertEquals(listOf(sessionId to ImportWorkPolicy.KEEP), workScheduler.scheduled)
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(1, snapshots.requestSoonCalls)
        }

    // --- Helpers ------------------------------------------------------------------------------------

    private suspend fun CoroutineScope.runCancelling(
        sessionId: Long,
        statement: String,
    ) {
        lateinit var caller: Job
        caller = launch(start = CoroutineStart.LAZY) { runner.run(sessionId) }
        driver.arm(statement) { caller.cancel() }
        caller.start()
        caller.join()
        assertEquals(1, driver.cancellations, "the commit hook fired")
    }

    private fun feedKey(feedUrl: String): String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))

    // -- source device (the writer's library) --

    private suspend fun seedSourcePodcast(feedUrl: String): Long =
        sourceDb.podcastDao().insertPodcast(podcastEntity(feedUrl = feedUrl, feedKey = feedKey(feedUrl)))

    private suspend fun seedSourceEpisode(
        podcastId: Long,
        identityKey: String,
    ): Long =
        sourceDb
            .episodeDao()
            .insertStub(
                episodeEntity(podcastId = podcastId, identityKey = identityKey) {
                    copy(enclosureUrl = "https://cdn.example.com/$identityKey.mp3")
                },
            )

    private suspend fun writeArchive(kind: BackupKind = BackupKind.MANUAL): Path {
        val archive = root.absolutePath.toPath() / "archive-${nextArchive++}.zip"
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
        assertIs<Outcome.Success<*>>(writer.write(kind, archive, includeCredentials = false))
        return archive
    }

    // -- session + payload lane --

    /** Writes raw [content] at [name] under the import cache; returns the `payloadPath`. */
    private fun stash(
        name: String,
        content: okio.BufferedSink.() -> Unit,
    ): String {
        val target = storagePaths.cacheDir.toPath() / name
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.write(target) { content() }
        return name
    }

    private fun adopt(
        archive: Path,
        name: String = "import/restore-$nextArchive.bin",
    ): String {
        val target = storagePaths.cacheDir.toPath() / name
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.copy(archive, target)
        return name
    }

    private suspend fun backupSession(
        payloadPath: String,
        fromAndroidBackup: Boolean = false,
    ): Long {
        val sessionId =
            db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = RestoreMode.MERGE,
                        restoreCategories = setOf(RestoreCategory.HISTORY),
                        fromAndroidBackup = fromAndroidBackup,
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = payloadPath,
            )
        assertIs<Outcome.Success<*>>(db.requestImportRestore(sessionId, clock.now()))
        return sessionId
    }

    private suspend fun session(
        archive: Path,
        mode: RestoreMode = RestoreMode.MERGE,
        categories: Set<RestoreCategory> =
            setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT, RestoreCategory.SETTINGS),
        fromAndroidBackup: Boolean = false,
        requested: Boolean = true,
    ): Long {
        val sessionId =
            db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = mode,
                        restoreCategories = categories,
                        fromAndroidBackup = fromAndroidBackup,
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = adopt(archive),
            )
        if (requested) assertIs<Outcome.Success<*>>(db.requestImportRestore(sessionId, clock.now()))
        return sessionId
    }

    // -- the foreign auto-snapshot --

    private fun autoSnapshot(): Path = storagePaths.filesDir.toPath() / "backup" / "auto-snapshot.zip"

    private fun seedAutoSnapshot() {
        val snapshot = autoSnapshot()
        fileSystem.createDirectories(checkNotNull(snapshot.parent))
        fileSystem.write(snapshot) { writeUtf8("foreign-snapshot") }
    }

    private fun assertRetired() {
        val dir = storagePaths.filesDir.toPath() / "backup"
        assertTrue(!fileSystem.exists(autoSnapshot()), "auto-snapshot renamed")
        assertTrue(
            fileSystem.list(dir).any { it.name.startsWith("restored-") && it.name.endsWith(".zip") },
            "a restored-{ts}.zip exists in $dir",
        )
    }

    // -- crafted archives --

    private fun podcastV1(
        feedUrl: String,
        credentialOrigin: String? = null,
    ): PodcastV1 =
        PodcastV1(
            key = feedKey(feedUrl),
            feedUrl = feedUrl,
            title = "Show",
            subscribedAt = 1_700_000_000_000L,
            credentialOrigin = credentialOrigin,
        )

    private fun line(
        feedUrl: String,
        k: String,
        d: Long? = null,
        dl: Boolean = false,
    ): EpisodeLineV1 =
        EpisodeLineV1(
            p = feedKey(feedUrl),
            k = k,
            d = d,
            u = "https://cdn.example.com/$k.mp3",
            dl = dl,
            ts = 50L,
        )

    private fun archivePayloads(
        library: LibraryV1 = LibraryV1(listOf(podcastV1(FEED_A)), emptyList()),
        episodes: ByteArray = ByteArray(0),
        queue: QueueV1 = QueueV1(upNext = emptyList()),
        settings: SettingsV1 = SettingsV1(values = emptyList()),
    ): Map<String, ByteArray> =
        mapOf(
            BackupCodec.ENTRY_LIBRARY to codec.encodeLibrary(library).encodeToByteArray(),
            BackupCodec.ENTRY_EPISODES to episodes,
            BackupCodec.ENTRY_QUEUE to codec.encodeQueue(queue).encodeToByteArray(),
            BackupCodec.ENTRY_SETTINGS to codec.encodeSettings(settings).encodeToByteArray(),
        )

    private fun manifestBytes(
        payloads: Map<String, ByteArray>,
        mutate: (BackupManifest) -> BackupManifest = { it },
    ): ByteArray = codec.encodeManifest(mutate(manifest(entryInfos(payloads)))).encodeToByteArray()

    private fun manifest(entries: List<EntryInfo>): BackupManifest =
        BackupManifest(
            createdAt = "2026-10-22T00:00:00Z",
            kind = BackupKind.MANUAL,
            app = AppInfoV1("1.0", 1L, "desktop-test", "linux"),
            dbSchemaVersion = 1,
            episodeKeysVersion = 1,
            urlNormalizerVersion = UrlNormalizer.VERSION,
            installationId = "install-test",
            counts = CountsV1(0, 0, 0, 0, 0, 0, 0),
            entries = entries,
        )

    private fun entryInfos(payloads: Map<String, ByteArray>): List<EntryInfo> =
        payloads.map { (name, bytes) ->
            EntryInfo(name, bytes.size.toLong(), bytes.toByteString().sha256().hex(), records = 1)
        }

    private fun archiveOf(
        entries: Map<String, ByteArray>,
        manifest: ByteArray?,
        extras: Map<String, ByteArray> = emptyMap(),
    ): Path {
        val archive = root.absolutePath.toPath() / "crafted-${nextArchive++}.zip"
        ZipArchiveWriter().create(archive).use { writer ->
            for (
            (name, bytes) in entries + extras +
                listOfNotNull(
                    manifest?.let { BackupCodec.ENTRY_MANIFEST to it },
                )
            ) {
                writer.entry(name).buffer().use { it.write(bytes) }
            }
        }
        return archive
    }

    private companion object {
        const val FEED_A = "https://a.example.com/feed.xml"
        const val FEED_B = "https://b.example.com/feed.xml"
        const val FEED_C = "https://c.example.com/feed.xml"
    }
}
