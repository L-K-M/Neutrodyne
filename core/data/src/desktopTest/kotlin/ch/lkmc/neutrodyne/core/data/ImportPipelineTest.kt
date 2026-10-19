// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.ImportWarningJson
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.HostileInput
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeImportReportPoster
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ImportSessions.create` end to end (05 §1–§5): real `PayloadStore` + real `XmlPullOpmlReader`
 * + real Room. The corpus previews are the AC1 rows; AC7 covers already-subscribed items, and
 * the error cases prove the taxonomy lands on the right `ImportError`.
 *
 * Sessions assert `state = PREVIEW`, `sourceFormat`, `payloadPath` under `import/`, options and
 * warnings JSON — and that no `podcast`/`podcast_group` row exists before confirmation.
 */
class ImportPipelineTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var root: File
    private lateinit var payloads: FakePayloadSource
    private lateinit var store: PayloadStore
    private lateinit var sessions: ImportSessions

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-import-pipeline")
                .toFile()
        payloads = FakePayloadSource()
        store = newStore(Dispatchers.Default)
        sessions = newSessions(db, appScope, Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        appScope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun allNewFormatsCreateDurablePreviewsAndSuggestGroupsOnlyForAllYouTubeLists() =
        runTest {
            val id = "UCBJycsmduvYEL83R_U4JriQ"
            val cases =
                listOf(
                    ImportFormat.NEWPIPE_JSON to """{"subscriptions":[{"service_id":0,"url":"$id","name":"Tech"}]}""",
                    ImportFormat.LIBRETUBE_JSON to (
                        """{"format":"Piped","localSubscriptions":[{"channelId":"$id"}],""" +
                            """"groups":[{"groupName":"Tech","channels":["$id"]}]}"""
                    ),
                    ImportFormat.TAKEOUT_CSV to "Id,Url,Title\n$id,ignored,Tech\n",
                    ImportFormat.URL_LIST to "@$id\n",
                )
            for ((format, text) in cases) {
                val uri = "content://w5/${format.name}"
                payloads.register(uri, bytes = text.encodeToByteArray())
                val created = sessions.create(ImportSource(uri, null), CreateImportOptions())
                val sid = assertIs<Outcome.Success<Long>>(created).value
                val session = db.importDao().sessionById(sid)!!
                assertEquals(format, session.sourceFormat)
                assertEquals(ImportState.PREVIEW, session.state)
                val options = ImportOptionsJson.decode(session.optionsJson!!)
                assertEquals(if (format == ImportFormat.LIBRETUBE_JSON) null else "YouTube", options.targetGroupName)
                assertEquals(1, options.itemYouTubeInputs.size)
                assertEquals(1, options.itemYouTubeVariants.values.single())
            }
            payloads.register("content://w5/mixed", bytes = "$id\nhttps://rss.example/feed\n".encodeToByteArray())
            val mixedCreated = sessions.create(ImportSource("content://w5/mixed", null), CreateImportOptions())
            val mixed = assertIs<Outcome.Success<Long>>(mixedCreated).value
            assertNull(ImportOptionsJson.decode(db.importDao().sessionById(mixed)!!.optionsJson!!).targetGroupName)
            assertTrue(db.podcastDao().allForExport().isEmpty())
        }

    @Test
    fun reopeningOldPreviewRecoversHtmlInputButHistoricalReportsStayUntouched() =
        runTest {
            val xml = """<opml version="2.0"><body><outline xmlUrl="invalid"
            htmlUrl="https://www.youtube.com/@channel" title="Title"/></body></opml>"""
            payloads.register("content://w5/old-preview", bytes = xml.encodeToByteArray())
            val created = sessions.create(ImportSource("content://w5/old-preview", null), CreateImportOptions())
            val sid = assertIs<Outcome.Success<Long>>(created).value
            val item = db.importDao().itemsOf(sid).single()
            db.importDao().updateItem(item.copy(status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET, selected = false))
            db.importDao().setOptionsJson(sid, "{}")
            sessions.upgradePreview(sid)
            assertEquals(
                ImportItemStatus.PREVIEW,
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .status,
            )
            assertEquals(
                "https://www.youtube.com/@channel",
                ImportOptionsJson
                    .decode(
                        db.importDao().sessionById(sid)!!.optionsJson!!,
                    ).itemYouTubeInputs[item.ordinal],
            )
            db.importDao().updateItem(item.copy(status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET, selected = false))
            db.importDao().setState(sid, ImportState.FETCHING)
            db.importDao().markDone(sid, clock.now())
            sessions.upgradePreview(sid)
            assertEquals(
                ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .status,
            )
        }

    @Test
    fun oldPreviewWithoutPayloadRetainsOriginalVariantHint() =
        runTest {
            val input = "https://www.youtube.com/feeds/videos.xml?playlist_id=UUSHBJycsmduvYEL83R_U4JriQ"
            val canonical = "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"
            val item =
                importItem(
                    0,
                    input,
                    normalizedUrl = canonical,
                    kind = ImportItemKind.YOUTUBE,
                    status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                )
            val sid = db.seedImportSession(state = ImportState.PREVIEW, items = listOf(item))
            sessions.upgradePreview(sid)
            val options = ImportOptionsJson.decode(db.importDao().sessionById(sid)!!.optionsJson!!)
            assertEquals(2, options.itemYouTubeVariants[0])
            assertTrue(
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .selected,
            )
        }

    // --- AC1 corpus previews ----------------------------------------------------------------------

    @Test
    fun antennapodFlatAtomPreviews() =
        runTest {
            val (session, items) = create("antennapod_flat_atom")

            assertSession(session, ImportFormat.OPML, "antennapod_flat_atom.opml")
            assertEquals(2, items.size)
            items[0].assertItem(
                title = "Accidental Tech Podcast",
                originalUrl = "https://atp.fm/rss",
                normalizedUrl = "https://atp.fm/rss",
                selected = true,
            )
            items[1].assertItem(
                title = "The Daily",
                originalUrl = "https://feeds.example.com/daily",
                selected = true,
            )
            assertNoLibraryRows()
        }

    @Test
    fun pocketcastsWrapperProposalIsPrefilledExcluded() =
        runTest {
            val (session, items) = create("pocketcasts_feeds_wrapper")

            val options = optionsOf(session)
            assertEquals(true, options.importGroups)
            assertEquals(setOf("feeds"), options.excludedGroupKeys)
            val group = options.sourceGroups.single()
            assertEquals("feeds", group.nameKey)
            assertEquals("wrapper", group.excludedReason)

            assertEquals(2, items.size)
            assertTrue(items.all { it.status == ImportItemStatus.PREVIEW })
            assertEquals(listOf("feeds"), groupNamesOf(items[0]))
        }

    @Test
    fun overcastBasicPreselectHonoured() =
        runTest {
            val (session, items) = create("overcast_basic")

            assertEquals(2, items.size)
            assertTrue(items[0].selected)
            // The file opted this entry out of import — 05 §3's preselect flag lands verbatim.
            assertTrue(!items[1].selected)
            assertTrue(items.all { it.status == ImportItemStatus.PREVIEW })
        }

    @Test
    fun overcastExtendedCarriesIgnoredOutlinesWarning() =
        runTest {
            val (session, items) = create("overcast_extended")

            assertEquals(2, items.size)
            val warnings = ImportWarningJson.decodeAll(requireNotNull(session.warningsJson))
            assertTrue(warnings.any { it.code == "IGNORED_OUTLINES" })
        }

    @Test
    fun gpodderSectionsAreExcludedProposals() =
        runTest {
            val (session, items) = create("gpodder_sections")

            val options = optionsOf(session)
            assertEquals(setOf("audio", "video"), options.excludedGroupKeys)
            assertTrue(
                options.sourceGroups.all { it.excludedReason == "gpodder_section" },
                "gPodder sections pre-fill the exclusion set (05 §4)",
            )
            assertEquals(3, items.size)
            assertEquals(listOf("Audio"), groupNamesOf(items[0]))
            assertEquals(listOf("Video"), groupNamesOf(items[2]))
        }

    @Test
    fun freshrssNestedKeepsOnlyTheWrapperExcluded() =
        runTest {
            val (session, items) = create("freshrss_nested")

            val options = optionsOf(session)
            assertEquals(setOf("tech"), options.excludedGroupKeys)
            assertEquals(setOf("tech", "blogs"), options.sourceGroups.mapTo(HashSet()) { it.nameKey })
            assertEquals(listOf("Blogs"), groupNamesOf(items[0]))
            assertEquals(listOf("Tech"), groupNamesOf(items[1]))
        }

    @Test
    fun googleBrokenStillPreviews() =
        runTest {
            val (session, items) = create("google_broken")

            assertEquals(2, items.size)
            assertTrue(items.all { it.status == ImportItemStatus.PREVIEW })
            assertEquals("Tom & Jerry", items[0].title)
        }

    @Test
    fun neutrodyneHybridRoutesYouTube() =
        runTest {
            val (session, items) = create("neutrodyne_hybrid")

            assertEquals(4, items.size)
            val channel = items[1]
            assertEquals(ImportItemKind.YOUTUBE, channel.kind)
            assertEquals(ImportItemStatus.PREVIEW, channel.status)
            assertEquals(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
                channel.normalizedUrl,
            )
            assertTrue(channel.selected)

            val options = optionsOf(session)
            // Our own export's proposal set round-trips with look fields intact.
            val tech = options.sourceGroups.single { it.nameKey == "tech" }
            assertEquals(-22016, tech.colorArgb)
            assertEquals("code", tech.iconKey)
            assertEquals(listOf("tech", "news"), groupNamesOf(items[0]))
            assertTrue(options.excludedGroupKeys.isEmpty())
        }

    @Test
    fun neutrodyneFlatPreviewsWithGroups() =
        runTest {
            val (session, items) = create("neutrodyne_flat")

            assertEquals(3, items.size)
            assertTrue(items.all { it.status == ImportItemStatus.PREVIEW })
            assertEquals(listOf("tech", "news"), groupNamesOf(items[0]))
        }

    // --- AC7: already-subscribed ------------------------------------------------------------------

    @Test
    fun alreadySubscribedEntriesAreMembershipOnly() =
        runTest {
            // The library already holds atp.fm and the daily by alias — both entries become
            // ALREADY_SUBSCRIBED rows instead of subscription candidates (AC7).
            val podcastId =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedUrl = "https://atp.fm/rss", feedKey = "atp.fm/rss"),
                )
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "feeds.example.com/daily",
                    podcastId = podcastId,
                    reason = AliasReason.SUBSCRIBE_INPUT,
                    addedAt = clock.now(),
                ),
            )

            val (_, items) = create("antennapod_flat_atom")

            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, items[0].status)
            assertEquals(podcastId, items[0].podcastId)
            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, items[1].status)
            assertEquals(podcastId, items[1].podcastId)
            assertTrue(items.none { it.selected })
        }

    // --- Error mapping ----------------------------------------------------------------------------

    @Test
    fun entityDeclarationIsHostile() =
        runTest {
            registerBytes(
                HOSTILE_URI,
                """<?xml version="1.0"?>
                <!DOCTYPE opml [<!ENTITY boom "x">]>
                <opml version="2.0"><body>
                <outline text="f" xmlUrl="https://a.example.com/rss"/>
                </body></opml>
                """.encodeToByteArray(),
            )

            assertEquals(
                ImportError.Hostile(HostileInput.ENTITY),
                assertIs<Outcome.Failure<ImportError>>(createOutcome(HOSTILE_URI)).error,
            )
            assertTrue(noSessions())
        }

    @Test
    fun deepNestingIsHostile() =
        runTest {
            val deep =
                buildString {
                    append("""<?xml version="1.0"?><opml version="2.0"><body>""")
                    repeat(40) { append("""<outline text="f$it">""") }
                    append("""<outline text="f" xmlUrl="https://a.example.com/rss"/>""")
                    repeat(40) { append("</outline>") }
                    append("</body></opml>")
                }
            registerBytes(HOSTILE_URI, deep.encodeToByteArray())

            assertEquals(
                ImportError.Hostile(HostileInput.TOO_DEEP),
                assertIs<Outcome.Failure<ImportError>>(createOutcome(HOSTILE_URI)).error,
            )
        }

    @Test
    fun oversizedPayloadIsTooLarge() =
        runTest {
            payloads.register(OVERSIZE_URI, source = EndlessSource())

            assertEquals(
                ImportError.TooLarge,
                assertIs<Outcome.Failure<ImportError>>(createOutcome(OVERSIZE_URI)).error,
            )
            assertTrue(noSessions())
        }

    @Test
    fun unrecognizedJsonIsUnsupported() =
        runTest {
            registerBytes(JSON_URI, """{"some": "json"}""".encodeToByteArray())

            assertEquals(
                ImportError.Unsupported(UnsupportedSource.JSON),
                assertIs<Outcome.Failure<ImportError>>(createOutcome(JSON_URI)).error,
            )
        }

    @Test
    fun emptyDocumentIsEmpty() =
        runTest {
            registerBytes(
                EMPTY_URI,
                """<?xml version="1.0"?><opml version="2.0"><body></body></opml>""".encodeToByteArray(),
            )

            assertEquals(
                ImportError.Empty,
                assertIs<Outcome.Failure<ImportError>>(createOutcome(EMPTY_URI)).error,
            )
        }

    @Test
    fun backupPayloadCreatesADatalessSession() =
        runTest {
            // 05 Full backup: a valid manifest routes the payload to the restore preview;
            // the session persists with no import_item rows (05's backup pipeline reads it).
            val backup =
                File(root, "snapshot.zip").also { file ->
                    ZipArchiveWriter().create(file.absolutePath.toPath()).use { writer ->
                        writer.entry("manifest.json").buffer().use { sink ->
                            sink.writeUtf8("""{"format":"neutrodyne-backup","formatVersion":1}""")
                        }
                    }
                }
            payloads.register(BACKUP_URI, file = backup)

            val sessionId =
                assertIs<Outcome.Success<Long>>(createOutcome(BACKUP_URI)).value

            val session = db.importDao().sessionById(sessionId)
            assertNotNull(session)
            assertEquals(ImportFormat.NEUTRODYNE_BACKUP, session.sourceFormat)
            assertEquals(ImportState.PREVIEW, session.state)
            assertTrue(db.importDao().itemsOf(sessionId).isEmpty())
            assertPayloadExists(session)
        }

    // --- Atomicity / detachment -------------------------------------------------------------------

    @Test
    fun failedPublishRollsBackTheSession() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            // The first session gets id 1; a directory at its publish name makes the rename
            // inside the write transaction fail → the session insert must roll back (02).
            FileSystem.SYSTEM.createDirectories(root.resolve("cache/import/1.bin").absolutePath.toPath())

            val failure = assertIs<Outcome.Failure<ImportError>>(createOutcome(URI))

            assertEquals(ImportError.Storage, failure.error)
            assertTrue(noSessions())
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun callerCancellationStillPersistsTheSession() =
        runTest {
            // 05 §1 step 5: the stage job lives on the application scope — a caller that goes
            // away mid-flight still leaves the persisted session for the resume banner. A
            // single-thread db dispatcher parked behind a latch holds the stage job mid-flight,
            // so the cancel provably lands while the detached job is alive.
            val gate = java.util.concurrent.CountDownLatch(1)
            val dbThread =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            val stageDb = TestDb.inMemory(clock = clock, queryContext = dbThread.asCoroutineDispatcher())
            val stageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val detachedSessions =
                ImportSessions(
                    db = stageDb,
                    payloads = newStore(UnconfinedTestDispatcher(testScheduler)),
                    classifier = ImportClassifier(stageDb, YouTubeUrlClassifier()),
                    opmlReader = XmlPullOpmlReader.discovered(),
                    archives = ZipGuard(),
                    clock = clock,
                    reports = FakeImportReportPoster(),
                    appScope = stageScope,
                    io = Dispatchers.Default,
                )
            // Occupy the only db thread: the stage job parks at its first query until released.
            dbThread.submit { gate.await() }
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))

            val job = launch { detachedSessions.create(ImportSource(URI, "a.opml"), CreateImportOptions()) }
            testScheduler.runCurrent()
            job.cancel()
            gate.countDown()

            // Real threads finish the detached job; poll the row on the same dispatcher.
            val session =
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        var row: ImportSessionEntity? = null
                        while (row == null) {
                            row = stageDb.importDao().sessionById(1)
                            if (row == null) kotlinx.coroutines.delay(20)
                        }
                        row
                    }
                }
            stageDb.close()
            stageScope.cancel()
            dbThread.shutdown()

            assertNotNull(session, "the cancelled caller's session still persisted")
            assertEquals(ImportState.PREVIEW, session.state)
        }

    @Test
    fun obstructedImportCacheFailsCreateWithStorage() =
        runTest {
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            File(root, "cache").writeText("not a directory")

            val failure = assertIs<Outcome.Failure<ImportError>>(createOutcome(URI))

            assertEquals(ImportError.Storage, failure.error)
            assertTrue(noSessions())
        }

    @Test
    fun deadAppScopeSweepsTheStagedCopy() =
        runTest {
            // Teardown between acquire and the stage job's first step: the job never runs, so
            // the staged copy belongs to no one and must not linger in the import cache.
            val deadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            deadScope.cancel()
            val deadSessions = newSessions(db, deadScope, Dispatchers.Default)
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))

            try {
                deadSessions.create(ImportSource(URI, "a.opml"), CreateImportOptions())
            } catch (_: CancellationException) {
                // Expected: the dead scope's stage job surfaces as cancellation.
            }

            assertTrue(noSessions())
            assertEquals(
                emptyList(),
                importDirFiles(),
                "the never-started stage job leaves no staged copy behind",
            )
        }

    @Test
    fun stageJobCancelledBeforePersistSweepsTheStagedCopy() =
        runTest {
            // A single db thread parked behind a gate holds the stage job inside classify;
            // cancelling the application scope there must still sweep the staged copy.
            val gate = java.util.concurrent.CountDownLatch(1)
            val dbExecutor =
                java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                )
            val stageDb =
                TestDb.inMemory(clock = clock, queryContext = dbExecutor.asCoroutineDispatcher())
            val stageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val stageSessions = newSessions(stageDb, stageScope, Dispatchers.Default)
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))
            dbExecutor.execute { gate.await() }

            val caller =
                launch {
                    try {
                        stageSessions.create(ImportSource(URI, "a.opml"), CreateImportOptions())
                    } catch (_: CancellationException) {
                        // Expected: the cancelled stage job surfaces as cancellation.
                    }
                }
            // The job's first db dispatch queueing behind the gate proves it is parked
            // inside stage() before any persistence; the poll runs in real time like the
            // caller-cancellation test above (testScheduler would skip the wait).
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(10_000) {
                    while (dbExecutor.queue.isEmpty()) delay(10)
                }
            }
            stageScope.cancel()
            gate.countDown()
            caller.join()

            assertNull(stageDb.importDao().sessionById(1), "nothing could have committed")
            assertEquals(
                emptyList(),
                importDirFiles(),
                "a stage job cancelled before persistence must sweep the staged copy",
            )
            stageDb.close()
            dbExecutor.shutdown()
        }

    @Test
    fun stageJobCancelledAtCommitKeepsSessionAndPayload() =
        runTest {
            // Room re-checks cancellation when a write transaction returns: a teardown landing
            // at COMMIT has already persisted the session — its payload must stay (main #153).
            val driver = CommitCancellingDriver()
            val stageDb = TestDb.inMemory(driver = driver, clock = clock)
            val stageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val stageSessions = newSessions(stageDb, stageScope, Dispatchers.Default)
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))

            driver.arm("INSERT OR ABORT INTO `import_session`") { stageScope.cancel() }
            val caller =
                launch {
                    try {
                        stageSessions.create(ImportSource(URI, "a.opml"), CreateImportOptions())
                    } catch (_: CancellationException) {
                        // Expected: the cancelled stage job surfaces as cancellation.
                    }
                }
            caller.join()

            assertEquals(1, driver.cancellations, "the commit hook fired")
            val session = assertNotNull(stageDb.importDao().sessionById(1))
            val payload = File(root, "cache").resolve(requireNotNull(session.payloadPath))
            assertTrue(payload.exists(), "a committed session's payload must survive")
            stageDb.close()
        }

    @Test
    fun stageJobCancelledInsideTheTransactionLeavesFilesConsistentWithTheRow() =
        runTest {
            // Cancel mid-transaction (the insert has stepped, publish and the remaining writes
            // have not run): whatever Room does with it — roll back or commit then throw — the
            // files on disk must match the row. A roll-back strands both `incoming-*.tmp` and
            // the already-renamed `import/{id}.bin`; a commit must keep the payload.
            val driver = CommitCancellingDriver()
            val stageDb = TestDb.inMemory(driver = driver, clock = clock)
            val stageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val stageSessions = newSessions(stageDb, stageScope, Dispatchers.Default)
            payloads.register(URI, file = opmlFixture("antennapod_flat_atom"))

            driver.armOnStep("INSERT OR ABORT INTO `import_session`") { stageScope.cancel() }
            val caller =
                launch {
                    try {
                        stageSessions.create(ImportSource(URI, "a.opml"), CreateImportOptions())
                    } catch (_: CancellationException) {
                        // Expected: the cancelled stage job surfaces as cancellation.
                    }
                }
            caller.join()

            val session = stageDb.importDao().sessionById(1)
            if (session == null) {
                assertEquals(
                    emptyList(),
                    importDirFiles(),
                    "a rolled-back insert leaves no staged or published payload",
                )
            } else {
                val payload = File(root, "cache").resolve(requireNotNull(session.payloadPath))
                assertTrue(payload.exists(), "a committed session keeps its payload")
            }
            stageDb.close()
        }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun newStore(io: kotlinx.coroutines.CoroutineDispatcher): PayloadStore =
        PayloadStore(
            storagePaths = storagePathsFor(root),
            fileSystem = FileSystem.SYSTEM,
            payloads = payloads,
            httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
            netErrors = testClassifier,
            io = io,
        )

    private fun newSessions(
        database: NeutrodyneDatabase,
        scope: CoroutineScope,
        io: kotlinx.coroutines.CoroutineDispatcher,
    ): ImportSessions =
        ImportSessions(
            db = database,
            payloads = store,
            classifier = ImportClassifier(database, YouTubeUrlClassifier()),
            opmlReader = XmlPullOpmlReader.discovered(),
            archives = ZipGuard(),
            clock = clock,
            reports = FakeImportReportPoster(),
            appScope = scope,
            io = io,
        )

    private fun registerBytes(
        uri: String,
        bytes: ByteArray,
    ) {
        payloads.register(uri, bytes = bytes)
    }

    private suspend fun createOutcome(uri: String): Outcome<Long, ImportError> =
        sessions.create(ImportSource(uri, "$uri.opml"), CreateImportOptions())

    private suspend fun create(fixture: String): Pair<ImportSessionEntity, List<ImportItemEntity>> {
        payloads.register(URI, file = opmlFixture(fixture))
        val sessionId = assertIs<Outcome.Success<Long>>(createOutcome(URI)).value
        val session = assertNotNull(db.importDao().sessionById(sessionId))
        assertPayloadExists(session)
        return session to db.importDao().itemsOf(sessionId)
    }

    private fun assertSession(
        session: ImportSessionEntity,
        format: ImportFormat,
        sourceName: String,
    ) {
        assertEquals(ImportState.PREVIEW, session.state)
        assertEquals(format, session.sourceFormat)
        assertEquals(sourceName, session.sourceName)
        assertTrue(!session.recoveredBySalvage)
        assertTrue(session.payloadPath?.startsWith("import/") == true)
        assertNull(session.finishedAt)
    }

    private fun assertPayloadExists(session: ImportSessionEntity) {
        val path = root.resolve("cache").absolutePath.toPath() / requireNotNull(session.payloadPath)
        assertTrue(FileSystem.SYSTEM.exists(path), "payload ${session.payloadPath} is on disk")
    }

    private fun optionsOf(session: ImportSessionEntity) = ImportOptionsJson.decode(requireNotNull(session.optionsJson))

    private fun groupNamesOf(item: ImportItemEntity): List<String> =
        Json.decodeFromString<List<String>>(item.groupNamesJson)

    private suspend fun noSessions(): Boolean =
        db
            .importDao()
            .observeOpenSessions()
            .first()
            .isEmpty()

    /** Every file left under `<cache>/import/` — staged temps and published payloads alike. */
    private fun importDirFiles(): List<String> =
        FileSystem.SYSTEM
            .listOrNull(File(root, "cache/import").absolutePath.toPath())
            .orEmpty()
            .map { it.name }

    private suspend fun assertNoLibraryRows() {
        assertTrue(db.podcastDao().allForExport().isEmpty(), "no podcast row before confirmation")
        assertTrue(
            db
                .groupDao()
                .observeGroups()
                .first()
                .isEmpty(),
            "no group row before confirmation",
        )
    }

    private fun ImportItemEntity.assertItem(
        title: String,
        originalUrl: String,
        normalizedUrl: String? = originalUrl,
        selected: Boolean,
    ) {
        assertEquals(title, this.title)
        assertEquals(originalUrl, this.originalUrl)
        assertEquals(normalizedUrl, this.normalizedUrl)
        assertEquals(ImportItemKind.RSS, kind)
        assertEquals(ImportItemStatus.PREVIEW, status)
        assertEquals(selected, this.selected)
        assertNull(podcastId)
        assertNull(errorDetail)
    }

    private companion object {
        const val URI = "content://provider/import.opml"
        const val HOSTILE_URI = "content://provider/hostile.opml"
        const val OVERSIZE_URI = "content://provider/huge.opml"
        const val JSON_URI = "content://provider/data.json"
        const val EMPTY_URI = "content://provider/empty.opml"
        const val BACKUP_URI = "content://provider/snapshot.zip"
    }
}
