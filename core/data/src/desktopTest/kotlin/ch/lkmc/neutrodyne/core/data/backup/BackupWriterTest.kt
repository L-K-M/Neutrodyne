// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.execSql
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.MAX_IMPORT_PAYLOAD_BYTES
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriter
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CredentialV1
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * 05 Writing's writer legs (M3 acceptance 5/10/11): the entry order and names, manifest hashes
 * and record counts over the exact inflated bytes, MANUAL vs AUTO_SNAPSHOT content, opt-in
 * credentials, the portable-settings whitelist, a 2,500+-episode keyset stream, BackupCodec +
 * ZipGuard readability of the produced file, a mid-write failure leaving the old target
 * untouched, and the process-wide mutex serialising concurrent writers.
 */
class BackupWriterTest {
    private val db = TestDb.inMemory()
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val clock = TestClock()
    private val exporter = OpmlExporter(db, secrets, clock, Dispatchers.Default)
    private val fileSystem = FileSystem.SYSTEM
    private val dir = createTempDirectory("nd-backup-writer").toString().toPath()
    private val codec = BackupCodec()

    @AfterTest
    fun close() {
        db.close()
        fileSystem.deleteRecursively(dir)
    }

    private fun newWriter(
        archives: ArchiveWriterFactory = ZipArchiveWriter(),
        fileSystem: FileSystem = this.fileSystem,
    ) = BackupWriter(
        db,
        settings,
        secrets,
        archives,
        ZipGuard(),
        exporter,
        testBuildInfo(),
        clock,
        fileSystem,
        Dispatchers.IO,
    )

    private fun target(name: String = "backup.zip"): Path = dir / name

    private fun reader(path: Path): ArchiveReader = ZipGuard().open(path, ArchiveCaps.BACKUP)

    private fun ArchiveReader.bytes(name: String): ByteArray = open(name).use { it.buffer().readByteArray() }

    private fun ArchiveReader.manifest(): BackupManifest =
        open(BackupCodec.ENTRY_MANIFEST).use { codec.decodeManifest(it) }

    private fun ArchiveReader.library(): LibraryV1 = open(BackupCodec.ENTRY_LIBRARY).use { codec.decodeLibrary(it) }

    private fun ArchiveReader.queue(): QueueV1 = open(BackupCodec.ENTRY_QUEUE).use { codec.decodeQueue(it) }

    private fun ArchiveReader.settings(): SettingsV1 = open(BackupCodec.ENTRY_SETTINGS).use { codec.decodeSettings(it) }

    private fun ArchiveReader.lines(): List<EpisodeLineV1> =
        codec.episodeLines(open(BackupCodec.ENTRY_EPISODES)).use { lines ->
            buildList {
                while (lines.hasNext()) add(lines.next())
            }
        }

    // --- Entry order and names (05 Writing's fixed order, manifest last) ----------------------

    @Test
    fun manualEntriesInWriteOrder() =
        runTest {
            seedPodcast()
            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target(), includeCredentials = false))
            reader(target()).use { reader ->
                assertEquals(
                    listOf(
                        BackupCodec.ENTRY_EPISODES,
                        BackupCodec.ENTRY_LIBRARY,
                        BackupCodec.ENTRY_QUEUE,
                        BackupCodec.ENTRY_SETTINGS,
                        BackupCodec.ENTRY_OPML,
                        BackupCodec.ENTRY_MANIFEST,
                    ),
                    reader.entryNames,
                )
            }
        }

    @Test
    fun snapshotEntriesDropOpml() =
        runTest {
            seedPodcast()
            assertIs<Outcome.Success<*>>(
                newWriter().write(BackupKind.AUTO_SNAPSHOT, target(), includeCredentials = false),
            )
            reader(target()).use { reader ->
                assertEquals(
                    listOf(
                        BackupCodec.ENTRY_EPISODES,
                        BackupCodec.ENTRY_LIBRARY,
                        BackupCodec.ENTRY_QUEUE,
                        BackupCodec.ENTRY_SETTINGS,
                        BackupCodec.ENTRY_MANIFEST,
                    ),
                    reader.entryNames,
                )
            }
        }

    @Test
    fun scheduledArchiveKeepsOpmlAndExcludesPasswords() =
        runTest {
            seedCredentialPodcast()

            assertIs<Outcome.Success<*>>(
                newWriter().write(BackupKind.SCHEDULED, target(), includeCredentials = true),
            )

            reader(target()).use { reader ->
                assertTrue(BackupCodec.ENTRY_OPML in reader.entryNames)
                assertEquals(BackupKind.SCHEDULED, reader.manifest().kind)
                assertFalse(reader.manifest().includesCredentials)
                assertTrue(reader.library().credentials.isEmpty())
                assertFalse(CREDENTIAL_PASS in String(reader.bytes(BackupCodec.ENTRY_OPML)))
            }
        }

    // --- Manifest integrity (05 Archive: bytes + sha256 + records per entry) -------------------

    @Test
    fun manifestMatchesWrittenEntryBytes() =
        runTest {
            val podcast = seedPodcast()
            val episode = seedEpisode(podcast)
            db.episodeStateDao().upsert(episodeStateEntity(episode, playedAt = 10L, updatedAt = 20L))
            db.queueDao().insert(queueEntryEntity(episode, "q1"))

            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target(), includeCredentials = false))
            reader(target()).use { reader ->
                val manifest = reader.manifest()
                assertEquals(
                    listOf(
                        BackupCodec.ENTRY_EPISODES,
                        BackupCodec.ENTRY_LIBRARY,
                        BackupCodec.ENTRY_QUEUE,
                        BackupCodec.ENTRY_SETTINGS,
                        BackupCodec.ENTRY_OPML,
                    ),
                    manifest.entries.map { it.name },
                )
                for (info in manifest.entries) {
                    val bytes = reader.bytes(info.name)
                    assertEquals(info.bytes, bytes.size.toLong(), "${info.name} byte count")
                    assertEquals(info.sha256, bytes.toByteString().sha256().hex(), "${info.name} sha256")
                    assertTrue(info.records > 0, "${info.name} record count")
                }
                assertEquals(1, manifest.counts.podcasts)
                assertEquals(1, manifest.counts.episodeLines)
                assertEquals(1, manifest.counts.played)
                assertEquals(0, manifest.counts.inProgress)
                assertEquals(1, manifest.counts.upNext)
                assertEquals(portableKeys().size, manifest.counts.settings)
            }
        }

    // --- MANUAL vs AUTO_SNAPSHOT (05 Writing; D33) ---------------------------------------------

    @Test
    fun snapshotNeverCarriesCredentialsOrOpml() =
        runTest {
            seedCredentialPodcast()

            // Even an opted-in flag cannot push passwords into a snapshot (D33).
            assertIs<Outcome.Success<*>>(
                newWriter().write(BackupKind.AUTO_SNAPSHOT, target(), includeCredentials = true),
            )
            reader(target()).use { reader ->
                assertFalse(BackupCodec.ENTRY_OPML in reader.entryNames)
                val library = reader.library()
                assertTrue(library.credentials.isEmpty())
                // `credentialOrigin` still travels so a restore flags `needsCredentials` (05).
                assertEquals(CREDENTIAL_ORIGIN, library.podcasts.single().credentialOrigin)
                assertFalse(reader.manifest().includesCredentials)
                assertEquals(BackupKind.AUTO_SNAPSHOT, reader.manifest().kind)
            }
        }

    @Test
    fun manualCredentialsOnlyOnOptIn() =
        runTest {
            seedCredentialPodcast()

            assertIs<Outcome.Success<*>>(
                newWriter().write(BackupKind.MANUAL, target("off.zip"), includeCredentials = false),
            )
            reader(target("off.zip")).use { reader ->
                val library = reader.library()
                assertTrue(library.credentials.isEmpty())
                assertEquals(CREDENTIAL_ORIGIN, library.podcasts.single().credentialOrigin)
                assertFalse(reader.manifest().includesCredentials)
            }

            assertIs<Outcome.Success<*>>(
                newWriter().write(BackupKind.MANUAL, target("on.zip"), includeCredentials = true),
            )
            reader(target("on.zip")).use { reader ->
                val library = reader.library()
                assertEquals(
                    listOf(CredentialV1(CREDENTIAL_ORIGIN, CREDENTIAL_USER, CREDENTIAL_PASS)),
                    library.credentials,
                )
                assertTrue(reader.manifest().includesCredentials)
                // The OPML never carries userinfo — passwords travel in library.json only (D33).
                assertFalse(CREDENTIAL_PASS in String(reader.bytes(BackupCodec.ENTRY_OPML)))
            }
        }

    // --- Settings whitelist (05 Settings whitelist) --------------------------------------------

    @Test
    fun portablePlaybackSettingsRoundTripWithoutDeviceKeys() =
        runTest {
            seedPodcast()
            settings.set(AppearanceSettingKeys.ARTWORK_TINT, false)
            settings.set(AppearanceSettingKeys.PURE_BLACK, true)
            settings.set(AppearanceSettingKeys.LIBRARY_DENSITY, LibraryDensity.LARGE)
            settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, "source-group")
            settings.set(
                AppearanceSettingKeys.LIBRARY_SEGMENT,
                ch.lkmc.neutrodyne.core.model.settings.LibrarySegment.GROUPS,
            )
            settings.set(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN, true)
            settings.set(PlaybackSettingKeys.SPEED, 1.8f)
            settings.set(PlaybackSettingKeys.SKIP_SILENCE, true)
            settings.set(PlaybackSettingKeys.SPEED_PRESETS, "2.0,0.75,1.25")
            settings.set(PlaybackSettingKeys.SKIP_BACK_MS, 45_000L)
            settings.set(PlaybackSettingKeys.SKIP_FORWARD_MS, 90_000L)
            settings.set(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION, false)
            settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ASK)
            settings.set(PlaybackSettingKeys.STREAM_CACHE_MB, 1_000)
            val expected = settings.observePortableSnapshot().first()
            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target(), includeCredentials = false))
            reader(target()).use { reader ->
                val values = reader.settings().values
                val restored = FakeSettingsRepository()
                restored.set(AppearanceSettingKeys.LIBRARY_DENSITY, LibraryDensity.SMALL)
                restored.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, "device-group")
                assertIs<Outcome.Success<*>>(RestoreSettingsMerge(restored).apply(reader.settings()))
                assertEquals(expected, restored.observePortableSnapshot().first())
                assertEquals(false, restored.get(AppearanceSettingKeys.ARTWORK_TINT))
                assertEquals(true, restored.get(AppearanceSettingKeys.PURE_BLACK))
                assertEquals(LibraryDensity.LARGE, restored.get(AppearanceSettingKeys.LIBRARY_DENSITY))
                assertEquals("device-group", restored.get(AppearanceSettingKeys.LIBRARY_GROUP_FILTER))
                assertEquals(
                    ch.lkmc.neutrodyne.core.model.settings.LibrarySegment.PODCASTS,
                    restored.get(AppearanceSettingKeys.LIBRARY_SEGMENT),
                )
                assertEquals(false, restored.get(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN))
                for (key in listOf(
                    AppearanceSettingKeys.ARTWORK_TINT,
                    AppearanceSettingKeys.PURE_BLACK,
                    AppearanceSettingKeys.LIBRARY_DENSITY,
                    AppearanceSettingKeys.PLAYER_PANEL_HIDDEN,
                )) {
                    assertFalse(key.synced)
                }
                // Exactly the portable registry, nothing else — installation_id is DEVICE.
                assertEquals(portableKeys().toSet(), values.map { it.key }.toSet())
                assertEquals(portableKeys().size, values.size)
                assertFalse(BackupSettingKeys.INSTALLATION_ID.name in values.map { it.key })
                assertTrue(
                    values
                        .map { it.type }
                        .toSet()
                        .all { it in setOf("Bool", "Int32", "Int64", "Float32", "Text", "TextSet", "Choice") },
                )
            }
        }

    // --- Streaming scale (02 Backup export: keyset chunks of 1,000) -----------------------------

    @Test
    fun largeLibraryStreams() =
        runTest {
            val podcast = seedPodcast()
            val episodeCount = 2_600
            val ids =
                db.ingestDao().insertEpisodes(
                    List(episodeCount) { i -> episodeEntity(podcastId = podcast, identityKey = "ep-$i") },
                )
            // 2,597 qualify by a state row; the last 3 only by an Up next entry → `ts = 0` lines.
            val withState = ids.dropLast(3)
            for (chunk in withState.chunked(500)) {
                db.episodeStateDao().ensureAll(chunk, 100L)
            }
            db.episodeStateDao().markPlayed(withState.take(100), 200L)
            for (id in withState.drop(100).take(20)) {
                db.episodeStateDao().upsert(episodeStateEntity(id, updatedAt = 300L) { copy(startedAt = 250L) })
            }
            for (id in ids.takeLast(3)) {
                db.queueDao().insert(queueEntryEntity(id, "q$id"))
            }

            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target(), includeCredentials = false))
            reader(target()).use { reader ->
                val lines = reader.lines()
                assertEquals(episodeCount, lines.size)
                assertEquals(episodeCount, lines.map { it.k }.toSet().size)
                assertEquals(3, lines.count { it.ts == 0L })
                assertEquals(100, lines.count { it.pl != null })
                assertEquals(20, lines.count { it.pl == null && it.st != null })
                val counts = reader.manifest().counts
                assertEquals(episodeCount, counts.episodeLines)
                assertEquals(100, counts.played)
                assertEquals(20, counts.inProgress)
                assertEquals(3, counts.upNext)
            }
        }

    // --- Round trip: the archive decodes and carries the library's identities (AC5) -------------

    @Test
    fun archiveDecodesWithCodecAndZipGuard() =
        runTest {
            val podcast =
                seedPodcast("https://feeds.patreon.com/member-feed") {
                    copy(
                        podcastGuid = "urn:real:guid",
                        podcastGuidDerived = false,
                        customTitle = "Pinned",
                        link = "https://example.com/show",
                        artworkUrl = "https://example.com/art.jpg",
                        includeInAll = false,
                        episodeOrder = FeedOrder.OLDEST_FIRST,
                        syncId = "sync-pod-1",
                    )
                }
            seedPodcast { copy(podcastGuid = "urn:derived", podcastGuidDerived = true) }
            db
                .podcastDao()
                .insertAlias(
                    PodcastUrlAliasEntity("https://old.example.com/feed.xml", podcast, AliasReason.REDIRECT, 1L),
                )
            db
                .scopeSettingsDao()
                .upsertPodcast(
                    PodcastSettingsEntity(
                        podcast,
                        ScopeOverrides(
                            playbackSpeed = 1.5f,
                            autoDownload = true,
                            deleteAfterPlayed = DeleteAfter.AFTER_24H,
                        ),
                    ),
                )
            db.useWriterConnection {
                it.execSql(
                    "INSERT INTO episode_guid_provenance(podcastId, guid, knowledge)" +
                        " VALUES ($podcast, 'g:amb-1', 'KNOWN_AMBIGUOUS')",
                )
            }

            val group = db.groupDao().insert(groupEntity(uuid = "uuid-g1", name = "Commute", orderKey = "g1"))
            db.groupDao().insertMember(
                memberEntity(groupId = group, podcastId = podcast, orderKey = "m1", addedAt = 7L),
            )
            db
                .scopeSettingsDao()
                .upsertGroup(PodcastGroupSettingsEntity(group, ScopeOverrides(skipSilence = true)))

            val played = seedEpisode(podcast, "key-played") { copy(enclosureUrl = "https://cdn.example.com/a.mp3") }
            val queued = seedEpisode(podcast, "key-queued")
            db
                .episodeStateDao()
                .upsert(
                    episodeStateEntity(played, playedAt = 10L, updatedAt = 30L) {
                        copy(playCount = 2, lastPlayedAt = 20L, isFavorite = true, measuredDurationMs = 61_000L)
                    },
                )
            db.positionDao().upsert(EpisodePositionEntity(played, 12_000L, 61_000L, PositionSource.STREAM, 25L))
            db.downloadDao().insert(downloadEntity(episodeId = queued, state = DownloadState.COMPLETED))
            db.queueDao().insert(queueEntryEntity(queued, "q1"))
            // `play_session` is an `id = 0` singleton `ensure`d at onCreate — a session is an
            // UPDATE, never an insert.
            db.playSessionDao().ensureNow(1L)
            db.useWriterConnection {
                it.execSql(
                    "UPDATE play_session SET currentEpisodeId = $played, contextType = 'GROUP'," +
                        " contextId = $group, contextOrder = 'OLDEST_FIRST', contextFilterFlags = 3," +
                        " contextMediaFilter = 'AUDIO', contextMinSortDate = 99," +
                        " contextAnchorEpisodeId = $queued, contextAnchorSortDate = 88" +
                        " WHERE id = 0",
                )
            }
            settings.set(BackupSettingKeys.INSTALLATION_ID, "install-1")

            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target(), includeCredentials = false))
            reader(target()).use { reader ->
                val manifest = reader.manifest()
                assertEquals(BackupCodec.FORMAT, manifest.format)
                assertEquals(BackupCodec.FORMAT_VERSION, manifest.formatVersion)
                assertEquals("install-1", manifest.installationId)
                assertEquals(BackupKind.MANUAL, manifest.kind)
                assertEquals(2, manifest.counts.podcasts)
                assertEquals(1, manifest.counts.groups)

                val library = reader.library()
                val one = library.podcasts.first { it.key == "https://feeds.patreon.com/member-feed" }
                assertEquals("https://feeds.patreon.com/member-feed", one.feedUrl)
                assertEquals("RSS", one.source)
                assertEquals(listOf("https://old.example.com/feed.xml"), one.aliases)
                assertEquals("urn:real:guid", one.podcastGuid)
                assertTrue(one.title.isNotEmpty())
                assertEquals("Pinned", one.customTitle)
                assertEquals("https://example.com/art.jpg", one.artworkUrl)
                assertFalse(one.includeInAll)
                assertEquals("OLDEST_FIRST", one.episodeOrder)
                assertEquals("sync-pod-1", one.syncId)
                assertEquals(listOf("g:amb-1"), one.guidAmbiguous)
                assertEquals(1.5f, one.settings?.playbackSpeed)
                assertEquals(true, one.settings?.autoDownload)
                assertEquals("AFTER_24H", one.settings?.deleteAfterPlayed)
                // A derived GUID never travels — it is not identity evidence (02, D98).
                assertNull(library.podcasts.first { it.key != one.key }.podcastGuid)

                val group = library.groups.single()
                assertEquals("uuid-g1", group.uuid)
                assertEquals("Commute", group.name)
                assertEquals(0, group.sortOrder)
                assertEquals("g1", group.orderKey)
                assertEquals(true, group.settings?.skipSilence)
                assertEquals(one.key, group.members.single().podcastKey)
                assertEquals("m1", group.members.single().orderKey)
                assertEquals(7L, group.members.single().addedAt)

                val queue = reader.queue()
                assertEquals("key-queued", queue.upNext.single().k)
                assertEquals(one.key, queue.upNext.single().p)
                assertEquals("q1", queue.upNext.single().ok)
                val session = assertNotNull(queue.session)
                assertEquals("key-played", session.current?.k)
                assertEquals("GROUP", session.contextType)
                assertEquals("uuid-g1", session.contextGroupUuid)
                assertEquals("OLDEST_FIRST", session.order)
                assertEquals(3, session.filterFlags)
                assertEquals("AUDIO", session.mediaFilter)
                assertEquals(99L, session.minSortDate)
                assertEquals("key-queued", session.anchor?.k)
                assertEquals(88L, session.anchorSortDate)

                val lines = reader.lines()
                val playedLine = lines.first { it.k == "key-played" }
                assertEquals(one.key, playedLine.p)
                assertEquals("https://cdn.example.com/a.mp3", playedLine.u)
                assertEquals(10L, playedLine.pl)
                assertEquals(2, playedLine.pc)
                assertEquals(20L, playedLine.lp)
                assertTrue(playedLine.fav)
                assertEquals(12_000L, playedLine.pos)
                assertEquals(25L, playedLine.posAt)
                assertEquals("STREAM", playedLine.posSrc)
                assertEquals(61_000L, playedLine.md)
                assertEquals(30L, playedLine.ts)
                val queuedLine = lines.first { it.k == "key-queued" }
                assertEquals(0L, queuedLine.ts)
                assertTrue(queuedLine.dl)

                val opml = String(reader.bytes(BackupCodec.ENTRY_OPML))
                assertTrue("member-feed" in opml)
                assertTrue("Commute" in opml)
            }
        }

    // --- Snapshot levels and the publication cap (05 Snapshot production) ---------------------

    @Test
    fun levelOneSlimsOnlyOldUnprotectedPlayedLines() =
        runTest {
            val podcast = seedPodcast("https://example.com/p-level1.xml")
            val cutoff = clock.now() - YEAR_MS
            // Old, played and unreferenced: the only line level 1 slims — its state,
            // tombstone and stub hints all leave the wire.
            val slimmed =
                seedEpisode(podcast, "k-slim") {
                    copy(guid = "g-slim", enclosureUrl = "https://cdn.example.com/slim.mp3")
                }
            db.episodeStateDao().upsert(
                episodeStateEntity(slimmed, playedAt = cutoff - 1, updatedAt = 40L) {
                    copy(
                        playCount = 3,
                        lastPlayedAt = 30L,
                        downloadDismissedAt = 35L,
                        measuredDurationMs = 61_000L,
                    )
                },
            )
            // The boundary is inclusive: `pl` at the cutoff is not "older than 365 days".
            val boundary =
                seedEpisode(podcast, "k-boundary") { copy(enclosureUrl = "https://cdn.example.com/b.mp3") }
            db.episodeStateDao().upsert(
                episodeStateEntity(boundary, playedAt = cutoff, updatedAt = 41L) { copy(playCount = 2) },
            )
            // A position, a favourite, an Up next entry or the session current each keep
            // the full line.
            val positioned = seedEpisode(podcast, "k-pos")
            db.episodeStateDao().upsert(episodeStateEntity(positioned, playedAt = cutoff - 1, updatedAt = 42L))
            db.positionDao().upsert(EpisodePositionEntity(positioned, 12_000L, 60_000L, PositionSource.STREAM, 43L))
            val favourite = seedEpisode(podcast, "k-fav")
            db.episodeStateDao().upsert(
                episodeStateEntity(favourite, playedAt = cutoff - 1, updatedAt = 44L) { copy(isFavorite = true) },
            )
            val queued = seedEpisode(podcast, "k-queued")
            db.episodeStateDao().upsert(episodeStateEntity(queued, playedAt = cutoff - 1, updatedAt = 45L))
            db.queueDao().insert(queueEntryEntity(queued, "q1"))
            // The session's `current` is protected even when it is not in Up next.
            val current = seedEpisode(podcast, "k-current")
            db.episodeStateDao().upsert(episodeStateEntity(current, playedAt = cutoff - 1, updatedAt = 46L))
            db.playSessionDao().ensureNow(1L)
            db.useWriterConnection {
                it.execSql("UPDATE play_session SET currentEpisodeId = $current WHERE id = 0")
            }

            assertIs<Outcome.Success<*>>(
                newWriter().write(
                    BackupKind.AUTO_SNAPSHOT,
                    target(),
                    includeCredentials = false,
                    level = SnapshotLevel.SLIM_HISTORY,
                ),
            )
            reader(target()).use { reader ->
                val lines = reader.lines().associateBy { it.k }
                assertEquals(6, lines.size)
                // The exact slim shape: p, k, kv, pl, ts and nothing else.
                assertEquals(
                    EpisodeLineV1(
                        p = "https://example.com/p-level1.xml",
                        k = "k-slim",
                        kv = 1,
                        pl = cutoff - 1,
                        ts = 40L,
                    ),
                    lines.getValue("k-slim"),
                )
                for ((kept, pl) in mapOf(
                    "k-boundary" to cutoff,
                    "k-pos" to cutoff - 1,
                    "k-fav" to cutoff - 1,
                    "k-queued" to cutoff - 1,
                    "k-current" to cutoff - 1,
                )) {
                    assertEquals(pl, lines.getValue(kept).pl, kept)
                }
                assertEquals("https://cdn.example.com/b.mp3", lines.getValue("k-boundary").u)
                assertEquals(2, lines.getValue("k-boundary").pc)
                assertEquals(12_000L, lines.getValue("k-pos").pos)
                assertTrue(lines.getValue("k-fav").fav)
                assertEquals(1, reader.manifest().snapshotLevel)
            }
        }

    @Test
    fun levelTwoDropsOldPlayedLinesAndOldTombstones() =
        runTest {
            val podcast = seedPodcast("https://example.com/p-level2.xml")
            val cutoff = clock.now() - YEAR_MS
            val old = cutoff - 1
            val droppedPlayed = seedEpisode(podcast, "k-drop")
            db.episodeStateDao().upsert(episodeStateEntity(droppedPlayed, playedAt = old, updatedAt = 40L))
            val favourite = seedEpisode(podcast, "k-fav")
            db.episodeStateDao().upsert(
                episodeStateEntity(favourite, playedAt = old, updatedAt = 41L) { copy(isFavorite = true) },
            )
            val positioned = seedEpisode(podcast, "k-pos")
            db.episodeStateDao().upsert(episodeStateEntity(positioned, playedAt = old, updatedAt = 42L))
            db.positionDao().upsert(EpisodePositionEntity(positioned, 5_000L, null, PositionSource.STREAM, 43L))
            val queued = seedEpisode(podcast, "k-queued")
            db.episodeStateDao().upsert(episodeStateEntity(queued, playedAt = old, updatedAt = 44L))
            db.queueDao().insert(queueEntryEntity(queued, "q1"))
            // An old played line that also carries a tombstone still drops (played rule).
            val playedTombstone = seedEpisode(podcast, "k-ptomb")
            db.episodeStateDao().upsert(
                episodeStateEntity(playedTombstone, playedAt = old, updatedAt = 45L) {
                    copy(downloadDismissedAt = old)
                },
            )
            // Tombstone-only lines: old drops, fresh and protected keep.
            val oldTombstone = seedEpisode(podcast, "k-tomb")
            db.episodeStateDao().upsert(
                episodeStateEntity(oldTombstone, updatedAt = 46L) { copy(downloadDismissedAt = old) },
            )
            val freshTombstone = seedEpisode(podcast, "k-fresh")
            db.episodeStateDao().upsert(
                episodeStateEntity(freshTombstone, updatedAt = 47L) { copy(downloadDismissedAt = cutoff) },
            )
            val mixedTombstone = seedEpisode(podcast, "k-mixed")
            db.episodeStateDao().upsert(
                episodeStateEntity(mixedTombstone, updatedAt = 48L) {
                    copy(downloadDismissedAt = old, measuredDurationMs = 30_000L)
                },
            )
            val positionedTombstone = seedEpisode(podcast, "k-post")
            db.episodeStateDao().upsert(
                episodeStateEntity(positionedTombstone, updatedAt = 49L) { copy(downloadDismissedAt = old) },
            )
            db.positionDao().upsert(
                EpisodePositionEntity(positionedTombstone, 5_000L, null, PositionSource.STREAM, 50L),
            )
            val queuedTombstone = seedEpisode(podcast, "k-qtomb")
            db.episodeStateDao().upsert(
                episodeStateEntity(queuedTombstone, updatedAt = 51L) { copy(downloadDismissedAt = old) },
            )
            db.queueDao().insert(queueEntryEntity(queuedTombstone, "q2"))
            // The session current is protected at level 2 as well.
            val current = seedEpisode(podcast, "k-current")
            db.episodeStateDao().upsert(
                episodeStateEntity(current, updatedAt = 52L) { copy(downloadDismissedAt = old) },
            )
            db.playSessionDao().ensureNow(1L)
            db.useWriterConnection {
                it.execSql("UPDATE play_session SET currentEpisodeId = $current WHERE id = 0")
            }

            assertIs<Outcome.Success<*>>(
                newWriter().write(
                    BackupKind.AUTO_SNAPSHOT,
                    target(),
                    includeCredentials = false,
                    level = SnapshotLevel.DROP_HISTORY,
                ),
            )
            reader(target()).use { reader ->
                val lines = reader.lines()
                assertEquals(
                    setOf("k-fav", "k-pos", "k-queued", "k-fresh", "k-mixed", "k-post", "k-qtomb", "k-current"),
                    lines.map { it.k }.toSet(),
                )
                val manifest = reader.manifest()
                assertEquals(2, manifest.snapshotLevel)
                // Counts and the entry record describe the filtered file exactly.
                assertEquals(lines.size, manifest.counts.episodeLines)
                assertEquals(lines.count { it.pl != null }, manifest.counts.played)
                assertEquals(lines.count { it.pl == null && it.st != null }, manifest.counts.inProgress)
                val info = manifest.entries.single { it.name == BackupCodec.ENTRY_EPISODES }
                val bytes = reader.bytes(BackupCodec.ENTRY_EPISODES)
                assertEquals(bytes.size.toLong(), info.bytes)
                assertEquals(bytes.toByteString().sha256().hex(), info.sha256)
                assertEquals(lines.size, info.records)
            }
        }

    @Test
    fun levelThreeKeepsOnlyLiveLines() =
        runTest {
            val podcast = seedPodcast("https://example.com/p-level3.xml")
            val old = clock.now() - YEAR_MS - 1
            // Live lines: session current (never queued), Up next, in progress by
            // startedAt, in progress by a bare position row, favourite.
            val current =
                seedEpisode(podcast, "k-current") { copy(enclosureUrl = "https://cdn.example.com/cur.mp3") }
            val queued = seedEpisode(podcast, "k-queued")
            db.queueDao().insert(queueEntryEntity(queued, "q1"))
            val started = seedEpisode(podcast, "k-started")
            db.episodeStateDao().upsert(episodeStateEntity(started, updatedAt = 40L) { copy(startedAt = 10L) })
            val positionOnly = seedEpisode(podcast, "k-posonly")
            db.positionDao().upsert(EpisodePositionEntity(positionOnly, 8_000L, null, PositionSource.STREAM, 41L))
            val favourite = seedEpisode(podcast, "k-fav")
            db.episodeStateDao().upsert(
                episodeStateEntity(favourite, playedAt = old, updatedAt = 42L) { copy(isFavorite = true) },
            )
            // Dead lines: played (any age), tombstones, download-only rows all go.
            val oldPlayed = seedEpisode(podcast, "k-old")
            db.episodeStateDao().upsert(episodeStateEntity(oldPlayed, playedAt = old, updatedAt = 43L))
            val freshPlayed = seedEpisode(podcast, "k-fresh")
            db.episodeStateDao().upsert(
                episodeStateEntity(freshPlayed, playedAt = clock.now() - 1_000, updatedAt = 44L),
            )
            val tombstone = seedEpisode(podcast, "k-tomb")
            db.episodeStateDao().upsert(
                episodeStateEntity(tombstone, updatedAt = 45L) { copy(downloadDismissedAt = clock.now() - 1_000) },
            )
            val downloaded = seedEpisode(podcast, "k-dl")
            db.downloadDao().insert(downloadEntity(episodeId = downloaded, state = DownloadState.COMPLETED))
            db.playSessionDao().ensureNow(1L)
            db.useWriterConnection {
                it.execSql("UPDATE play_session SET currentEpisodeId = $current WHERE id = 0")
            }

            assertIs<Outcome.Success<*>>(
                newWriter().write(
                    BackupKind.AUTO_SNAPSHOT,
                    target(),
                    includeCredentials = false,
                    level = SnapshotLevel.ACTIVE_ONLY,
                ),
            )
            reader(target()).use { reader ->
                val lines = reader.lines()
                assertEquals(
                    setOf("k-current", "k-queued", "k-started", "k-posonly", "k-fav"),
                    lines.map { it.k }.toSet(),
                )
                // Protected refs keep the stub hints a restore needs on an empty database.
                assertEquals("https://cdn.example.com/cur.mp3", lines.single { it.k == "k-current" }.u)
                assertEquals(0L, lines.single { it.k == "k-current" }.ts)
                assertEquals(8_000L, lines.single { it.k == "k-posonly" }.pos)
                val queue = reader.queue()
                assertEquals("k-queued", queue.upNext.single().k)
                assertEquals("k-current", queue.session?.current?.k)
                val manifest = reader.manifest()
                assertEquals(3, manifest.snapshotLevel)
                assertEquals(lines.size, manifest.counts.episodeLines)
                assertEquals(lines.count { it.pl != null }, manifest.counts.played)
                assertEquals(lines.count { it.pl == null && it.st != null }, manifest.counts.inProgress)
            }
        }

    @Test
    fun compressedCapRejectsTheClosedStageBeforeReplacingAnExistingTarget() =
        runTest {
            val podcast = seedPodcast()
            val episode = seedEpisode(podcast)
            db.episodeStateDao().upsert(episodeStateEntity(episode, playedAt = 10L))
            val target = target()
            val writer = newWriter()
            assertIs<Outcome.Success<*>>(writer.write(BackupKind.AUTO_SNAPSHOT, target, includeCredentials = false))
            val before = fileSystem.read(target) { readByteArray() }
            val size = before.size.toLong()

            // A cap below the staged ZIP's size rejects the closed stage; the previous
            // target survives byte-for-byte and no stage is left behind.
            assertEquals(
                Outcome.Failure(BackupError.TooLarge),
                writer.write(
                    BackupKind.AUTO_SNAPSHOT,
                    target,
                    includeCredentials = false,
                    maxCompressedBytes = size - 1,
                ),
            )
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists("$target.tmp".toPath()))

            // The cap is inclusive: exactly the staged size still publishes.
            assertIs<Outcome.Success<*>>(
                writer.write(BackupKind.AUTO_SNAPSHOT, target, includeCredentials = false, maxCompressedBytes = size),
            )
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
        }

    @Test
    fun nonSnapshotKindsRefuseHistoryLevels() =
        runTest {
            seedPodcast()
            val writer = newWriter()

            assertFailsWith<IllegalArgumentException> {
                writer.write(
                    BackupKind.MANUAL,
                    target(),
                    includeCredentials = false,
                    level = SnapshotLevel.DROP_HISTORY,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                writer.write(
                    BackupKind.SCHEDULED,
                    target(),
                    includeCredentials = false,
                    level = SnapshotLevel.SLIM_HISTORY,
                )
            }
            assertFalse(fileSystem.exists(target()))
            assertFalse(fileSystem.exists("${target()}.tmp".toPath()))

            // FULL is the default and stays a normal write — `snapshotLevel` remains 0.
            assertIs<Outcome.Success<*>>(
                writer.write(BackupKind.MANUAL, target(), includeCredentials = false, level = SnapshotLevel.FULL),
            )
            reader(target()).use { reader -> assertEquals(0, reader.manifest().snapshotLevel) }
        }

    // --- Failure atomicity and the write mutex (05 Writing; AC10/AC11) --------------------------

    @Test
    fun failedWriteLeavesPreviousTargetUntouched() =
        runTest {
            seedPodcast()
            val target = target()
            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target, includeCredentials = false))
            val before = fileSystem.read(target) { readByteArray() }

            // A mid-write failure (queue.json never completes) must not touch the target or
            // leave the staged file behind.
            val failing = newWriter(FailOnEntryFactory(BackupCodec.ENTRY_QUEUE))
            val outcome = failing.write(BackupKind.MANUAL, target, includeCredentials = false)
            assertEquals(Outcome.Failure(BackupError.Io), outcome)
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists("$target.tmp".toPath()))
        }

    @Test
    fun cancellationAfterArchiveCloseCannotPublishTheStage() =
        runTest {
            seedPodcast()
            val target = target()
            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.AUTO_SNAPSHOT, target, false))
            val before = fileSystem.read(target) { readByteArray() }
            seedPodcast()
            val staged = "$target.tmp".toPath()
            val caller = Job()
            val cancelling =
                object : ForwardingFileSystem(fileSystem) {
                    override fun metadataOrNull(path: Path): FileMetadata? {
                        val metadata = super.metadataOrNull(path)
                        if (path == staged) caller.cancel()
                        return metadata
                    }
                }
            val write =
                async(Dispatchers.IO + caller) {
                    newWriter(fileSystem = cancelling).write(BackupKind.AUTO_SNAPSHOT, target, false)
                }

            assertFailsWith<CancellationException> { write.await() }
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists(staged))
        }

    @Test
    fun oversizedEpisodeLineCannotReplaceARestorableBackup() =
        runTest {
            val podcast = seedPodcast()
            val target = target()
            val writer = newWriter()
            assertIs<Outcome.Success<*>>(writer.write(BackupKind.MANUAL, target, includeCredentials = false))
            val before = fileSystem.read(target) { readByteArray() }
            val episode =
                seedEpisode(podcast) {
                    copy(guid = "g".repeat(BackupCodec.EPISODE_LINE_MAX_BYTES.toInt() + 1))
                }
            db.episodeStateDao().upsert(episodeStateEntity(episode, playedAt = 10L))

            assertEquals(
                Outcome.Failure(BackupError.TooLarge),
                writer.write(BackupKind.MANUAL, target, includeCredentials = false),
            )
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists("$target.tmp".toPath()))
        }

    @Test
    fun installationIdWriteFailureDoesNotPublishASnapshot() =
        runTest {
            seedPodcast()
            settings.failNextSet = SettingsError.WriteFailed

            assertEquals(
                Outcome.Failure(BackupError.Storage),
                newWriter().write(BackupKind.AUTO_SNAPSHOT, target(), includeCredentials = false),
            )
            assertFalse(fileSystem.exists(target()))
            assertFalse(fileSystem.exists("${target()}.tmp".toPath()))
        }

    @Test
    fun anArchiveTrippingTheReadRatioCapCannotReplaceThePreviousBackup() =
        runTest {
            val podcast = seedPodcast()
            val target = target()
            val writer = newWriter()
            assertIs<Outcome.Success<*>>(writer.write(BackupKind.MANUAL, target, includeCredentials = false))
            val before = fileSystem.read(target) { readByteArray() }
            val title = "t".repeat(REPEATED_TITLE_BYTES)
            val episodeCount = (ArchiveCaps.DEFAULT_RATIO_FLOOR_BYTES / title.length).toInt() + 1
            val ids =
                db.ingestDao().insertEpisodes(
                    List(episodeCount) { index ->
                        episodeEntity(podcastId = podcast, identityKey = "ep-$index") { copy(title = title) }
                    },
                )
            for (chunk in ids.chunked(DB_BIND_CHUNK)) db.episodeStateDao().ensureAll(chunk, clock.now())

            assertEquals(
                Outcome.Failure(BackupError.TooLarge),
                writer.write(BackupKind.MANUAL, target, includeCredentials = false),
            )
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists("$target.tmp".toPath()))
        }

    @Test
    fun anArchiveBeyondTheImportPayloadCapCannotReplaceThePreviousBackup() =
        runTest {
            seedPodcast()
            val target = target()
            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target, includeCredentials = false))
            val before = fileSystem.read(target) { readByteArray() }
            val staged = "$target.tmp".toPath()
            val oversized =
                object : ForwardingFileSystem(fileSystem) {
                    override fun metadataOrNull(path: Path): FileMetadata? {
                        val metadata = super.metadataOrNull(path) ?: return null
                        return if (path == staged) {
                            FileMetadata(isRegularFile = true, size = MAX_IMPORT_PAYLOAD_BYTES + 1)
                        } else {
                            metadata
                        }
                    }
                }

            assertEquals(
                Outcome.Failure(BackupError.TooLarge),
                newWriter(fileSystem = oversized).write(BackupKind.MANUAL, target, includeCredentials = false),
            )
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists(staged))
        }

    @Test
    fun anOversizedLibraryJsonCannotReplaceThePreviousBackup() =
        runTest {
            val target = target()
            val writer = newWriter()
            assertIs<Outcome.Success<*>>(writer.write(BackupKind.AUTO_SNAPSHOT, target, includeCredentials = false))
            val before = fileSystem.read(target) { readByteArray() }
            val random = Random(7)
            val alphabet = 'a'..'z'
            // Low compression keeps this within archive caps, isolating the codec's library cap.
            val title = CharArray(BackupCodec.LIBRARY_MAX_BYTES.toInt() + 1) { alphabet.random(random) }
            seedPodcast { copy(title = title.concatToString()) }

            assertEquals(
                Outcome.Failure(BackupError.TooLarge),
                writer.write(BackupKind.AUTO_SNAPSHOT, target, includeCredentials = false),
            )
            fileSystem.read(target) { assertContentEquals(before, readByteArray()) }
            assertFalse(fileSystem.exists("$target.tmp".toPath()))
        }

    @Test
    fun concurrentWritersSerialiseOnMutex() =
        runTest {
            seedPodcast()
            val probe = SerialProbeFactory()
            val writer = newWriter(probe)

            val first = async { writer.write(BackupKind.MANUAL, target("a.zip"), includeCredentials = false) }
            val second = async { writer.write(BackupKind.AUTO_SNAPSHOT, target("b.zip"), includeCredentials = false) }
            val (one, two) = awaitAll(first, second)

            assertIs<Outcome.Success<*>>(one)
            assertIs<Outcome.Success<*>>(two)
            // The mutex serialises whole writes: a second writer may never open its archive
            // while the first still has one open.
            assertEquals(1, probe.maxOpen.get())
        }

    // --- R1.9 preflight: counts describe exactly the content a write embeds ----------------------

    @Test
    fun preflightCountsWhatWriteEmbeds() =
        runTest {
            seedPodcast("https://feeds.patreon.com/member-feed")
            seedCredentialPodcast()
            seedPodcast("https://example.com/public.xml")

            assertEquals(PrivacyCounts(2, 1), newWriter().preflight(includePasswords = true))
            assertEquals(PrivacyCounts(2, 0), newWriter().preflight(includePasswords = false))

            assertIs<Outcome.Success<*>>(newWriter().write(BackupKind.MANUAL, target(), includeCredentials = true))
            reader(target()).use { reader ->
                val library = reader.library()
                assertEquals(1, library.credentials.size)
            }
        }

    // --- Seeds and fixtures ---------------------------------------------------------------------

    private suspend fun seedPodcast(
        feedUrl: String = "https://example.com/feed-${Uuid.random().toString().take(8)}.xml",
        block: PodcastEntity.() -> PodcastEntity = { this },
    ): Long = db.podcastDao().insertPodcast(podcastEntity(feedUrl = feedUrl, feedKey = feedUrl, block = block))

    /** A podcast whose feed is protected by a stored credential (origin + secret). */
    private suspend fun seedCredentialPodcast(): Long {
        val credentialId =
            db.credentialDao().insert(
                CredentialEntity(
                    id = 0,
                    origin = CREDENTIAL_ORIGIN,
                    username = CREDENTIAL_USER,
                    secretCipher = null,
                    iv = null,
                    createdAt = 1L,
                ),
            )
        val podcast = seedPodcast("https://supercast.tech/feeds/member.xml") { copy(credentialId = credentialId) }
        val secretId =
            assertNotNull(
                secrets.put(CREDENTIAL_ORIGIN, CREDENTIAL_USER, CREDENTIAL_PASS.toCharArray(), emptyMap()),
            )
        secrets.podcastCredentialIds.value = secrets.podcastCredentialIds.value + (podcast to secretId)
        return podcast
    }

    private suspend fun seedEpisode(
        podcastId: Long,
        identityKey: String = "ep-${Uuid.random().toString().take(8)}",
        block: EpisodeEntity.() -> EpisodeEntity = { this },
    ): Long =
        db
            .ingestDao()
            .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = identityKey, block = block)))
            .single()

    private fun portableKeys(): List<String> =
        AllSettingKeys.list.filter { it.file == SettingsFile.PORTABLE }.map { it.name }

    /** A factory whose writer throws while opening [failOn]'s entry — a mid-write failure. */
    private class FailOnEntryFactory(
        private val failOn: String,
    ) : ArchiveWriterFactory {
        override fun create(
            path: Path,
            deflateLevel: Int,
        ): ArchiveWriter {
            val delegate = ZipArchiveWriter().create(path, deflateLevel)
            return object : ArchiveWriter {
                override fun entry(name: String): Sink {
                    if (name == failOn) throw IOException("injected entry failure")
                    return delegate.entry(name)
                }

                override fun close() = delegate.close()
            }
        }
    }

    /** Counts archives open at once; [maxOpen] proves the writer mutex serialises. */
    private class SerialProbeFactory : ArchiveWriterFactory {
        private val open = AtomicInteger(0)
        val maxOpen = AtomicInteger(0)

        override fun create(
            path: Path,
            deflateLevel: Int,
        ): ArchiveWriter {
            maxOpen.accumulateAndGet(open.incrementAndGet(), ::maxOf)
            // Widen the critical section so a broken mutex provably overlaps the two writes.
            Thread.sleep(25)
            val delegate = ZipArchiveWriter().create(path, deflateLevel)
            return object : ArchiveWriter {
                override fun entry(name: String): Sink = delegate.entry(name)

                override fun close() {
                    delegate.close()
                    open.decrementAndGet()
                }
            }
        }
    }

    private companion object {
        const val CREDENTIAL_ORIGIN = "https://supercast.tech"
        const val CREDENTIAL_USER = "alice"
        const val CREDENTIAL_PASS = "s3cret"
        const val REPEATED_TITLE_BYTES = 8 * 1024
        const val DB_BIND_CHUNK = 500

        /** 05's "older than 365 days" bound, in milliseconds. */
        const val YEAR_MS: Long = 365L * 24 * 60 * 60 * 1000
    }
}
