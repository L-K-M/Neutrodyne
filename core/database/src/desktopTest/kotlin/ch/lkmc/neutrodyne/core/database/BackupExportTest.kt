// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 02 Backup export / 05 Writing step 2: every export projection carries exactly the columns its
 * archive entry needs; the episode keyset stream is ordered, gapless and duplicate-free; and all
 * of it read inside ONE read transaction sees a single point-in-time snapshot even while a
 * concurrent writer commits (WAL). `observeLibraryShape` is 05's library watcher: it fires on
 * subscribe/unsubscribe/membership writes only.
 */
class BackupExportTest {
    private val db = TestDb.inMemory()
    private val dao = db.backupDao()

    @AfterTest fun close() = db.close()

    @Test fun exportProjectionsReturnExactlyTheSeededRowsAndColumns() =
        runTest {
            val cred =
                db
                    .credentialDao()
                    .insert(
                        CredentialEntity(
                            origin = "https://auth.example",
                            username = "u",
                            secretCipher = null,
                            iv = null,
                            createdAt = 1,
                        ),
                    )
            val pa =
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        feedUrl = "https://a/feed.xml",
                        feedKey = "k-a",
                        syncId = "sync-a",
                        title = "Title A",
                        subscribedAt = 100,
                    ) {
                        copy(
                            podcastGuid = "g-real-a",
                            youtubeChannelId = "UC-a",
                            youtubeVariants = 7,
                            customTitle = "Custom A",
                            artworkUrl = "https://a/art.jpg",
                            link = "https://a/home",
                            includeInAll = false,
                            episodeOrder = FeedOrder.OLDEST_FIRST,
                            credentialId = cred,
                        )
                    },
                )
            val pb =
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        feedUrl = "https://b/feed.xml",
                        feedKey = "k-b",
                        syncId = "sync-b",
                        title = "Title B",
                        subscribedAt = 200,
                    ) {
                        copy(podcastGuid = "g-derived-b", podcastGuidDerived = true)
                    },
                )
            // Every grouped read gets a second parent: two aliases on pa (inserted out of url
            // order) and one on pb, settings rows for both podcasts and both groups, two members
            // per group and ambiguous GUIDs on both podcasts.
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "https://old/a.xml",
                    podcastId = pa,
                    reason = AliasReason.REDIRECT,
                    addedAt = 11,
                ),
            )
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "https://aaa/a.xml",
                    podcastId = pa,
                    reason = AliasReason.NEW_FEED_URL,
                    addedAt = 12,
                ),
            )
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "https://zzz/b.xml",
                    podcastId = pb,
                    reason = AliasReason.SUBSCRIBE_INPUT,
                    addedAt = 13,
                ),
            )
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(pa, ScopeOverrides(playbackSpeed = 1.5f, autoDownload = true)),
            )
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(pb, ScopeOverrides(autoDownloadKeepLatest = 5)),
            )
            val g = db.groupDao().insert(groupEntity(uuid = "g-uuid", name = "News", orderKey = "a0"))
            // Sorts ahead of "a0" despite the higher id: the groups list orders by orderKey.
            val g2 = db.groupDao().insert(groupEntity(uuid = "h-uuid", name = "Tech", orderKey = "0z"))
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(g, ScopeOverrides(skipSilence = true)),
            )
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(g2, ScopeOverrides(autoDownloadKeepLatest = 5)),
            )
            // Member orderKeys interleave across groups and run counter to the member PK
            // (podcastId pa < pb) inside each: a dropped or scrambled ORDER BY prefix would
            // interleave or invert the runs the writer consumes per group.
            db.groupDao().insertMember(memberEntity(g, pb, "a1", addedAt = 21))
            db.groupDao().insertMember(memberEntity(g2, pb, "a2", addedAt = 22))
            db.groupDao().insertMember(memberEntity(g, pa, "a3", addedAt = 23))
            db.groupDao().insertMember(memberEntity(g2, pa, "a4", addedAt = 24))
            db.ingestDao().recordGuidKnowledge(
                pa,
                ambiguous = setOf("amb-9", "amb-1"),
                independent = setOf("ind-1"),
                observed = setOf("obs-1"),
            )
            db.ingestDao().recordGuidKnowledge(
                pb,
                ambiguous = setOf("amb-b"),
                independent = emptySet(),
                observed = setOf("obs-b"),
            )

            // One episode per qualification path: full state, queued, position-only, download-only
            // (not COMPLETED), session-current, position-at-zero and bare — the last two export
            // nothing at all.
            val ids =
                db
                    .ingestDao()
                    .insertEpisodes(
                        listOf(
                            episodeEntity(podcastId = pa, identityKey = "e-full", title = "Full") {
                                copy(
                                    guid = "g-e1",
                                    pubDate = 1_000,
                                    enclosureUrl = "https://a/1.mp3",
                                    enclosureType = "audio/mpeg",
                                    durationMs = 60_000,
                                    externalMediaId = "yt-1",
                                    link = "https://a/1",
                                )
                            },
                            episodeEntity(podcastId = pa, identityKey = "e-queued"),
                            episodeEntity(podcastId = pa, identityKey = "e-pos"),
                            episodeEntity(podcastId = pa, identityKey = "e-dl"),
                            episodeEntity(podcastId = pa, identityKey = "e-current"),
                            episodeEntity(podcastId = pa, identityKey = "e-pos0"),
                            episodeEntity(podcastId = pa, identityKey = "e-bare"),
                        ),
                    )
            val (eFull, eQueued, ePos, eDl, eCurrent) = ids
            // A queued episode on the second podcast: queue refs resolve feedKey per row.
            val eQb =
                db
                    .ingestDao()
                    .insertEpisodes(listOf(episodeEntity(podcastId = pb, identityKey = "e-qb")))
                    .single()

            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    episodeId = eFull,
                    startedAt = 1,
                    playedAt = 2,
                    playCount = 3,
                    lastPlayedAt = 4,
                    isFavorite = true,
                    downloadDismissedAt = 5,
                    measuredDurationMs = 6,
                    updatedAt = 7,
                ),
            )
            db.positionDao().upsert(
                EpisodePositionEntity(eFull, positionMs = 8_000, positionSource = PositionSource.STREAM, updatedAt = 9),
            )
            db.downloadDao().insert(downloadEntity(eFull))
            // Queue rows insert in reverse orderKey order: the read must re-sort by orderKey.
            db.queueDao().insert(queueEntryEntity(eFull, orderKey = "q9"))
            db.queueDao().insert(queueEntryEntity(eQb, orderKey = "q5"))
            db.queueDao().insert(queueEntryEntity(eQueued, orderKey = "q0"))
            db.positionDao().upsert(
                EpisodePositionEntity(
                    ePos,
                    positionMs = 5_000,
                    positionSource = PositionSource.DOWNLOAD,
                    updatedAt = 10,
                ),
            )
            db.downloadDao().insert(downloadEntity(eDl, state = DownloadState.QUEUED))
            db.positionDao().upsert(
                EpisodePositionEntity(ids[5], positionMs = 0, positionSource = PositionSource.STREAM, updatedAt = 11),
            )

            db.useWriterConnection { conn ->
                conn.exec(
                    "UPDATE play_session SET currentEpisodeId = $eCurrent, contextType = 'GROUP'," +
                        " contextId = $g, contextOrder = 'OLDEST_FIRST', contextFilterFlags = 3," +
                        " contextMediaFilter = 'AUDIO', contextMinSortDate = 999," +
                        " contextAnchorEpisodeId = $eFull, contextAnchorSortDate = 500 WHERE id = 0",
                )
            }

            db.backupReadTransaction {
                assertEquals(
                    listOf(
                        BackupPodcastRow(
                            id = pa,
                            feedKey = "k-a",
                            feedUrl = "https://a/feed.xml",
                            sourceType = SourceType.RSS,
                            podcastGuid = "g-real-a",
                            podcastGuidDerived = false,
                            youtubeChannelId = "UC-a",
                            youtubeVariants = 7,
                            title = "Title A",
                            customTitle = "Custom A",
                            artworkUrl = "https://a/art.jpg",
                            link = "https://a/home",
                            subscribedAt = 100,
                            includeInAll = false,
                            episodeOrder = FeedOrder.OLDEST_FIRST,
                            syncId = "sync-a",
                            credentialOrigin = "https://auth.example",
                        ),
                        BackupPodcastRow(
                            id = pb,
                            feedKey = "k-b",
                            feedUrl = "https://b/feed.xml",
                            sourceType = SourceType.RSS,
                            podcastGuid = "g-derived-b",
                            podcastGuidDerived = true,
                            youtubeChannelId = null,
                            youtubeVariants = 1,
                            title = "Title B",
                            customTitle = null,
                            artworkUrl = null,
                            link = null,
                            subscribedAt = 200,
                            includeInAll = true,
                            episodeOrder = null,
                            syncId = "sync-b",
                            credentialOrigin = null,
                        ),
                    ),
                    libraryPodcasts(),
                )
                // pa's alias run is contiguous and url-ordered (inserted "old" before "aaa").
                assertEquals(
                    listOf(
                        BackupAliasRow(pa, "https://aaa/a.xml"),
                        BackupAliasRow(pa, "https://old/a.xml"),
                        BackupAliasRow(pb, "https://zzz/b.xml"),
                    ),
                    libraryAliases(),
                )
                assertEquals(
                    listOf(
                        PodcastSettingsEntity(pa, ScopeOverrides(playbackSpeed = 1.5f, autoDownload = true)),
                        PodcastSettingsEntity(pb, ScopeOverrides(autoDownloadKeepLatest = 5)),
                    ),
                    libraryPodcastSettings(),
                )
                // Only KNOWN_AMBIGUOUS exports — independent/observed provenance never travels.
                // pa's pair proves the guid sort inside one podcast (inserted "amb-9" first).
                assertEquals(
                    listOf(
                        BackupAmbiguousGuidRow(pa, "amb-1"),
                        BackupAmbiguousGuidRow(pa, "amb-9"),
                        BackupAmbiguousGuidRow(pb, "amb-b"),
                    ),
                    libraryAmbiguousGuids(),
                )
                assertEquals(
                    listOf(
                        groupEntity(uuid = "h-uuid", name = "Tech", orderKey = "0z").copy(id = g2),
                        groupEntity(uuid = "g-uuid", name = "News", orderKey = "a0").copy(id = g),
                    ),
                    libraryGroups(),
                )
                assertEquals(
                    listOf(
                        PodcastGroupSettingsEntity(g, ScopeOverrides(skipSilence = true)),
                        PodcastGroupSettingsEntity(g2, ScopeOverrides(autoDownloadKeepLatest = 5)),
                    ),
                    libraryGroupSettings(),
                )
                // Contiguous groupId runs, orderKey-ordered inside each group.
                assertEquals(
                    listOf(
                        BackupMemberRow(groupId = g, orderKey = "a1", addedAt = 21, feedKey = "k-b"),
                        BackupMemberRow(groupId = g, orderKey = "a3", addedAt = 23, feedKey = "k-a"),
                        BackupMemberRow(groupId = g2, orderKey = "a2", addedAt = 22, feedKey = "k-b"),
                        BackupMemberRow(groupId = g2, orderKey = "a4", addedAt = 24, feedKey = "k-a"),
                    ),
                    libraryMemberships(),
                )
                assertEquals(
                    listOf(
                        BackupQueueRow("k-a", "e-queued", "q0"),
                        BackupQueueRow("k-b", "e-qb", "q5"),
                        BackupQueueRow("k-a", "e-full", "q9"),
                    ),
                    queueRefs(),
                )
                assertEquals(
                    BackupSessionRow(
                        currentFeedKey = "k-a",
                        currentIdentityKey = "e-current",
                        contextType = ContextType.GROUP,
                        contextGroupUuid = "g-uuid",
                        contextPodcastKey = null,
                        contextOrder = FeedOrder.OLDEST_FIRST,
                        contextFilterFlags = 3,
                        contextMediaFilter = MediaFilter.AUDIO,
                        contextMinSortDate = 999,
                        anchorFeedKey = "k-a",
                        anchorIdentityKey = "e-full",
                        anchorSortDate = 500,
                    ),
                    playSession(),
                )

                val lines = episodeChunk(0, BackupDao.EPISODE_CHUNK)
                assertEquals(listOf(eFull, eQueued, ePos, eDl, eCurrent, eQb), lines.map { it.id })
                assertEquals(
                    BackupEpisodeRow(
                        feedKey = "k-a",
                        id = eFull,
                        identityKey = "e-full",
                        guid = "g-e1",
                        title = "Full",
                        pubDate = 1_000,
                        enclosureUrl = "https://a/1.mp3",
                        enclosureType = "audio/mpeg",
                        durationMs = 60_000,
                        externalMediaId = "yt-1",
                        link = "https://a/1",
                        playedAt = 2,
                        playCount = 3,
                        startedAt = 1,
                        lastPlayedAt = 4,
                        isFavorite = true,
                        downloadDismissedAt = 5,
                        measuredDurationMs = 6,
                        stateUpdatedAt = 7,
                        positionMs = 8_000,
                        positionSource = PositionSource.STREAM,
                        positionUpdatedAt = 9,
                        downloaded = true,
                    ),
                    lines.single { it.id == eFull },
                )
                // Queue-only: every LEFT-JOINed state and position column is NULL (ts = 0 line).
                val queued = lines.single { it.id == eQueued }
                assertNull(queued.stateUpdatedAt)
                assertNull(queued.playedAt)
                assertNull(queued.positionMs)
                assertEquals(false, queued.downloaded)
                // Position-only: pos/posAt/posSrc travel without any episode_state row.
                val pos = lines.single { it.id == ePos }
                assertNull(pos.stateUpdatedAt)
                assertEquals(5_000L, pos.positionMs)
                assertEquals(PositionSource.DOWNLOAD, pos.positionSource)
                assertEquals(10L, pos.positionUpdatedAt)
                // A QUEUED download row qualifies but is not 'COMPLETED'.
                val dl = lines.single { it.id == eDl }
                assertNull(dl.stateUpdatedAt)
                assertEquals(false, dl.downloaded)
                // Current-episode only.
                val current = lines.single { it.id == eCurrent }
                assertNull(current.stateUpdatedAt)
                assertNull(current.positionMs)
                // Queued on the second podcast: the feedKey join resolves per row.
                val qb = lines.single { it.id == eQb }
                assertEquals("k-b", qb.feedKey)
                assertNull(qb.stateUpdatedAt)
            }
        }

    /**
     * The dominant real input (a fresh install): the `play_session` id = 0 singleton with every
     * ref null and neutral context columns still exports as one `SessionV1` row.
     */
    @Test fun playSessionExportsTheDefaultSingleton() =
        runTest {
            assertEquals(DEFAULT_SESSION, dao.playSession())
        }

    /** PODCAST context resolves `contextId` through `podcast` — a separate join branch. */
    @Test fun playSessionResolvesPodcastContextToFeedKey() =
        runTest {
            val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-ctx"))
            db.useWriterConnection { conn ->
                conn.exec(
                    "UPDATE play_session SET contextType = 'PODCAST', contextId = $p," +
                        " contextMinSortDate = 42 WHERE id = 0",
                )
            }
            assertEquals(
                DEFAULT_SESSION.copy(
                    contextType = ContextType.PODCAST,
                    contextPodcastKey = "k-ctx",
                    contextMinSortDate = 42,
                ),
                dao.playSession(),
            )
        }

    /**
     * `contextAnchorEpisodeId` has no FK ("the anchor may be deleted", `PlaySessionEntity`): a
     * dangling anchor still exports the live `anchorSortDate` with a null ref pair, never drops
     * the session row.
     */
    @Test fun playSessionSurvivesADeletedContextAnchor() =
        runTest {
            db.useWriterConnection { conn ->
                conn.exec(
                    "UPDATE play_session SET contextAnchorEpisodeId = 4242," +
                        " contextAnchorSortDate = 777 WHERE id = 0",
                )
            }
            assertEquals(DEFAULT_SESSION.copy(anchorSortDate = 777), dao.playSession())
        }

    @Test fun episodeChunksPaginateByIdWithoutGapsOrDuplicates() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-big-1"))
            val p2 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-big-2"))
            val ids =
                db
                    .ingestDao()
                    .insertEpisodes(
                        (1..1_300).map { i -> episodeEntity(podcastId = p1, identityKey = "e-1-$i") } +
                            (1..1_300).map { i -> episodeEntity(podcastId = p2, identityKey = "e-2-$i") },
                    )
            db.useWriterConnection { conn ->
                conn.usePrepared("INSERT INTO episode_state(episodeId, updatedAt) VALUES (?, 1)") { stmt ->
                    for (id in ids) {
                        stmt.bindLong(1, id)
                        stmt.step()
                        stmt.reset()
                    }
                }
            }

            val pages = mutableListOf<List<BackupEpisodeRow>>()
            var after = 0L
            while (true) {
                val chunk = dao.episodeChunk(after, BackupDao.EPISODE_CHUNK)
                if (chunk.isEmpty()) break
                pages += chunk
                after = chunk.last().id
            }

            assertEquals(listOf(1_000, 1_000, 600), pages.map { it.size })
            assertEquals(ids, pages.flatten().map { it.id })
        }

    /**
     * The WAL reader-isolation proof (02: `withReadTransaction`, DEFERRED): a writer that commits
     * between two chunk reads lands nothing in the open transaction — not even its committed
     * `podcast` row; a fresh read afterwards sees both.
     */
    @Test fun aWriterBetweenChunksIsInvisibleToTheOpenReadTransaction() =
        runBlocking {
            val dir = Files.createTempDirectory("backup-wal-")
            val db = TestDb.file(dir)
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-snap"))
                val ids =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            (1..1_500).map { i -> episodeEntity(podcastId = p, identityKey = "e-$i") },
                        )
                db.useWriterConnection { conn ->
                    conn.usePrepared("INSERT INTO episode_state(episodeId, updatedAt) VALUES (?, 1)") { stmt ->
                        for (id in ids) {
                            stmt.bindLong(1, id)
                            stmt.step()
                            stmt.reset()
                        }
                    }
                }

                val firstChunkRead = CompletableDeferred<Unit>()
                val writeCommitted = CompletableDeferred<Unit>()
                val writer =
                    async(Dispatchers.Default) {
                        firstChunkRead.await()
                        db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-late"))
                        val late =
                            db
                                .ingestDao()
                                .insertEpisodes(listOf(episodeEntity(podcastId = p, identityKey = "e-late")))
                                .single()
                        db.episodeStateDao().ensure(episodeStateEntity(late))
                        writeCommitted.complete(Unit)
                    }

                var insidePodcasts = 0
                val inside = mutableListOf<BackupEpisodeRow>()
                db.backupReadTransaction {
                    inside += episodeChunk(0, BackupDao.EPISODE_CHUNK)
                    firstChunkRead.complete(Unit)
                    withTimeout(HANDSHAKE_MS) { writeCommitted.await() }
                    inside += episodeChunk(inside.last().id, BackupDao.EPISODE_CHUNK)
                    insidePodcasts = libraryPodcasts().size
                }
                writer.await()

                // 1,500 seeded rows, none skipped and none repeated; the late row stays outside.
                assertEquals(ids, inside.map { it.id })
                assertEquals(1, insidePodcasts)

                val fresh = db.backupDao().episodeChunk(ids.last(), BackupDao.EPISODE_CHUNK)
                assertEquals("e-late", fresh.single().identityKey)
                assertEquals(2, db.backupDao().libraryPodcasts().size)
            } finally {
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

    /**
     * 05 Auto Backup's library watcher: `observeLibraryShape` emits the initial shape, then one
     * shape per subscribe/unsubscribe/membership change — plus an equal shape for any `podcast`
     * column write, the re-emission the watcher's `distinctUntilChanged()` exists to drop.
     * Position saves and played marks write tables the query does not observe, so nothing emits.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun libraryShapeEmitsOnStructureButNotOnPositionOrPlayedWrites() =
        runTest {
            val shapeDb = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                shapeDb.backupDao().observeLibraryShape().test {
                    assertEquals(LibraryShape(0, 0), awaitItem())

                    val p = shapeDb.podcastDao().insertPodcast(podcastEntity(feedKey = "k-shape"))
                    advanceUntilIdle()
                    assertEquals(LibraryShape(1, 0), awaitItem())

                    // A `podcast` column write re-emits an identical shape; the watcher's
                    // `distinctUntilChanged()` drops it before any snapshot is requested.
                    shapeDb.podcastDao().setCustomTitle(p, "Renamed")
                    advanceUntilIdle()
                    assertEquals(LibraryShape(1, 0), awaitItem())

                    // Groups are not part of the shape.
                    val g = shapeDb.groupDao().insert(groupEntity(orderKey = "a"))
                    advanceUntilIdle()
                    expectNoEvents()

                    shapeDb.groupDao().insertMember(memberEntity(g, p, "a"))
                    advanceUntilIdle()
                    assertEquals(LibraryShape(1, 1), awaitItem())

                    val e =
                        shapeDb
                            .ingestDao()
                            .insertEpisodes(listOf(episodeEntity(podcastId = p)))
                            .single()
                    shapeDb.positionDao().upsert(
                        EpisodePositionEntity(e, positionMs = 1, positionSource = PositionSource.STREAM, updatedAt = 1),
                    )
                    shapeDb.episodeStateDao().upsert(episodeStateEntity(e, playedAt = 1))
                    advanceUntilIdle()
                    expectNoEvents()

                    shapeDb.groupDao().deleteMembers(g, listOf(p))
                    advanceUntilIdle()
                    assertEquals(LibraryShape(1, 0), awaitItem())

                    shapeDb.podcastDao().deleteCascade(p, now = 1)
                    advanceUntilIdle()
                    assertEquals(LibraryShape(0, 0), awaitItem())
                }
            } finally {
                shapeDb.close()
            }
        }

    private companion object {
        /** The writer handshake deadline; a deadlock fails fast instead of hanging the suite. */
        const val HANDSHAKE_MS = 10_000L

        /** The `play_session` id = 0 singleton's export shape straight after `onCreate`. */
        val DEFAULT_SESSION =
            BackupSessionRow(
                currentFeedKey = null,
                currentIdentityKey = null,
                contextType = null,
                contextGroupUuid = null,
                contextPodcastKey = null,
                contextOrder = FeedOrder.NEWEST_FIRST,
                contextFilterFlags = 0,
                contextMediaFilter = MediaFilter.ALL,
                contextMinSortDate = null,
                anchorFeedKey = null,
                anchorIdentityKey = null,
                anchorSortDate = null,
            )
    }
}
