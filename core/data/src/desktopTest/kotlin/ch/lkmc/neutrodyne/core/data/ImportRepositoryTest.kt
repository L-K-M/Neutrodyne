// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.paging.testing.asSnapshot
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.CredentialCommitCoordinatorImpl
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportCommitter
import ch.lkmc.neutrodyne.core.data.importing.ImportFixUps
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportRepositoryImpl
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.ImportWarningJson
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.ImportWarning
import ch.lkmc.neutrodyne.core.model.SourceGroup
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `ImportRepositoryImpl` end to end (05): `create`/`confirm` run the real
 * `ImportSessions`/`ImportCommitter` against a real in-memory Room database; reads,
 * selections, options edits, proposals and `cancel` are asserted off the real rows and
 * `PayloadStore` files. The fix-ups' routing is covered with a recording
 * `PodcastRepository`; `remove`'s cascade runs `deleteCascade` for real.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportRepositoryTest {
    private val clock = TestClock()
    private val driver = CommitCancellingDriver()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val fileSystem = HookedDeleteFileSystem(FileSystem.SYSTEM)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = FakeRefreshController()
    private val channels = FakeGroupChannelSync()
    private val scheduler = FakeImportWorkScheduler()
    private val podcasts = FakePodcastRepository()
    private val poster = FakeNewEpisodePoster()

    private lateinit var root: File
    private lateinit var payloads: FakePayloadSource
    private lateinit var store: PayloadStore
    private lateinit var repository: ImportRepositoryImpl

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-import-repo")
                .toFile()
        payloads = FakePayloadSource()
        store =
            PayloadStore(
                storagePaths = storagePathsFor(root),
                fileSystem = fileSystem,
                payloads = payloads,
                httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                netErrors = testClassifier,
                io = Dispatchers.Default,
            )
        val sessions =
            ImportSessions(
                db = db,
                payloads = store,
                classifier = ImportClassifier(db, YouTubeUrlClassifier()),
                opmlReader = XmlPullOpmlReader.discovered(),
                archives = ZipGuard(),
                clock = clock,
                appScope = appScope,
                io = Dispatchers.Default,
            )
        val secrets =
            DesktopSecretStore(
                db,
                appDirsFor(root),
                CredentialCommitCoordinatorImpl(),
                clock,
                IdentitySecretProtection,
                appScope,
            )
        val committer =
            ImportCommitter(
                db = db,
                secrets = secrets,
                coordinator = CredentialCommitCoordinatorImpl(),
                workScheduler = scheduler,
                refresh = refresh,
                channels = channels,
                orderKeys = TestOrderKeys,
                clock = clock,
            )
        val cascading = CascadingPodcasts()
        val fixUps =
            ImportFixUps(
                db = db,
                podcasts = cascading,
                unsubscribe = UnsubscribeUseCase(cascading, poster),
                workScheduler = scheduler,
                clock = clock,
            )
        repository =
            ImportRepositoryImpl(
                db = db,
                sessions = sessions,
                committer = committer,
                fixUps = fixUps,
                payloads = store,
                workScheduler = scheduler,
                cpu = Dispatchers.Default,
                io = Dispatchers.Default,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        appScope.cancel()
        root.deleteRecursively()
    }

    // --- Composition: create and confirm run the real pipeline ----------------------------------

    @Test
    fun createAndConfirmDriveTheRealPipeline() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            val sessionId = assertCreate()

            assertIs<Outcome.Success<*>>(repository.confirm(sessionId))

            assertEquals(2, db.podcastDao().allForExport().size)
            val session = assertNotNull(db.importDao().sessionById(sessionId))
            assertEquals(ImportState.COMMITTED, session.state)
            assertEquals(listOf(sessionId to ImportWorkPolicy.KEEP), scheduler.scheduled)
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(0, db.syncOutboxDao().count(), "sync tables stay disabled offline")
        }

    @Test
    fun repeatedConfirmWritesNothingTwice() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            val sessionId = assertCreate()

            assertIs<Outcome.Success<*>>(repository.confirm(sessionId))
            assertIs<Outcome.Success<*>>(repository.confirm(sessionId))

            assertEquals(2, db.podcastDao().allForExport().size)
            assertEquals(1, scheduler.scheduled.size)
        }

    // --- Session reads ----------------------------------------------------------------------------

    @Test
    fun observeSessionDecodesCountsOptionsWarningsAndTallies() =
        runTest {
            val sessionId =
                db.seedImportSession(
                    options =
                        ImportOptions(
                            importGroups = false,
                            targetGroupName = "Everywhere",
                            laneAttempts = 3,
                            sourceGroups = listOf(SourceGroup("tech", "Tech", null, null, null)),
                        ),
                    items =
                        listOf(
                            importItem(1, "https://a.example.com/f", groups = listOf("Tech")),
                            importItem(
                                2,
                                "https://www.youtube.com/@channel",
                                kind = ImportItemKind.YOUTUBE,
                                status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                                selected = false,
                            ),
                            importItem(
                                3,
                                "https://c.example.com/f",
                                status = ImportItemStatus.INVALID_URL,
                                selected = false,
                            ),
                        ),
                )
            db
                .importDao()
                .setWarningsJson(
                    sessionId,
                    ImportWarningJson.encodeAll(listOf(ImportWarning("IGNORED_OUTLINES", count = 4))),
                )

            val view = assertNotNull(repository.observeSession(sessionId).first())

            assertEquals(sessionId, view.id)
            assertEquals(ImportState.PREVIEW, view.state)
            assertEquals(
                mapOf(
                    ImportItemStatus.PREVIEW to 1,
                    ImportItemStatus.YOUTUBE_UNSUPPORTED_YET to 1,
                    ImportItemStatus.INVALID_URL to 1,
                ),
                view.counts,
            )
            assertEquals(1, view.selectedCount)
            assertEquals(1, view.youtubeCount)
            assertEquals("Everywhere", view.options.targetGroupName)
            assertEquals(3, view.options.laneAttempts)
            assertEquals(
                "tech",
                view.options.sourceGroups
                    .single()
                    .nameKey,
            )
            assertEquals(listOf(ImportWarning("IGNORED_OUTLINES", count = 4)), view.warnings)
        }

    @Test
    fun observeOpenSessionsKeepsLiveStatesOldestFirst() =
        runTest {
            val old = db.seedImportSession(createdAt = 10, state = ImportState.PREVIEW)
            db.seedImportSession(createdAt = 20, state = ImportState.DONE, finishedAt = 25)
            val fetching = db.seedImportSession(createdAt = 30, state = ImportState.FETCHING)
            db.seedImportSession(createdAt = 40, state = ImportState.CANCELLED)

            val open = repository.observeOpenSessions().first()

            assertEquals(listOf(old, fetching), open.map { it.id })
        }

    private suspend fun reportSession(): Long {
        val rows =
            (0 until 100).map { ordinal ->
                val status = if (ordinal % 2 == 0) ImportItemStatus.NO_MEDIA else ImportItemStatus.SUBSCRIBED
                importItem(ordinal, "https://host$ordinal.example/feed", status = status)
            } +
                listOf(
                    importItem(
                        100,
                        "https://youtube.com/feed",
                        status = ImportItemStatus.FETCH_FAILED,
                    ).copy(errorDetail = "DEFERRED"),
                )
        return db.seedImportSession(state = ImportState.DONE, items = rows)
    }

    @Test fun reportCountsDeferredSeparately() =
        runTest {
            val view = repository.observeSession(reportSession()).first()!!
            assertEquals(1, view.deferredCount, "Deferred failures need a separate session count")
        }

    @Test fun reportIncludesUnloadedNoMediaOrdinals() =
        runTest {
            val view = repository.observeSession(reportSession()).first()!!
            assertEquals(
                (0 until 100 step 2).toSet(),
                view.noMediaOrdinals,
                "Remove all must include unloaded no-media rows",
            )
        }

    @Test fun reportGroupsRowsAcrossPages() =
        runTest {
            val id = reportSession()
            val paged = repository.pagedItems(id, ImportItemFilter.ALL).asSnapshot { scrollTo(100) }
            assertEquals(
                (1 until 100 step 2).toList() + (0 until 100 step 2).toList() + 100,
                paged.map {
                    it.ordinal
                },
                "Report sections must stay contiguous across pages",
            )
        }

    // --- Paged items and the filter table -----------------------------------------------------------

    @Test
    fun pagedItemsApplyTheStatusTable() =
        runTest {
            val items =
                listOf(
                    // A row that was selected and committed; it is never a preview pick again.
                    importItem(1, "https://a.example.com/f", status = ImportItemStatus.QUEUED),
                    // Unselected at commit — 05 counts it as not imported past PREVIEW.
                    importItem(2, "https://b.example.com/f", selected = false),
                    importItem(3, "https://c.example.com/f", status = ImportItemStatus.DUPLICATE_IN_FILE),
                    importItem(4, "https://d.example.com/f", status = ImportItemStatus.INVALID_URL),
                    importItem(5, "https://e.example.com/f", status = ImportItemStatus.NOT_A_FEED),
                    importItem(6, "https://f.example.com/f", status = ImportItemStatus.AUTH_REQUIRED),
                    importItem(7, "https://g.example.com/f", status = ImportItemStatus.SUBSCRIBED),
                    importItem(
                        8,
                        "https://www.youtube.com/@c",
                        kind = ImportItemKind.YOUTUBE,
                        status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                        selected = false,
                    ),
                    importItem(9, "https://i.example.com/f", status = ImportItemStatus.GONE),
                )
            val sid = db.seedImportSession(items = items)

            suspend fun ordinals(filter: ImportItemFilter): List<Int> =
                repository
                    .pagedItems(sid, filter)
                    .asSnapshot()
                    .map(ImportItemView::ordinal)

            // In PREVIEW, NOT_IMPORTED shows the dead rows only — unselected previews are picks.
            assertEquals(listOf(3, 4, 8), ordinals(ImportItemFilter.NOT_IMPORTED))
            assertEquals(listOf(2), ordinals(ImportItemFilter.NEW_ONLY))

            db.importDao().setState(sid, ImportState.COMMITTED)

            // A committed session counts its still-PREVIEW rows as not imported too.
            assertEquals(listOf(2, 3, 4, 8), ordinals(ImportItemFilter.NOT_IMPORTED))
            assertEquals(listOf(5, 6, 9), ordinals(ImportItemFilter.NEEDS_ATTENTION))
            assertEquals(listOf(1, 7, 5, 6, 9, 2, 3, 4, 8), ordinals(ImportItemFilter.ALL))
        }

    @Test
    fun itemViewsShowHostAndPrivacyNeverRawUrls() =
        runTest {
            val sid =
                db.seedImportSession(
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://alice:s3cret@feeds.private.example.com/rss",
                                normalizedUrl = "https://feeds.private.example.com/rss",
                                title = null,
                            ),
                        ),
                )

            val view = repository.pagedItems(sid, ImportItemFilter.ALL).asSnapshot().single()

            assertEquals("feeds.private.example.com", view.host)
            // The title falls back to the host, never the credential-bearing URL.
            assertEquals("feeds.private.example.com", view.title)
            assertTrue(view.isPrivate)
            assertTrue(view.selectable)
        }

    @Test
    fun untitledInvalidUrlsNeverBecomeCredentialBearingTitles() =
        runTest {
            val sid =
                db.seedImportSession(
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://alice:s3cret@/rss",
                                normalizedUrl = null,
                                title = null,
                                status = ImportItemStatus.INVALID_URL,
                            ),
                        ),
                )

            val view = repository.pagedItems(sid, ImportItemFilter.ALL).asSnapshot().single()

            assertEquals("", view.host)
            assertEquals("", view.title, "a missing public title/host must never fall back to the raw URL")
            assertTrue(!view.selectable)
        }

    // --- Group proposals -----------------------------------------------------------------------------

    @Test
    fun groupProposalsPreserveEmptyFoldersAndResolveExisting() =
        runTest {
            val existingId = seedGroup("Tech")
            val sid =
                db.seedImportSession(
                    options =
                        ImportOptions(
                            excludedGroupKeys = setOf("audio"),
                            renamedGroups = mapOf("news" to "Renamed News"),
                            sourceGroups =
                                listOf(
                                    SourceGroup("tech", "Tech", null, null, null),
                                    SourceGroup("news", "News", null, null, null),
                                    SourceGroup("audio", "Audio", null, null, "gpodder_section"),
                                    SourceGroup("empty", "Empty Folder", null, null, null),
                                ),
                        ),
                    items =
                        listOf(
                            importItem(1, "https://a.example.com/f", groups = listOf("Tech", "News")),
                            importItem(2, "https://b.example.com/f", groups = listOf("Tech", "Audio")),
                        ),
                )

            val proposals = repository.observeGroupProposals(sid).first()

            assertEquals(listOf("tech", "news", "audio", "empty"), proposals.map { it.nameKey })
            assertEquals(existingId, proposals[0].existingGroupId)
            // The existing group's own name wins over the source spelling.
            assertEquals("Tech", proposals[0].name)
            assertEquals(2, proposals[0].memberCount)
            assertTrue(proposals[0].included)
            assertEquals("Renamed News", proposals[1].name)
            assertEquals(1, proposals[1].memberCount)
            assertTrue(!proposals[2].included)
            assertEquals("gpodder_section", proposals[2].excludedReason)
            // The empty folder survives with zero members and no existing match.
            assertEquals("Empty Folder", proposals[3].name)
            assertEquals(0, proposals[3].memberCount)
            assertNull(proposals[3].existingGroupId)
        }

    // --- PREVIEW-only edits ----------------------------------------------------------------------------

    @Test
    fun setSelectedIsPreviewOnly() =
        runTest {
            val sid =
                db.seedImportSession(
                    items =
                        listOf(
                            importItem(1, "https://a.example.com/f"),
                            importItem(2, "https://b.example.com/f"),
                            importItem(
                                3,
                                "https://c.example.com/f",
                                status = ImportItemStatus.INVALID_URL,
                                selected = false,
                            ),
                        ),
                )

            repository.setSelected(sid, setOf(1), selected = false)
            assertEquals(
                listOf(false, true),
                db
                    .importDao()
                    .itemsOf(sid)
                    .take(2)
                    .map { it.selected },
            )

            // null = every selectable row; the INVALID_URL row is not selectable.
            repository.setSelected(sid, null, selected = false)
            assertTrue(db.importDao().itemsOf(sid).none { it.selected })

            repository.setSelected(sid, null, selected = true)
            assertEquals(
                listOf(true, true, false),
                db.importDao().itemsOf(sid).map { it.selected },
            )

            db.importDao().setState(sid, ImportState.COMMITTED)
            repository.setSelected(sid, null, selected = false)
            assertEquals(
                listOf(true, true, false),
                db.importDao().itemsOf(sid).map { it.selected },
                "selection writes must not touch a committed session",
            )
        }

    @Test
    fun updateOptionsMergesLatestAndProtectsWorkerFields() =
        runTest {
            val sid =
                db.seedImportSession(
                    options =
                        ImportOptions(
                            laneAttempts = 7,
                            sourceGroups = listOf(SourceGroup("tech", "Tech", null, null, null)),
                        ),
                )

            // A stale screen that snapshots empty groups and laneAttempts=0 can only win the
            // fields a user may change.
            repository.updateOptions(
                sid,
            ) { it.copy(importGroups = false, laneAttempts = 0, sourceGroups = emptyList()) }

            val options = optionsOf(sid)
            assertEquals(false, options.importGroups)
            assertEquals(7, options.laneAttempts)
            assertEquals(listOf("tech"), options.sourceGroups.map { it.nameKey })

            // Once commit or a restore request stamps the row, options edits freeze entirely.
            db
                .importDao()
                .setOptionsJson(sid, ImportOptionsJson.encode(options.copy(committedAt = clock.now())))
            repository.updateOptions(sid) { it.copy(importGroups = true) }
            assertEquals(false, optionsOf(sid).importGroups)
        }

    @Test
    fun updateOptionsRefusesPastPreview() =
        runTest {
            val sid = db.seedImportSession(state = ImportState.COMMITTED)

            repository.updateOptions(sid) { it.copy(importGroups = false) }

            assertEquals(true, optionsOf(sid).importGroups)
        }

    // --- Cancel ----------------------------------------------------------------------------------------

    @Test
    fun cancelPreviewDeletesPayloadThenRow() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            val sessionId = assertCreate()
            val session = assertNotNull(db.importDao().sessionById(sessionId))
            val payload = File(root, "cache").resolve(requireNotNull(session.payloadPath))
            assertTrue(payload.isFile)

            repository.cancel(sessionId)

            assertNull(db.importDao().sessionById(sessionId))
            assertTrue(db.importDao().itemsOf(sessionId).isEmpty(), "items cascade with the row")
            assertTrue(!payload.exists(), "the payload went first")
        }

    @Test
    fun cancelActiveStopsTheHostAndKeepsSubscriptions() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.QUEUED,
                                podcastId = podcastId,
                            ),
                        ),
                )

            repository.cancel(sid)

            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(sid)?.state)
            assertEquals(listOf(sid), scheduler.cancelled)
            assertNotNull(db.podcastDao().byId(podcastId), "the subscription stays")
            assertEquals(
                ImportItemStatus.QUEUED,
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .status,
            )
        }

    @Test
    fun cancelLeavesDoneAndRestoreRequestedAlone() =
        runTest {
            val done = db.seedImportSession(state = ImportState.DONE, finishedAt = clock.now())
            val requested =
                db.seedImportSession(
                    options = ImportOptions(restoreRequestedAt = clock.now()),
                    items = listOf(importItem(1, "https://a.example.com/f")),
                )

            repository.cancel(done)
            repository.cancel(requested)

            assertEquals(ImportState.DONE, db.importDao().sessionById(done)?.state)
            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(requested)?.state)
            assertTrue(scheduler.cancelled.isEmpty())
        }

    // --- The restore-request race (05 §9's PREVIEW cancel) ----------------------------------------------

    @Test
    fun aPreviewDeleteRacingACommittedRestoreRequestNeverStrandsARow() =
        runTest {
            // A restore intent landing while the payload delete runs must not leave a live
            // row pointing at a deleted file — either the request wins outright (row and
            // payload both kept) or the cancel wins (both gone). The hook writes it
            // synchronously on the same connection, so no nested transaction is awaited.
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            val sid = assertCreate()
            fileSystem.onDelete = {
                driver.exec(
                    "UPDATE import_session SET optionsJson = " +
                        "'${ImportOptionsJson.encode(ImportOptions(restoreRequestedAt = clock.now()))}'" +
                        " WHERE id = $sid",
                )
            }

            repository.cancel(sid)

            val session = db.importDao().sessionById(sid)
            if (session != null) {
                assertTrue(
                    File(root, "cache").resolve(requireNotNull(session.payloadPath)).exists(),
                    "a live row must keep its payload",
                )
            }
        }

    @Test
    fun aCommittedRestoreRequestKeepsPreviewRowAndPayload() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            val sid = assertCreate()

            // The requester's own write transaction is genuinely open and holding the writer
            // lock while cancel runs — the committed request wins and keeps both.
            val inRequest = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requester =
                async(Dispatchers.Default) {
                    db.withWriteTransaction {
                        checkNotNull(db.importDao().sessionById(sid))
                        inRequest.complete(Unit)
                        release.await()
                        db
                            .importDao()
                            .setOptionsJson(
                                sid,
                                ImportOptionsJson.encode(
                                    ImportOptions(restoreRequestedAt = clock.now()),
                                ),
                            )
                    }
                }
            inRequest.await()
            val cancelling = async(Dispatchers.Default) { repository.cancel(sid) }
            release.complete(Unit)
            requester.await()
            cancelling.await()

            val session = assertNotNull(db.importDao().sessionById(sid), "the requested row is not cancel's")
            assertNotNull(
                ImportOptionsJson.decode(requireNotNull(session.optionsJson)).restoreRequestedAt,
                "the committed request stands",
            )
            assertTrue(
                File(root, "cache").resolve(requireNotNull(session.payloadPath)).exists(),
                "the payload stays with its row",
            )
        }

    @Test
    fun aCancelWinningTheRestoreRaceDeletesBothAndFailsTheRequester() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            val sid = assertCreate()

            // The cancel holds its write transaction across the payload delete, so the
            // delete hook is where its ownership claim is held: the requester only starts
            // there, and its own transaction can then commit only after the cancel's — it
            // must find the row gone. The hook waits on the requester STARTING, never on
            // its transaction (which cannot run while the cancel holds the writer lock).
            val claimHeld = CompletableDeferred<Unit>()
            val requesterStarted = CountDownLatch(1)
            fileSystem.onDelete = {
                claimHeld.complete(Unit)
                requesterStarted.await()
            }
            val requester =
                async(Dispatchers.Default) {
                    claimHeld.await()
                    requesterStarted.countDown()
                    db.requestImportRestore(sid, clock.now())
                }

            repository.cancel(sid)
            val outcome = requester.await()

            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(outcome).error,
            )
            assertNull(db.importDao().sessionById(sid))
        }

    @Test
    fun aCorruptOptionsRowIsNeverOverwrittenOrDeleted() =
        runTest {
            val sid = db.seedImportSession(payloadPath = "import/c.bin")
            File(root, "cache/import").mkdirs()
            val payload = File(root, "cache/import/c.bin").apply { writeText("x") }
            db.importDao().setOptionsJson(sid, "{corrupt")

            // Neither cancel nor an options edit may touch a row they cannot read —
            // deleting it or writing defaults could erase a hidden restore intent.
            repository.cancel(sid)
            repository.updateOptions(sid) { it.copy(importGroups = false) }

            val session = assertNotNull(db.importDao().sessionById(sid), "the unreadable row is left alone")
            assertEquals("{corrupt", session.optionsJson)
            assertTrue(payload.exists(), "its payload stays with it")

            // Observations still surface the row — with defaults, never a crash.
            val view = assertNotNull(repository.observeSession(sid).first())
            assertEquals(ImportOptions(), view.options)
        }

    // --- Helpers --------------------------------------------------------------------------------------

    private suspend fun assertCreate(): Long {
        val outcome = repository.create(ImportSource(URI, "a.opml"), CreateImportOptions())
        return assertIs<Outcome.Success<Long>>(outcome).value
    }

    private suspend fun optionsOf(sessionId: Long): ImportOptions =
        ImportOptionsJson.decode(
            requireNotNull(db.importDao().sessionById(sessionId)).optionsJson!!,
        )

    private suspend fun seedGroup(name: String): Long =
        db.groupDao().insert(
            PodcastGroupEntity(
                uuid = Uuid.random().toString(),
                name = name,
                nameKey = (GroupNames.validate(name) as GroupNames.Validation.Valid).nameKey,
                orderKey = TestOrderKeys.after(db.groupDao().lastGroupOrderKey()),
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )

    /**
     * `PodcastRepository` reads/records through [FakePodcastRepository] but runs the real
     * `deleteCascade` for `unsubscribe` — `remove`'s `import_item` FK behaviour only exists
     * when the podcast rows truly go.
     */
    private inner class CascadingPodcasts : PodcastRepository by podcasts {
        override suspend fun unsubscribe(
            podcastIds: List<Long>,
            origin: ChangeOrigin,
        ): List<Long> {
            val removed = podcastIds.filter { db.podcastDao().byId(it) != null }
            for (id in removed) db.podcastDao().deleteCascade(id, clock.now())
            podcasts.unsubscribe(podcastIds, origin)
            return removed
        }
    }

    private companion object {
        const val URI = "content://provider/import.opml"
    }
}
