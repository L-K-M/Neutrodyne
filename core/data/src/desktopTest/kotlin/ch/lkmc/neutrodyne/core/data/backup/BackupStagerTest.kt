// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.MAX_IMPORT_PAYLOAD_BYTES
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.backup.CredentialV1
import ch.lkmc.neutrodyne.feeds.backup.EntryInfo
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.GroupV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.buffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 05 Validation and preview (M3 B12a) over the real `ZipGuard`/`ZipArchiveWriter`/
 * `BackupCodec` and a Room database: writer round trips, manifest gates (format,
 * `minReaderVersion`, duplicates, required names), per-entry byte/SHA-256 checks, the
 * codec caps, N3's content validation (userinfo, `source`, credential origins), a preview
 * built from decoded content plus the `matchedLocalIds` dry run, and the lifetime rules
 * (failure/cancellation clean only the owned directory; the source archive survives).
 */
class BackupStagerTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val secrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val scheduler = FakeRefreshScheduler()
    private val fileSystem = FileSystem.SYSTEM
    private val root = createTempDirectory("nd-backup-stager").toString().toPath()
    private val stage = root / "stage"
    private val codec = BackupCodec()
    private var nextArchive = 0

    private val merger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, scheduler, clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = TestOrderKeys,
            clock = clock,
        )

    private val stager = BackupStager(db, ZipGuard(), merger, fileSystem, Dispatchers.IO)

    @AfterTest fun close() = db.close()

    // --- Round trips over the real writer ------------------------------------------------------

    @Test
    fun manualArchiveInspectsWithRealPreview() =
        runTest {
            val podcast = seedPodcast("https://example.com/show.xml")
            val group = db.groupDao().insert(groupEntity(uuid = GROUP_UUID, name = "News", orderKey = "g1"))
            db.groupDao().insertMember(memberEntity(group, podcast, "m1"))
            val played = seedEpisode(podcast, "ep-played")
            val started = seedEpisode(podcast, "ep-started")
            val plain = seedEpisode(podcast, "ep-plain")
            db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = 10L, updatedAt = 20L))
            db.episodeStateDao().upsert(episodeStateEntity(started, updatedAt = 30L) { copy(startedAt = 25L) })
            db.queueDao().insert(queueEntryEntity(plain, "q1"))

            val archive = write(BackupKind.MANUAL)

            assertSuccess(stager.inspect(archive, stage)).use {
                assertEquals(1, it.preview.podcasts)
                assertEquals(1, it.preview.groups)
                assertEquals(1, it.preview.played)
                assertEquals(1, it.preview.inProgress)
                assertEquals(1, it.preview.upNext)
                assertFalse(it.preview.includesCredentials)
                assertFalse(it.preview.automatic)
                assertEquals(0, it.preview.localPodcastsNotInBackup)
                assertEquals(0, it.preview.localGroupsNotInBackup)
                assertTrue(it.preview.warnings.isEmpty())
                assertEquals(3, it.episodeLines)
                assertEquals(0, it.skippedLines)
            }
            assertTrue(fileSystem.list(stage).isEmpty())
            assertTrue(fileSystem.exists(archive))
        }

    @Test
    fun snapshotArchiveInspectsAsAutomatic() =
        runTest {
            seedPodcast("https://example.com/show.xml")
            val archive = write(BackupKind.AUTO_SNAPSHOT)

            assertSuccess(stager.inspect(archive, stage)).use {
                assertTrue(it.preview.automatic)
                assertEquals(BackupKind.AUTO_SNAPSHOT, it.manifest.kind)
            }
        }

    @Test
    fun credentialArchivePreviewFlagsRealCredentials() =
        runTest {
            val archive = write(BackupKind.MANUAL, includeCredentials = true)

            assertSuccess(stager.inspect(archive, stage)).use {
                assertTrue(it.preview.includesCredentials)
                assertEquals(
                    "https://supercast.tech",
                    it
                        .library()
                        .credentials
                        .single()
                        .origin,
                )
            }
        }

    // --- Manifest gates (05 steps 2–3) -----------------------------------------------------------

    @Test
    fun newerMinReaderVersionWinsOverUnknownFields() =
        runTest {
            val payloads = payloads()
            val archive =
                archiveOf(
                    payloads,
                    manifest =
                        manifestBytes(payloads) {
                            it.copy(minReaderVersion = BackupCodec.FORMAT_VERSION + 1)
                        }.mutateJson { it.dropLast(1) + ",\"newerField\":{\"deep\":true}}" },
                )

            val failure = assertFailure(stager.inspect(archive, stage))
            assertEquals(BackupError.NewerFormat(BackupCodec.FORMAT_VERSION + 1), failure)
            assertStageClean()
        }

    @Test
    fun malformedAndMissingManifest() =
        runTest {
            val payloads = payloads()

            var archive = target()
            writeArchive(archive, payloads, manifest = "{".encodeToByteArray())
            assertEquals(BackupError.Corrupt(BackupCodec.ENTRY_MANIFEST), assertFailure(stager.inspect(archive, stage)))

            archive = target()
            writeArchive(
                archive,
                payloads,
                manifest = manifestBytes(payloads).mutateJson { it.replace("\"neutrodyne-backup\"", "\"other\"") },
            )
            assertEquals(BackupError.NotABackup, assertFailure(stager.inspect(archive, stage)))

            archive = target()
            writeArchive(archive, payloads, manifest = null)
            assertEquals(BackupError.NotABackup, assertFailure(stager.inspect(archive, stage)))
            assertStageClean()
        }

    @Test
    fun manifestOverCapIsTooLarge() =
        runTest {
            val payloads = payloads()
            val archive = archiveOf(payloads, manifest = ByteArray((BackupCodec.MANIFEST_MAX_BYTES + 1).toInt()))
            assertEquals(BackupError.TooLarge, assertFailure(stager.inspect(archive, stage)))
            assertStageClean()
        }

    @Test
    fun missingRequiredEntries() =
        runTest {
            val payloads = payloads()

            // Declared but absent from the archive.
            var archive = target()
            writeArchive(archive, payloads - BackupCodec.ENTRY_EPISODES, manifest = manifestBytes(payloads))
            assertEquals(
                BackupError.Corrupt(BackupCodec.ENTRY_EPISODES),
                assertFailure(stager.inspect(archive, stage)),
            )

            // Present but undeclared: a required entry cannot be hash-verified.
            archive = target()
            writeArchive(
                archive,
                payloads,
                manifest =
                    manifestBytes(payloads) {
                        it.copy(entries = it.entries.filter { entry -> entry.name != BackupCodec.ENTRY_QUEUE })
                    },
            )
            assertEquals(
                BackupError.Corrupt(BackupCodec.ENTRY_QUEUE),
                assertFailure(stager.inspect(archive, stage)),
            )
            assertStageClean()
        }

    @Test
    fun duplicateAndNonWhitelistedDeclarations() =
        runTest {
            val payloads = payloads()

            var archive = target()
            writeArchive(
                archive,
                payloads,
                manifest =
                    manifestBytes(payloads) {
                        it.copy(entries = it.entries + it.entries.first())
                    },
            )
            assertEquals(BackupError.Corrupt(BackupCodec.ENTRY_MANIFEST), assertFailure(stager.inspect(archive, stage)))

            // `manifest.json` can never declare its own hash; a foreign name is never opened.
            for (name in listOf(BackupCodec.ENTRY_MANIFEST, "notes.txt", "../evil")) {
                archive = target()
                writeArchive(
                    archive,
                    payloads,
                    manifest =
                        manifestBytes(payloads) {
                            it.copy(entries = it.entries + EntryInfo(name, 0L, "0".repeat(64), 0))
                        },
                )
                assertEquals(
                    BackupError.Corrupt(BackupCodec.ENTRY_MANIFEST),
                    assertFailure(stager.inspect(archive, stage)),
                )
            }
            assertStageClean()
        }

    @Test
    fun byteAndHashMismatches() =
        runTest {
            val payloads = payloads()

            var archive = target()
            writeArchive(
                archive,
                payloads,
                manifest =
                    manifestBytes(payloads) {
                        it.copy(
                            entries =
                                it.entries.map { entry ->
                                    if (entry.name ==
                                        BackupCodec.ENTRY_LIBRARY
                                    ) {
                                        entry.copy(bytes = entry.bytes + 1)
                                    } else {
                                        entry
                                    }
                                },
                        )
                    },
            )
            assertEquals(BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY), assertFailure(stager.inspect(archive, stage)))

            archive = target()
            writeArchive(
                archive,
                payloads,
                manifest =
                    manifestBytes(payloads) {
                        it.copy(
                            entries =
                                it.entries.map { entry ->
                                    if (entry.name ==
                                        BackupCodec.ENTRY_SETTINGS
                                    ) {
                                        entry.copy(sha256 = "0".repeat(64))
                                    } else {
                                        entry
                                    }
                                },
                        )
                    },
            )
            assertEquals(BackupError.Corrupt(BackupCodec.ENTRY_SETTINGS), assertFailure(stager.inspect(archive, stage)))
            assertStageClean()
        }

    @Test
    fun unknownArchiveEntriesAreIgnoredNotExtracted() =
        runTest {
            val payloads = payloads()
            val archive =
                archiveOf(
                    payloads,
                    manifest = manifestBytes(payloads),
                    extras = mapOf("notes.txt" to "hello".encodeToByteArray()),
                )

            assertSuccess(stager.inspect(archive, stage)).use {
                assertEquals(1, it.ignoredEntries)
                assertEquals(
                    1,
                    it.preview.warnings
                        .single { it.code == "IGNORED_ENTRIES" }
                        .count,
                )
            }
            // The ignored name never became a path under the stage root.
            assertTrue(fileSystem.list(stage).isEmpty())
        }

    // --- Codec caps and episode streaming ---------------------------------------------------------

    @Test
    fun oversizedAndUndecodableLinesAreCountedNotFatal() =
        runTest {
            val good = codec.encodeEpisodeLine(EpisodeLineV1(p = KEY, k = "ep-1", ts = 5))
            val fat = "x".repeat((BackupCodec.EPISODE_LINE_MAX_BYTES + 1).toInt())
            val episodes = "$good\n$fat\n{not json\n".encodeToByteArray()
            val payloads = payloads(episodes = episodes)
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))

            assertSuccess(stager.inspect(archive, stage)).use {
                assertEquals(1, it.episodeLines)
                assertEquals(2, it.skippedLines)
                assertEquals(
                    2,
                    it.preview.warnings
                        .single { it.code == "SKIPPED_LINES" }
                        .count,
                )
            }
        }

    @Test
    fun episodeLinesReopenPerPass() =
        runTest {
            val payloads = payloads()
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))

            assertSuccess(stager.inspect(archive, stage)).use {
                val first = buildList { it.forEachEpisodeLine { line -> add(line.k) } }
                val second = buildList { it.forEachEpisodeLine { line -> add(line.k) } }
                assertEquals(first, second)
                assertEquals(1, it.episodeLines)
            }
        }

    // --- N3 content validation (hash-verified bytes stay untrusted) --------------------------------

    @Test
    fun userinfoInIdentityFieldsIsRejected() =
        runTest {
            for (
            library in
            listOf(
                library(podcast(KEY, feedUrl = "https://user:pass@example.com/show.xml")),
                library(podcast("user@example.com/show.xml")),
                library(podcast(KEY, link = "https://u:p@example.com/blog")),
                library(podcast(KEY, aliases = listOf("e:v@example.com/old"))),
            )
            ) {
                val archive = target()
                val payloads = payloads(library = library)
                writeArchive(archive, payloads, manifest = manifestBytes(payloads))
                assertEquals(
                    BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY),
                    assertFailure(stager.inspect(archive, stage)),
                )
            }
            assertStageClean()
        }

    @Test
    fun linkAndArtworkMustBeFetchableWithoutUserinfo() =
        runTest {
            for (url in listOf("javascript:x", "file:///etc/passwd", "https://u:p@/x")) {
                for (podcast in listOf(podcast(KEY, link = url), podcast(KEY).copy(artworkUrl = url))) {
                    val payloads = payloads(library = library(podcast))
                    val archive = archiveOf(payloads, manifest = manifestBytes(payloads))
                    assertEquals(
                        BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY),
                        assertFailure(stager.inspect(archive, stage)),
                        "link=${podcast.link}, artwork=${podcast.artworkUrl}",
                    )
                    assertStageClean()
                }
            }
        }

    @Test
    fun unknownSourceKindIsRejectedNotDefaultedToRss() =
        runTest {
            val bad = payloads(library = library(podcast(KEY).copy(source = "PEERTUBE")))
            assertEquals(
                BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY),
                assertFailure(stager.inspect(archiveOf(bad, manifest = manifestBytes(bad)), stage)),
            )

            val good = payloads(library = library(podcast(KEY).copy(source = SourceType.YOUTUBE_CHANNEL.name)))
            assertSuccess(stager.inspect(archiveOf(good, manifest = manifestBytes(good)), stage)).close()
            assertStageClean()
        }

    @Test
    fun nonFeedCredentialOriginsAreRejected() =
        runTest {
            for (bad in listOf("sync:deadbeef", "podcastindex", "https://u:p@example.com", "ftp://h")) {
                val payloads =
                    payloads(
                        library = library(podcast(KEY)).copy(credentials = listOf(CredentialV1(bad, "u", "p"))),
                    )
                assertEquals(
                    BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY),
                    assertFailure(stager.inspect(archiveOf(payloads, manifest = manifestBytes(payloads)), stage)),
                )
            }
            // The same grammar gates the per-podcast credentialOrigin marker.
            val payloads = payloads(library = library(podcast(KEY).copy(credentialOrigin = "podcastindex")))
            assertEquals(
                BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY),
                assertFailure(stager.inspect(archiveOf(payloads, manifest = manifestBytes(payloads)), stage)),
            )
            assertStageClean()
        }

    @Test
    fun malformedIdentitiesAreRejected() =
        runTest {
            for (
            library in
            listOf(
                library(podcast("not a key")),
                library(podcast(KEY, podcastGuid = " padded ")),
                library(podcast(KEY, syncId = "not-a-uuid")),
                library(podcast(KEY)).copy(groups = listOf(GroupV1("not-a-uuid", "G", 0))),
            )
            ) {
                val payloads = payloads(library = library)
                assertEquals(
                    BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY),
                    assertFailure(stager.inspect(archiveOf(payloads, manifest = manifestBytes(payloads)), stage)),
                )
            }
            assertStageClean()
        }

    // --- Preview facts come from decoded content (05 step 5) ----------------------------------------

    @Test
    fun previewIgnoresForgedManifestCounts() =
        runTest {
            val payloads = payloads()
            val archive =
                archiveOf(
                    payloads,
                    manifest =
                        manifestBytes(payloads) {
                            it.copy(counts = CountsV1(99, 99, 99, 99, 99, 99, 99), includesCredentials = true)
                        },
                )

            assertSuccess(stager.inspect(archive, stage)).use {
                assertEquals(1, it.preview.podcasts)
                assertEquals(0, it.preview.groups)
                assertEquals(1, it.preview.played)
                assertFalse(it.preview.includesCredentials)
            }
        }

    @Test
    fun localRemovalCountsMatchTheMerger() =
        runTest {
            val kept = seedPodcast("https://example.com/kept.xml")
            seedPodcast("https://example.com/gone1.xml")
            seedPodcast("https://example.com/gone2.xml")
            db.groupDao().insert(groupEntity(name = "LocalOnly", orderKey = "g1"))

            val payloads = payloads(library = library(podcast("example.com/kept.xml")))
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))

            val staged = assertSuccess(stager.inspect(archive, stage))
            assertEquals(2, staged.preview.localPodcastsNotInBackup)
            assertEquals(1, staged.preview.localGroupsNotInBackup)

            // The dry run and a real Replace agree: only the matched row survives.
            staged.use {
                merger.mergeLibrary(it, MergePolicy.RESTORE_REPLACE, RestoreCategory.entries.toSet())
            }
            assertEquals(listOf(kept), db.podcastDao().allForExport().map { it.id })
            assertTrue(db.groupDao().groupsInOrder().isEmpty())
        }

    @Test
    fun linkedDeviceSurfacesServerHost() =
        runTest {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example.com:8443",
                    accountId = "a",
                    deviceId = "d",
                    cursor = "c",
                    nodeId = "5eed5eed5eed5eed",
                    protocol = 1,
                    linkedAt = 1_700_000_000_000L,
                    lastSyncAt = 1_700_000_000_000L,
                ),
            )
            val payloads = payloads()
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))

            assertSuccess(stager.inspect(archive, stage)).use {
                assertEquals("sync.example.com:8443", it.preview.linkedServerHost)
            }
        }

    @Test
    fun inspectionWritesNothingToTheDatabase() =
        runTest {
            seedPodcast("https://example.com/show.xml")
            val payloads = payloads()
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))
            val before = db.podcastDao().allForExport()
            val syncBefore = db.syncStateDao().get()

            assertSuccess(stager.inspect(archive, stage)).close()

            assertEquals(before, db.podcastDao().allForExport())
            assertEquals(syncBefore, db.syncStateDao().get())
            assertTrue(secrets.podcastCredentialIds.value.isEmpty())
        }

    // --- Acquisition cap, hostile containers, lifetime ----------------------------------------------

    @Test
    fun payloadCapAppliesBeforeTheArchiveOpens() =
        runTest {
            val archive = target()
            fileSystem.sink(archive).buffer().use { it.write(ByteArray(MAX_IMPORT_PAYLOAD_BYTES.toInt() + 1)) }
            assertEquals(BackupError.TooLarge, assertFailure(stager.inspect(archive, stage)))
        }

    @Test
    fun nonArchiveAndMissingInputs() =
        runTest {
            val archive = target()
            fileSystem.sink(archive).buffer().use { it.writeUtf8("not a zip") }
            assertEquals(BackupError.Corrupt("zip"), assertFailure(stager.inspect(archive, stage)))
            assertEquals(BackupError.Io, assertFailure(stager.inspect(root / "absent.zip", stage)))
        }

    @Test
    fun failureRemovesOnlyTheOwnedStageDirectory() =
        runTest {
            fileSystem.createDirectories(stage)
            val decoy = stage / "decoy.txt"
            fileSystem.sink(decoy).buffer().use { it.writeUtf8("keep me") }
            val archive = target()
            val payloads = payloads()
            // A hash failure on the last declared entry leaves earlier entries staged.
            writeArchive(
                archive,
                payloads,
                manifest =
                    manifestBytes(payloads) { manifest ->
                        manifest.copy(
                            entries =
                                manifest.entries.map {
                                    if (it.name == BackupCodec.ENTRY_SETTINGS) it.copy(sha256 = "0".repeat(64)) else it
                                },
                        )
                    },
            )
            val before = fileSystem.source(archive).use { it.buffer().readByteArray() }

            assertFailure(stager.inspect(archive, stage))

            assertEquals(listOf(decoy), fileSystem.list(stage))
            assertContentEquals(before, fileSystem.source(archive).use { it.buffer().readByteArray() })
        }

    @Test
    fun unexpectedFailureAlsoRemovesPrivateStageFiles() =
        runTest {
            fileSystem.createDirectories(stage)
            val decoy = stage / "decoy.txt"
            fileSystem.write(decoy) { writeUtf8("keep me") }
            val failing =
                object : ForwardingFileSystem(fileSystem) {
                    override fun sink(
                        file: Path,
                        mustCreate: Boolean,
                    ): Sink {
                        if (file.name == BackupCodec.ENTRY_QUEUE) error("unexpected stage failure")
                        return super.sink(file, mustCreate)
                    }
                }
            val payloads =
                payloads(
                    library =
                        library(podcast(KEY)).copy(
                            credentials = listOf(CredentialV1("https://example.com", "member", "secret")),
                        ),
                )
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))
            val before = fileSystem.read(archive) { readByteArray() }
            val failingStager = BackupStager(db, ZipGuard(), merger, failing, Dispatchers.IO)

            assertFailsWith<IllegalStateException> { failingStager.inspect(archive, stage) }

            assertEquals(listOf(decoy), fileSystem.list(stage), "private staged credentials must be removed")
            assertContentEquals(before, fileSystem.read(archive) { readByteArray() })
        }

    @Test
    fun cancellationDuringPreviewRemovesTheStage() =
        runTest {
            // The staged `episodes.jsonl` is opened only by the preview pass, so throwing on
            // its source lands the cancellation deterministically after staging succeeded.
            val cancelling =
                object : ForwardingFileSystem(fileSystem) {
                    override fun source(file: Path): Source {
                        if (file.name == BackupCodec.ENTRY_EPISODES) throw CancellationException()
                        return super.source(file)
                    }
                }
            val cancellable = BackupStager(db, ZipGuard(), merger, cancelling, Dispatchers.IO)
            val payloads = payloads()
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))

            assertFailsWith<CancellationException> { cancellable.inspect(archive, stage) }
            assertTrue(fileSystem.list(stage).isEmpty())
            assertTrue(fileSystem.exists(archive))
        }

    @Test
    fun closeRemovesOnlyTheStage() =
        runTest {
            val payloads = payloads()
            val archive = archiveOf(payloads, manifest = manifestBytes(payloads))

            val staged = assertSuccess(stager.inspect(archive, stage))
            assertEquals(1, fileSystem.list(stage).size)
            staged.close()
            staged.close() // idempotent
            assertTrue(fileSystem.list(stage).isEmpty())
            assertTrue(fileSystem.exists(archive))
        }

    // --- Helpers --------------------------------------------------------------------------------------

    private suspend fun write(
        kind: BackupKind,
        includeCredentials: Boolean = false,
    ): Path {
        if (includeCredentials) seedCredentialPodcast()
        val writer =
            BackupWriter(
                db,
                FakeSettingsRepository(),
                secrets,
                ZipArchiveWriter(),
                ZipGuard(),
                OpmlExporter(db, secrets, clock, Dispatchers.Default),
                testBuildInfo(),
                clock,
                fileSystem,
                Dispatchers.IO,
            )
        val archive = target()
        assertIs<Outcome.Success<*>>(writer.write(kind, archive, includeCredentials))
        return archive
    }

    /** A fresh archive name per call — the stage root never holds the source. */
    private fun target(): Path = root / "archive-${nextArchive++}.zip"

    /** Seeds with a canonical feedKey — what production rows (and the backup) carry. */
    private suspend fun seedPodcast(feedUrl: String): Long =
        db.podcastDao().insertPodcast(
            podcastEntity(feedUrl = feedUrl, feedKey = checkNotNull(UrlNormalizer.forIdentity(feedUrl))),
        )

    private suspend fun seedEpisode(
        podcastId: Long,
        identityKey: String,
    ): Long =
        db.ingestDao().insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = identityKey))).single()

    private suspend fun seedCredentialPodcast() {
        val origin = "https://supercast.tech"
        val credentialId =
            db.credentialDao().insert(
                CredentialEntity(
                    id = 0,
                    origin = origin,
                    username = "alice",
                    secretCipher = null,
                    iv = null,
                    createdAt = 1L,
                ),
            )
        val feedUrl = "$origin/feeds/member.xml"
        val podcast =
            db.podcastDao().insertPodcast(
                podcastEntity(feedUrl = feedUrl, feedKey = checkNotNull(UrlNormalizer.forIdentity(feedUrl))) {
                    copy(credentialId = credentialId)
                },
            )
        val secretId = checkNotNull(secrets.put(origin, "alice", "s3cret".toCharArray(), emptyMap()))
        secrets.podcastCredentialIds.value = secrets.podcastCredentialIds.value + (podcast to secretId)
    }

    private fun podcast(
        key: String,
        feedUrl: String = "https://$key",
        link: String? = null,
        aliases: List<String> = emptyList(),
        podcastGuid: String? = null,
        syncId: String? = null,
    ): PodcastV1 =
        PodcastV1(
            key = key,
            feedUrl = feedUrl,
            title = "Show",
            link = link,
            aliases = aliases,
            podcastGuid = podcastGuid,
            syncId = syncId,
            subscribedAt = 1_700_000_000_000L,
        )

    private fun library(vararg podcasts: PodcastV1): LibraryV1 = LibraryV1(podcasts.toList(), emptyList())

    private fun payloads(
        library: LibraryV1 = library(podcast(KEY)),
        episodes: ByteArray = defaultEpisodes(),
        queue: QueueV1 = QueueV1(upNext = emptyList()),
        settings: SettingsV1 = SettingsV1(values = emptyList()),
    ): Map<String, ByteArray> =
        mapOf(
            BackupCodec.ENTRY_LIBRARY to codec.encodeLibrary(library).encodeToByteArray(),
            BackupCodec.ENTRY_EPISODES to episodes,
            BackupCodec.ENTRY_QUEUE to codec.encodeQueue(queue).encodeToByteArray(),
            BackupCodec.ENTRY_SETTINGS to codec.encodeSettings(settings).encodeToByteArray(),
        )

    private fun defaultEpisodes(): ByteArray =
        (codec.encodeEpisodeLine(EpisodeLineV1(p = KEY, k = "ep-1", pl = 10L, ts = 20L)) + "\n").encodeToByteArray()

    /** The manifest every payload map needs: each entry's real size and SHA-256. */
    private fun manifestBytes(
        payloads: Map<String, ByteArray>,
        mutate: (BackupManifest) -> BackupManifest = { it },
    ): ByteArray = codec.encodeManifest(mutate(manifest(entryInfos(payloads)))).encodeToByteArray()

    private fun ByteArray.mutateJson(mutate: (String) -> String): ByteArray =
        mutate(decodeToString()).encodeToByteArray()

    private fun manifest(entries: List<EntryInfo>): BackupManifest =
        BackupManifest(
            createdAt = "2026-10-17T00:00:00Z",
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

    /** Writes a fresh archive under [root] and returns its path. */
    private fun archiveOf(
        payloads: Map<String, ByteArray>,
        manifest: ByteArray?,
        extras: Map<String, ByteArray> = emptyMap(),
    ): Path = target().also { writeArchive(it, payloads, manifest, extras) }

    private fun writeArchive(
        path: Path,
        payloads: Map<String, ByteArray>,
        manifest: ByteArray?,
        extras: Map<String, ByteArray> = emptyMap(),
    ) {
        ZipArchiveWriter().create(path).use { writer ->
            for (
            (name, bytes) in payloads + extras +
                listOfNotNull(
                    manifest?.let { BackupCodec.ENTRY_MANIFEST to it },
                )
            ) {
                writer.entry(name).buffer().use { it.write(bytes) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertSuccess(outcome: Outcome<T, BackupError>): T = (outcome as Outcome.Success<T>).value

    private fun assertFailure(outcome: Outcome<*, BackupError>): BackupError =
        (outcome as Outcome.Failure<BackupError>).error

    private fun assertStageClean() {
        if (!fileSystem.exists(stage)) return
        val leftovers = fileSystem.list(stage).filter { it.name.startsWith("backup-") }
        assertTrue(leftovers.isEmpty(), "staging directories leaked: $leftovers")
    }

    private companion object {
        const val KEY = "example.com/show.xml"
        const val GROUP_UUID = "3f6f9a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a"
    }
}
