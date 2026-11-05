// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.execSql
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.database.BackupDao
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeDownloadLifecycle
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.EpisodeRefV1
import ch.lkmc.neutrodyne.feeds.backup.GroupV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.MemberV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.source
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 05's `RestoreMerger` episode, Up next and session phases (transactions B and C of the
 * [Merge and Replace rules] table) over the bundled driver and real `BackupCodec` streams:
 * the streamed `episodes.jsonl` in 1,000-line write transactions, archive timestamps,
 * the Merge/Replace state and position columns, stubs, Up next dedup/order and the
 * `play_session` singleton — plus the mid-transaction failure/replay contract.
 */
class RestoreEpisodeMergerTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val secrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val scheduler = FakeRefreshScheduler()
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
            downloads =
                Provider {
                    FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) }
                },
        )
    private val episodes =
        RestoreEpisodeMerger(
            db = db,
            episodes = EpisodeMatcher(db),
            writes = RestoreWrites(db),
            orderKeys = TestOrderKeys,
            clock = clock,
        )

    @AfterTest fun close() = db.close()

    // --- Merge state and position (05 HISTORY) --------------------------------------------------

    @Test
    fun mergeRestoresStateAndPositionWithBackupTimestamps() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    episodeId = e,
                    playCount = 1,
                    startedAt = 200,
                    isFavorite = false,
                    updatedAt = 500,
                ),
            )
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            line(
                                "https://a/feed",
                                "g:e",
                                pl = 300,
                                pc = 2,
                                st = 150,
                                lp = 250,
                                fav = true,
                                dis = 80,
                                md = 99,
                                pos = 5_000,
                                posAt = 650,
                                posSrc = "DOWNLOAD",
                                dur = 60_000,
                                ts = 700,
                            ),
                        ),
                )

            val summary = restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(1, summary.episodeLines)
            assertEquals(0, summary.stubs)
            assertEquals(0, summary.unmatched)
            val s = assertNotNull(db.episodeStateDao().byEpisode(e))
            assertEquals(300L, s.playedAt)
            assertEquals(2, s.playCount)
            assertNull(s.startedAt) // a played result clears the in-progress marker
            assertEquals(250L, s.lastPlayedAt)
            assertTrue(s.isFavorite)
            assertEquals(80L, s.downloadDismissedAt)
            assertEquals(99L, s.measuredDurationMs)
            // The backup's `ts` stamps the row, never the restore clock.
            assertEquals(700L, s.updatedAt)
            val pos = assertNotNull(db.positionDao().byEpisode(e))
            // Newer than the played mark (650 > 300): adopted verbatim, archive stamps kept.
            assertEquals(5_000L, pos.positionMs)
            assertEquals(60_000L, pos.durationMs)
            assertEquals(PositionSource.DOWNLOAD, pos.positionSource)
            assertEquals(650L, pos.updatedAt)
        }

    @Test
    fun mergeZeroPositionLineStillRunsPostRules() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            db.positionDao().upsert(position(e, 5_000, at = 500))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    // pos = 0 never overwrites, but the line's newer played mark must still
                    // reset the surviving local position to 0@playedAt (05's post rules).
                    episodeLines = listOf(line("https://a/feed", "g:e", pl = 900, pos = 0, posAt = 850, ts = 1_000)),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(900L, db.episodeStateDao().byEpisode(e)!!.playedAt)
            val pos = assertNotNull(db.positionDao().byEpisode(e))
            assertEquals(0L, pos.positionMs)
            assertEquals(900L, pos.updatedAt)
        }

    @Test
    fun mergePositionNewerBackupWinsZeroAndNullNeverOverwrite() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val wins = seedEpisode(p, "g:wins")
            val keeps = seedEpisode(p, "g:keeps")
            val absent = seedEpisode(p, "g:absent")
            db.positionDao().upsert(position(wins, 100, at = 500))
            db.positionDao().upsert(position(keeps, 100, at = 500))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            line("https://a/feed", "g:wins", pos = 200, posAt = 700, dur = 950, ts = 1),
                            line("https://a/feed", "g:keeps", pos = 0, posAt = 900, ts = 1),
                            line("https://a/feed", "g:absent", pos = 300, posAt = 300, posSrc = "DOWNLOAD", ts = 1),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            db.positionDao().byEpisode(wins)!!.let {
                assertEquals(200L, it.positionMs)
                assertEquals(950L, it.durationMs)
                assertEquals(700L, it.updatedAt)
            }
            db.positionDao().byEpisode(keeps)!!.let {
                assertEquals(100L, it.positionMs)
                assertEquals(500L, it.updatedAt)
            }
            db.positionDao().byEpisode(absent)!!.let {
                assertEquals(300L, it.positionMs)
                assertEquals(PositionSource.DOWNLOAD, it.positionSource)
                assertEquals(300L, it.updatedAt)
            }
        }

    @Test
    fun ts0LineMergesOnlyItsPositionAndDerivesTheProxy() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines = listOf(line("https://a/feed", "g:e", pos = 4_000, posAt = 300, ts = 0)),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val pos = assertNotNull(db.positionDao().byEpisode(e))
            assertEquals(4_000L, pos.positionMs)
            assertEquals(300L, pos.updatedAt)
            // No state field was written; the in-progress proxy derives with `posAt`
            // stamps, never now and never 0 (05 Line timestamps).
            val s = assertNotNull(db.episodeStateDao().byEpisode(e))
            assertNull(s.playedAt)
            assertEquals(0, s.playCount)
            assertEquals(300L, s.startedAt)
            assertEquals(300L, s.updatedAt)
        }

    // --- Replace (05 HISTORY column) -------------------------------------------------------------

    @Test
    fun replaceTakesBackupStateVerbatimAndClearsUnwrittenRows() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val written = seedEpisode(p, "g:written")
            val dropped = seedEpisode(p, "g:dropped")
            db.episodeStateDao().upsert(EpisodeStateEntity(written, playedAt = 999, playCount = 9, updatedAt = 999))
            db.episodeStateDao().upsert(EpisodeStateEntity(dropped, playedAt = 888, updatedAt = 888))
            db.positionDao().upsert(position(written, 9_000, at = 999))
            db.positionDao().upsert(position(dropped, 8_000, at = 888))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            line("https://a/feed", "g:written", pl = 100, pc = 1, pos = 50, posAt = 150, ts = 200),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            db.episodeStateDao().byEpisode(written)!!.let {
                assertEquals(100L, it.playedAt) // verbatim, not the local max
                assertEquals(1, it.playCount)
                assertEquals(200L, it.updatedAt)
            }
            db.positionDao().byEpisode(written)!!.let {
                // pos = 50, but the played mark (100) is newer than posAt (150)? No — 150 > 100
                // keeps the written position.
                assertEquals(50L, it.positionMs)
                assertEquals(150L, it.updatedAt)
            }
            // `g:dropped` had no line: the Replace pre-step removed its rows.
            assertNull(db.episodeStateDao().byEpisode(dropped))
            assertNull(db.positionDao().byEpisode(dropped))
            assertNotNull(db.episodeDao().byId(dropped))
        }

    // --- Category gating --------------------------------------------------------------------------

    @Test
    fun upNextOnlyWritesQueueAndStubsReferencedLines() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            db.episodeStateDao().upsert(EpisodeStateEntity(e, playedAt = 111, updatedAt = 111))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            // Referenced by Up next → stubs even without HISTORY.
                            line("https://a/feed", "g:ref", u = "https://cdn/ref.mp3", pl = 100, ts = 500),
                            // Not referenced, no local row → unmatched, no stub.
                            line("https://a/feed", "g:other", u = "https://cdn/other.mp3", pl = 200, ts = 500),
                            // A matched line's state is not written without HISTORY either.
                            line("https://a/feed", "g:e", pl = 999, ts = 999),
                        ),
                    queue = QueueV1(upNext = listOf(UpNextRefV1("https://a/feed", "g:ref"))),
                )

            val summary = restore(staged, MergePolicy.RESTORE_MERGE, setOf(RestoreCategory.UP_NEXT))

            assertEquals(1, summary.stubs)
            assertEquals(1, summary.unmatched)
            val stub = assertNotNull(db.episodeDao().byIdentityKey(p, "g:ref"))
            assertNull(db.episodeStateDao().byEpisode(stub.id)) // no state without HISTORY
            assertNull(db.episodeDao().byIdentityKey(p, "g:other"))
            assertEquals(listOf(stub.id), db.queueDao().entries().map { it.episodeId })
            // The matched local episode kept its local state — HISTORY was off.
            assertEquals(111L, db.episodeStateDao().byEpisode(e)!!.playedAt)
        }

    @Test
    fun historyOnlyLeavesQueueAndSessionUntouched() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            val queued = seedEpisode(p, "g:q")
            db.queueDao().insert(queueEntryEntity(queued, "0.5", addedAt = 7))
            val before = db.playSessionDao().get()
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines = listOf(line("https://a/feed", "g:e", pl = 300, ts = 400)),
                    queue =
                        QueueV1(
                            upNext = listOf(UpNextRefV1("https://a/feed", "g:e")),
                            session = SessionV1(current = EpisodeRefV1("https://a/feed", "g:e")),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, setOf(RestoreCategory.HISTORY))

            assertEquals(300L, db.episodeStateDao().byEpisode(e)!!.playedAt)
            assertEquals(listOf(queued), db.queueDao().entries().map { it.episodeId })
            assertEquals(before, db.playSessionDao().get())
        }

    @Test
    fun noEpisodeCategoryConsumesNothingAndWritesNothing() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines = listOf(line("https://a/feed", "g:e", pl = 300, ts = 400)),
                    queue = QueueV1(upNext = listOf(UpNextRefV1("https://a/feed", "g:e"))),
                )

            val settled =
                merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, setOf(RestoreCategory.SETTINGS))
            val summary =
                episodes
                    .mergeEpisodes(
                        staged,
                        settled,
                        MergePolicy.RESTORE_MERGE,
                        setOf(RestoreCategory.SETTINGS),
                    ).summary

            assertEquals(0, summary.episodeLines)
            assertNull(db.episodeStateDao().byEpisode(e))
            assertTrue(db.queueDao().entries().isEmpty())
        }

    // --- Up next (05 transaction C) --------------------------------------------------------------

    @Test
    fun playedEpisodeLeavesTheQueue() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val played = seedEpisode(p, "g:played")
            val stays = seedEpisode(p, "g:stays")
            db.queueDao().insert(queueEntryEntity(played, "0.4"))
            db.queueDao().insert(queueEntryEntity(stays, "0.6"))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines = listOf(line("https://a/feed", "g:played", pl = 900, ts = 1_000)),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(listOf(stays), db.queueDao().entries().map { it.episodeId })
        }

    @Test
    fun queueMergeAppendsDedupedRefsInBackupOrder() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val already = seedEpisode(p, "g:already")
            val played = seedEpisode(p, "g:played")
            val added = seedEpisode(p, "g:added")
            db.queueDao().insert(queueEntryEntity(already, "0.5"))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines = listOf(line("https://a/feed", "g:played", pl = 100, ts = 200)),
                    queue =
                        QueueV1(
                            upNext =
                                listOf(
                                    UpNextRefV1("https://a/feed", "g:already"), // local row → skipped
                                    UpNextRefV1("https://a/feed", "g:played"), // played → skipped
                                    UpNextRefV1("https://a/feed", "g:added"),
                                    UpNextRefV1("https://a/feed", "g:added"), // backup-internal dup → skipped
                                    UpNextRefV1("https://a/feed", "g:ghost"), // unresolvable → skipped
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val entries = db.queueDao().entries()
            assertEquals(listOf(already, added), entries.map { it.episodeId })
            assertEquals("0.5", entries[0].orderKey)
            assertEquals(TestOrderKeys.after("0.5"), entries[1].orderKey)
            assertEquals(ARCHIVE_AT, entries[1].addedAt)
        }

    // --- Up next Replace order keys (05 transaction C) ----------------------------------------
    //
    // These run on `ProtocolOrderKeys` like the library phase's Replace-key tests —
    // `TestOrderKeys` accepts decimal strings a real archive's `ok` can never be. The queue
    // shares the library phase's rule: a Replace-written list keeps staged keys only when
    // every slot is a valid `OrderKey` in strict ascending order; a missing, malformed,
    // duplicated or unordered key rewrites the whole accepted list in archive order.

    @Test
    fun queueReplaceKeepsACompleteValidOkListVerbatim() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val local = seedEpisode(p, "g:local")
            val e1 = seedEpisode(p, "g:e1")
            val e2 = seedEpisode(p, "g:e2")
            val e3 = seedEpisode(p, "g:e3")
            db.queueDao().insert(queueEntryEntity(local, "a9"))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue =
                        QueueV1(
                            upNext =
                                listOf(
                                    UpNextRefV1("https://a/feed", "g:e1", ok = "a0"),
                                    UpNextRefV1("https://a/feed", "g:e2", ok = "a2"),
                                    UpNextRefV1("https://a/feed", "g:e3", ok = "a5"),
                                ),
                        ),
                )

            realRestore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            // The local queue is gone and every staged key lands verbatim — no rewrite.
            val entries = db.queueDao().entries()
            assertEquals(listOf(e1, e2, e3), entries.map { it.episodeId })
            assertEquals(listOf("a0", "a2", "a5"), entries.map { it.orderKey })
            assertEquals(List(3) { ARCHIVE_AT }, entries.map { it.addedAt })
        }

    @Test
    fun queueReplaceRewritesTheAcceptedListWhenAnOkIsMissing() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e1 = seedEpisode(p, "g:e1")
            val e2 = seedEpisode(p, "g:e2")
            val e3 = seedEpisode(p, "g:e3")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue =
                        QueueV1(
                            upNext =
                                listOf(
                                    UpNextRefV1("https://a/feed", "g:e1", ok = "a0"),
                                    UpNextRefV1("https://a/feed", "g:e2"), // no ok
                                    UpNextRefV1("https://a/feed", "g:e3", ok = "a2"),
                                ),
                        ),
                )

            realRestore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            // A missing key no longer interpolates between its neighbours — the whole
            // accepted list takes `OrderKey.rewrite(3)` in archive order.
            val entries = db.queueDao().entries()
            assertEquals(listOf(e1, e2, e3), entries.map { it.episodeId })
            assertEquals(ProtocolOrderKeys.rewrite(3), entries.map { it.orderKey })
        }

    @Test
    fun queueReplaceRewritesMalformedAndForeignOksWithoutThrowing() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e1 = seedEpisode(p, "g:e1")
            val e2 = seedEpisode(p, "g:e2")
            val e3 = seedEpisode(p, "g:e3")
            // "!!!" fails the protocol grammar; "0.5" is a decimal spelling a real archive
            // can never carry. Under the old interpolation the missing slot would have fed
            // both to the generator and thrown mid-restore.
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue =
                        QueueV1(
                            upNext =
                                listOf(
                                    UpNextRefV1("https://a/feed", "g:e1", ok = "!!!"),
                                    UpNextRefV1("https://a/feed", "g:e2"),
                                    UpNextRefV1("https://a/feed", "g:e3", ok = "0.5"),
                                ),
                        ),
                )

            realRestore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            val entries = db.queueDao().entries()
            assertEquals(listOf(e1, e2, e3), entries.map { it.episodeId })
            assertEquals(ProtocolOrderKeys.rewrite(3), entries.map { it.orderKey })
        }

    @Test
    fun queueReplaceRewritesDescendingAndDuplicatedOks() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e1 = seedEpisode(p, "g:e1")
            val e2 = seedEpisode(p, "g:e2")
            for (oks in listOf(listOf("a1", "a0"), listOf("a0", "a0"))) {
                val staged =
                    staged(
                        podcasts = listOf(podcastV1("https://a/feed")),
                        queue =
                            QueueV1(
                                upNext =
                                    listOf(
                                        UpNextRefV1("https://a/feed", "g:e1", ok = oks[0]),
                                        UpNextRefV1("https://a/feed", "g:e2", ok = oks[1]),
                                    ),
                            ),
                    )

                realRestore(staged, MergePolicy.RESTORE_REPLACE, ALL)

                // Descending or repeated keys cannot persist either — the archive's order
                // stays (`ORDER BY orderKey` must not invert it) on fresh keys.
                val entries = db.queueDao().entries()
                assertEquals(listOf(e1, e2), entries.map { it.episodeId })
                assertEquals(ProtocolOrderKeys.rewrite(2), entries.map { it.orderKey })
            }
        }

    @Test
    fun queueRefsResolveStubsAndLinelessEpisodesAlike() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val known = seedEpisode(p, "g:known") // no line — resolved through the ladder
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(line("https://a/feed", "g:stubbed", u = "https://cdn/s.mp3", ts = 5)),
                    queue =
                        QueueV1(
                            upNext =
                                listOf(
                                    UpNextRefV1("https://a/feed", "g:stubbed"),
                                    UpNextRefV1("https://a/feed", "g:known"),
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val stub = assertNotNull(db.episodeDao().byIdentityKey(p, "g:stubbed"))
            assertEquals(listOf(stub.id, known), db.queueDao().entries().map { it.episodeId })
        }

    // --- Play session (05 transaction C) -----------------------------------------------------------

    @Test
    fun sessionMergeRestoresWhileLocalIsIdle() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val current = seedEpisode(p, "g:cur")
            val anchor = seedEpisode(p, "g:anchor")
            val group = db.groupDao().insert(groupEntity(uuid = "u-g", name = "Commute", orderKey = "0.5"))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    groups = listOf(groupV1(uuid = "u-g", name = "Commute")),
                    queue =
                        QueueV1(
                            upNext = emptyList(),
                            session =
                                SessionV1(
                                    current = EpisodeRefV1("https://a/feed", "g:cur"),
                                    contextType = "GROUP",
                                    contextGroupUuid = "u-g",
                                    order = "OLDEST_FIRST",
                                    filterFlags = 3,
                                    mediaFilter = "AUDIO",
                                    minSortDate = 12_345,
                                    anchor = EpisodeRefV1("https://a/feed", "g:anchor"),
                                    anchorSortDate = 999,
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val s = assertNotNull(db.playSessionDao().get())
            assertEquals(current, s.currentEpisodeId)
            assertEquals(ContextType.GROUP, s.contextType)
            assertEquals(group, s.contextId)
            assertEquals(FeedOrder.OLDEST_FIRST, s.contextOrder)
            assertEquals(3, s.contextFilterFlags)
            assertEquals(MediaFilter.AUDIO, s.contextMediaFilter)
            assertEquals(12_345L, s.contextMinSortDate)
            assertEquals(anchor, s.contextAnchorEpisodeId)
            assertEquals(999L, s.contextAnchorSortDate)
            assertEquals(clock.now(), s.updatedAt)
            assertEquals(1L, s.generation)
        }

    @Test
    fun sessionMergeKeepsALocalSessionInProgress() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val playing = seedEpisode(p, "g:playing")
            val other = seedEpisode(p, "g:other")
            seedSession(current = playing)
            val before = db.playSessionDao().get()!!
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue =
                        QueueV1(
                            upNext = emptyList(),
                            session =
                                SessionV1(
                                    current = EpisodeRefV1("https://a/feed", "g:other"),
                                    contextType = "PODCAST",
                                    contextPodcastKey = "https://a/feed",
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            // A local session already playing is left alone under Merge.
            assertEquals(before, db.playSessionDao().get())
            assertEquals(playing, db.playSessionDao().get()!!.currentEpisodeId)
        }

    @Test
    fun sessionReplaceTakesTheBackupsSession() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val playing = seedEpisode(p, "g:playing")
            val other = seedEpisode(p, "g:other")
            seedSession(current = playing, contextType = ContextType.PODCAST, contextId = p)
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue =
                        QueueV1(
                            upNext = emptyList(),
                            session =
                                SessionV1(
                                    current = EpisodeRefV1("https://a/feed", "g:other"),
                                    contextType = "PODCAST",
                                    contextPodcastKey = "https://a/feed",
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            val s = assertNotNull(db.playSessionDao().get())
            assertEquals(other, s.currentEpisodeId)
            assertEquals(ContextType.PODCAST, s.contextType)
            assertEquals(p, s.contextId)
        }

    @Test
    fun replaceWithoutQueueJsonClearsTheWholeSession() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val playing = seedEpisode(p, "g:playing")
            val anchor = seedEpisode(p, "g:anchor")
            val group = db.groupDao().insert(groupEntity(uuid = "u-g", name = "Ctx", orderKey = "0.5"))
            seedSession(current = playing, contextType = ContextType.GROUP, contextId = group)
            db.useWriterConnection { conn ->
                conn.execSql(
                    "UPDATE play_session SET contextAnchorEpisodeId = $anchor," +
                        " contextAnchorSortDate = 42 WHERE id = 0",
                )
            }
            // No queue.json at all — Replace still owns the whole session row.
            val staged = staged(podcasts = listOf(podcastV1("https://a/feed")))

            restore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            val s = assertNotNull(db.playSessionDao().get())
            assertNull(s.currentEpisodeId)
            assertNull(s.contextType)
            assertNull(s.contextId)
            assertNull(s.contextAnchorEpisodeId)
            assertNull(s.contextAnchorSortDate)
            // Owned increments over the seeded generation: the deleted group's context
            // clear, the pre-step's clearContext, then the Replace session write.
            assertEquals(8L, s.generation)
        }

    @Test
    fun replaceWithASessionlessQueueClearsTheWholeSession() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val playing = seedEpisode(p, "g:playing")
            val anchor = seedEpisode(p, "g:anchor")
            val group = db.groupDao().insert(groupEntity(uuid = "u-g", name = "Ctx", orderKey = "0.5"))
            seedSession(current = playing, contextType = ContextType.GROUP, contextId = group)
            db.useWriterConnection { conn ->
                conn.execSql(
                    "UPDATE play_session SET contextAnchorEpisodeId = $anchor," +
                        " contextAnchorSortDate = 42 WHERE id = 0",
                )
            }
            // queue.json is present but carries `session = null` — same clear.
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue = QueueV1(upNext = emptyList(), session = null),
                )

            restore(staged, MergePolicy.RESTORE_REPLACE, ALL)

            val s = assertNotNull(db.playSessionDao().get())
            assertNull(s.currentEpisodeId)
            assertNull(s.contextType)
            assertNull(s.contextId)
            assertNull(s.contextAnchorEpisodeId)
            assertNull(s.contextAnchorSortDate)
            assertEquals(8L, s.generation)
        }

    @Test
    fun sessionContextMapsStagedGroupUuidToItsMergedRow() =
        runTest {
            val p = seedPodcast("https://a/feed")
            // The staged group's `uuid` does not exist locally: Merge inserts the group
            // under a fresh uuid, so the session's `contextGroupUuid` can only resolve
            // through transaction A's staged uuid → survivor map.
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    groups = listOf(groupV1(uuid = "u-staged", name = "Fresh")),
                    queue =
                        QueueV1(
                            upNext = emptyList(),
                            session =
                                SessionV1(
                                    contextType = "GROUP",
                                    contextGroupUuid = "u-staged",
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val merged = assertNotNull(db.groupDao().groupByNameKey("fresh"))
            val s = assertNotNull(db.playSessionDao().get())
            assertEquals(ContextType.GROUP, s.contextType)
            assertEquals(merged.id, s.contextId)
        }

    @Test
    fun sessionUnresolvableContextAndRefsKeepNulls() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    queue =
                        QueueV1(
                            upNext = emptyList(),
                            session =
                                SessionV1(
                                    current = EpisodeRefV1("https://a/feed", "g:none"),
                                    contextType = "GROUP",
                                    contextGroupUuid = "u-nope",
                                    anchor = EpisodeRefV1("https://gone", "g:x"),
                                    anchorSortDate = 777,
                                ),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val s = assertNotNull(db.playSessionDao().get())
            assertNull(s.currentEpisodeId)
            assertNull(s.contextType)
            assertNull(s.contextId)
            // The anchor ref is unresolvable but its sort date still lands.
            assertNull(s.contextAnchorEpisodeId)
            assertEquals(777L, s.contextAnchorSortDate)
        }

    @Test
    fun sessionContextMapsAFoldedStagedUuidToTheSurvivor() =
        runTest {
            seedPodcast("https://a/feed")
            val l1 = db.groupDao().insert(groupEntity(uuid = "u-l1", name = "Different", orderKey = "0.5"))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    groups =
                        listOf(
                            // Same nameKey: `u-fold` folds into `u-l1`, which claimed the
                            // local row by uuid — Merge keeps the local name, so no live
                            // uuid/nameKey lookup can find `u-fold`'s survivor again.
                            groupV1(uuid = "u-l1", name = "Fold"),
                            groupV1(uuid = "u-fold", name = "Fold"),
                        ),
                    queue =
                        QueueV1(
                            upNext = emptyList(),
                            session = SessionV1(contextType = "GROUP", contextGroupUuid = "u-fold"),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            val s = assertNotNull(db.playSessionDao().get())
            assertEquals(ContextType.GROUP, s.contextType)
            assertEquals(l1, s.contextId)
        }

    // --- Settled library identities (05 transactions A → B/C) ----------------------------------------
    //
    // The episode phase attaches through transaction A's settled `p`/`uuid` → local-id
    // maps. A matched before any write, so the live ladder can disagree once a later
    // staged row commits — an inserted podcast's key or a written alias re-points the
    // evidence.

    @Test
    fun collidingInsertKeepsSettledPodcastIdsStagedJFirst() = runTest { collidingInsertKeepsSettledIds(jFirst = true) }

    @Test
    fun collidingInsertKeepsSettledPodcastIdsStagedIFirst() = runTest { collidingInsertKeepsSettledIds(jFirst = false) }

    /**
     * The report's colliding-identity case: staged `j` claims local `rx` by real
     * `podcastGuid` while `j`'s alias is staged `i`'s key — `i` matches nothing and
     * inserts during transaction A. The settled map must keep `j → rx` even though the
     * live ladder would now re-resolve `j`'s alias evidence to `i`'s fresh row.
     */
    private suspend fun collidingInsertKeepsSettledIds(jFirst: Boolean) {
        val rx = db.podcastDao().insertPodcast(podcastEntity { copy(podcastGuid = "urn:uuid:G-J") })
        val rxHist = seedEpisode(rx, "g:rx1")
        val rxQueued = seedEpisode(rx, "g:rx2")
        val j =
            podcastV1(
                "https://j/feed",
                aliases = listOf("https://i/feed"),
                podcastGuid = "urn:uuid:G-J",
            )
        val i = podcastV1("https://i/feed")
        val staged =
            staged(
                podcasts = if (jFirst) listOf(j, i) else listOf(i, j),
                episodeLines =
                    listOf(
                        line("https://j/feed", "g:rx1", pl = 500, ts = 700),
                        line("https://j/feed", "g:stub", u = "https://cdn/s.mp3", d = 40_000),
                        line("https://i/feed", "g:i1", u = "https://cdn/i.mp3", d = 40_000),
                    ),
                queue =
                    QueueV1(
                        upNext = listOf(UpNextRefV1("https://j/feed", "g:rx2")),
                        session =
                            SessionV1(
                                current = EpisodeRefV1("https://j/feed", "g:rx2"),
                                contextType = "PODCAST",
                                contextPodcastKey = "https://j/feed",
                            ),
                    ),
            )

        restore(staged, MergePolicy.RESTORE_MERGE, ALL)

        val ri = assertNotNull(db.podcastDao().byFeedKey("https://i/feed"))
        // j's history landed on rx's episode and its stub went to rx — never to i's row.
        assertEquals(500L, db.episodeStateDao().byEpisode(rxHist)?.playedAt)
        assertNotNull(db.episodeDao().byIdentityKey(rx, "g:stub"))
        assertNull(db.episodeDao().byIdentityKey(ri.id, "g:stub"))
        assertNotNull(db.episodeDao().byIdentityKey(ri.id, "g:i1"))
        // Queue, current and the podcast context all attach to rx.
        assertEquals(listOf(rxQueued), db.queueDao().entries().map { it.episodeId })
        val s = assertNotNull(db.playSessionDao().get())
        assertEquals(rxQueued, s.currentEpisodeId)
        assertEquals(ContextType.PODCAST, s.contextType)
        assertEquals(rx, s.contextId)
    }

    // --- Stubs, provenance, skipped lines -----------------------------------------------------------

    @Test
    fun unmatchedLineStubsWithFeedColumnsAndForeignVersionStaysPrefixed() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            line(
                                "https://a/feed",
                                "g:foreign",
                                kv = 2,
                                g = "guid-x",
                                t = "Future episode",
                                d = 40_000,
                                u = "https://cdn/x.mp3",
                                ty = "audio/mpeg",
                                dur = 61_000,
                                l = "https://p/x",
                                pl = 500,
                                ts = 700,
                            ),
                        ),
                )

            val summary = restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(1, summary.stubs)
            val stub = assertNotNull(db.episodeDao().byIdentityKey(p, "2g:foreign"))
            assertFalse(stub.inFeed)
            assertEquals("Future episode", stub.title)
            assertEquals("guid-x", stub.guid) // a match hint — never provenance
            assertEquals("https://cdn/x.mp3", stub.enclosureUrl)
            assertEquals(500L, db.episodeStateDao().byEpisode(stub.id)!!.playedAt)
            assertTrue(db.ingestDao().guidKnowledge(p).isEmpty())
        }

    @Test
    fun unmatchedLineWithoutStubEvidenceIsCounted() =
        runTest {
            seedPodcast("https://a/feed")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            // `k`-only state line: nothing to stub from, no match → counted.
                            line("https://a/feed", "g:gone", pl = 100, ts = 200),
                            // A `p` no staged key, feedKey or alias resolves → skipped line.
                            line("https://unknown/feed", "g:x", u = "https://cdn/x.mp3", ts = 1),
                        ),
                )

            val summary = restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(2, summary.episodeLines)
            assertEquals(1, summary.unmatched)
            assertEquals(1, summary.skippedLines)
            assertEquals(0, summary.stubs)
        }

    @Test
    fun corruptLinesAreSkippedByTheCodecNotTheMerge() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines = listOf(line("https://a/feed", "g:e", pl = 1, ts = 2)),
                    rawLines = listOf("{not-json", """{"p":"https://a/feed"}"""),
                )

            val summary = restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(1, summary.episodeLines)
            assertEquals(1L, db.episodeStateDao().byEpisode(e)!!.playedAt)
            assertEquals(2, staged.skippedByCodec)
        }

    @Test
    fun linesResolveThroughAliasAndGuidMatchedPodcasts() =
        runTest {
            val byAlias = seedPodcast("https://a/current")
            val e1 = seedEpisode(byAlias, "g:e1")
            val byGuid = db.podcastDao().insertPodcast(podcastEntity { copy(podcastGuid = "urn:real") })
            val e2 = seedEpisode(byGuid, "g:e2")
            val staged =
                staged(
                    podcasts =
                        listOf(
                            podcastV1("https://a/old", aliases = listOf("https://a/current")),
                            podcastV1("https://b/backup", podcastGuid = "urn:real"),
                        ),
                    episodeLines =
                        listOf(
                            line("https://a/old", "g:e1", pl = 500, ts = 700),
                            line("https://b/backup", "g:e2", pl = 600, ts = 700),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(500L, db.episodeStateDao().byEpisode(e1)!!.playedAt)
            assertEquals(600L, db.episodeStateDao().byEpisode(e2)!!.playedAt)
        }

    @Test
    fun favouriteAndTombstoneUnionUnderMerge() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e1 = seedEpisode(p, "g:e1")
            val e2 = seedEpisode(p, "g:e2")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(e1, downloadDismissedAt = 100, isFavorite = true, updatedAt = 500),
            )
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            // Older dis never wins; the local favourite is not unset.
                            line("https://a/feed", "g:e1", dis = 50, ts = 700),
                            line("https://a/feed", "g:e2", dis = 800, fav = true, ts = 700),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            db.episodeStateDao().byEpisode(e1)!!.let {
                assertEquals(100L, it.downloadDismissedAt)
                assertTrue(it.isFavorite)
            }
            db.episodeStateDao().byEpisode(e2)!!.let {
                assertEquals(800L, it.downloadDismissedAt)
                assertTrue(it.isFavorite)
            }
        }

    // --- Transactions and replay --------------------------------------------------------------------

    @Test
    fun chunksCommitIndependentlyAndReplayCompletes() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val lines =
                (1..(BackupDao.EPISODE_CHUNK + 1)).map { i ->
                    line("https://a/feed", "g:$i", u = "https://cdn/$i.mp3", pos = i * 10L, posAt = 100, ts = 1)
                }
            val staged = FailingStaged(staged(podcasts = listOf(podcastV1("https://a/feed")), episodeLines = lines))
            val progress = mutableListOf<Pair<Int, Int>>()

            staged.failAfter = BackupDao.EPISODE_CHUNK // the 1,001st line never arrives
            assertFailsWith<IllegalStateException> {
                restore(staged, MergePolicy.RESTORE_MERGE, ALL, total = lines.size) { d, t ->
                    progress += d to t
                }
            }
            // The first 1,000-line transaction committed; the interrupted second one did not.
            assertEquals(BackupDao.EPISODE_CHUNK.toLong(), countSql("SELECT COUNT(*) FROM episode"))
            assertEquals(listOf(BackupDao.EPISODE_CHUNK to lines.size), progress)

            staged.failAfter = Int.MAX_VALUE
            restore(staged, MergePolicy.RESTORE_MERGE, ALL)
            assertEquals((BackupDao.EPISODE_CHUNK + 1).toLong(), countSql("SELECT COUNT(*) FROM episode"))
            assertEquals(
                (BackupDao.EPISODE_CHUNK + 1).toLong(),
                countSql("SELECT COUNT(*) FROM episode_position"),
            )
        }

    @Test
    fun replayIsLogicallyIdempotent() =
        runTest {
            val p = seedPodcast("https://a/feed")
            val e = seedEpisode(p, "g:e")
            db.queueDao().insert(queueEntryEntity(e, "0.5"))
            val staged =
                staged(
                    podcasts = listOf(podcastV1("https://a/feed")),
                    episodeLines =
                        listOf(
                            line("https://a/feed", "g:e", pl = 300, pos = 100, posAt = 250, ts = 400),
                            line("https://a/feed", "g:new", u = "https://cdn/n.mp3", pl = 10, ts = 20),
                        ),
                    queue =
                        QueueV1(
                            upNext = listOf(UpNextRefV1("https://a/feed", "g:new")),
                            session = SessionV1(current = EpisodeRefV1("https://a/feed", "g:e")),
                        ),
                )

            restore(staged, MergePolicy.RESTORE_MERGE, ALL)
            val first = snapshot(p, "g:e", "g:new")
            restore(staged, MergePolicy.RESTORE_MERGE, ALL)

            assertEquals(first, snapshot(p, "g:e", "g:new"))
        }

    // --- Helpers -------------------------------------------------------------------------------------

    /** The caller contract: transaction A first, then the episode/queue/session phases. */
    private suspend fun restore(
        staged: StagedLibrary,
        policy: MergePolicy,
        categories: Set<RestoreCategory>,
        merger: RestoreMerger = this.merger,
        episodes: RestoreEpisodeMerger = this.episodes,
        total: Int = 0,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): MergeSummary {
        val settled = merger.mergeLibrary(staged, policy, categories)
        return episodes.mergeEpisodes(staged, settled, policy, categories, total, progress).summary
    }

    /** A restore bound to the real `OrderKey` port — the Replace `ok` cases. */
    private suspend fun realRestore(
        staged: StagedLibrary,
        policy: MergePolicy,
        categories: Set<RestoreCategory>,
    ): MergeSummary =
        restore(
            staged,
            policy,
            categories,
            merger = realMerger(),
            episodes = realEpisodes(),
        )

    private fun realMerger(): RestoreMerger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, scheduler, clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = ProtocolOrderKeys,
            clock = clock,
            downloads =
                Provider {
                    FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) }
                },
        )

    private fun realEpisodes(): RestoreEpisodeMerger =
        RestoreEpisodeMerger(
            db = db,
            episodes = EpisodeMatcher(db),
            writes = RestoreWrites(db),
            orderKeys = ProtocolOrderKeys,
            clock = clock,
        )

    private suspend fun snapshot(
        podcastId: Long,
        vararg keys: String,
    ): List<Any?> {
        val rows = keys.map { db.episodeDao().byIdentityKey(podcastId, it) }
        return listOf(
            rows,
            rows.map { it?.let { e -> db.episodeStateDao().byEpisode(e.id) } },
            rows.map { it?.let { e -> db.positionDao().byEpisode(e.id) } },
            db.queueDao().entries(),
            db.playSessionDao().get(),
        )
    }

    private suspend fun seedPodcast(feedKey: String): Long =
        db.podcastDao().insertPodcast(podcastEntity(feedKey = feedKey))

    private suspend fun seedEpisode(
        podcastId: Long,
        key: String,
        block: EpisodeEntity.() -> EpisodeEntity = { this },
    ): Long = db.episodeDao().insertStub(episodeEntity(podcastId = podcastId, identityKey = key, block = block))

    private fun position(
        episodeId: Long,
        positionMs: Long,
        at: Long,
    ) = EpisodePositionEntity(
        episodeId = episodeId,
        positionMs = positionMs,
        positionSource = PositionSource.STREAM,
        updatedAt = at,
    )

    private suspend fun seedSession(
        current: Long?,
        contextType: ContextType? = null,
        contextId: Long? = null,
    ) {
        db.playSessionDao().ensureNow(1)
        db.useWriterConnection { conn ->
            conn.execSql(
                "UPDATE play_session SET currentEpisodeId = ${current?.toString() ?: "NULL"}," +
                    " contextType = ${contextType?.let { "'${it.name}'" } ?: "NULL"}," +
                    " contextId = ${contextId?.toString() ?: "NULL"}, generation = 5 WHERE id = 0",
            )
        }
    }

    private suspend fun countSql(sql: String): Long =
        db.useReaderConnection { conn ->
            conn.usePrepared(sql) { stmt ->
                stmt.step()
                stmt.getLong(0)
            }
        }

    private fun line(
        p: String,
        k: String,
        kv: Int = 1,
        g: String? = null,
        t: String? = null,
        d: Long? = null,
        u: String? = null,
        ty: String? = null,
        dur: Long? = null,
        yt: String? = null,
        l: String? = null,
        pl: Long? = null,
        pc: Int = 0,
        st: Long? = null,
        lp: Long? = null,
        pos: Long? = null,
        posAt: Long? = null,
        posSrc: String? = null,
        fav: Boolean = false,
        dis: Long? = null,
        md: Long? = null,
        ts: Long = 0,
    ) = EpisodeLineV1(
        p = p,
        k = k,
        kv = kv,
        g = g,
        t = t,
        d = d,
        u = u,
        ty = ty,
        dur = dur,
        yt = yt,
        l = l,
        pl = pl,
        pc = pc,
        st = st,
        lp = lp,
        pos = pos,
        posAt = posAt,
        posSrc = posSrc,
        fav = fav,
        dis = dis,
        md = md,
        ts = ts,
    )

    private fun podcastV1(
        key: String,
        feedUrl: String = key,
        aliases: List<String> = emptyList(),
        podcastGuid: String? = null,
        subscribedAt: Long = 100L,
    ): PodcastV1 =
        PodcastV1(
            key = key,
            feedUrl = feedUrl,
            aliases = aliases,
            podcastGuid = podcastGuid,
            title = "T-$key",
            subscribedAt = subscribedAt,
        )

    private fun groupV1(
        uuid: String,
        name: String,
        members: List<String> = emptyList(),
    ): GroupV1 =
        GroupV1(
            uuid = uuid,
            name = name,
            sortOrder = 0,
            members = members.map { MemberV1(it, addedAt = 1L) },
        )

    private fun staged(
        podcasts: List<PodcastV1> = emptyList(),
        groups: List<GroupV1> = emptyList(),
        episodeLines: List<EpisodeLineV1> = emptyList(),
        rawLines: List<String> = emptyList(),
        queue: QueueV1? = null,
    ): CodecStaged = CodecStaged(LibraryV1(podcasts = podcasts, groups = groups), episodeLines, rawLines, queue)

    private companion object {
        const val ARCHIVE_AT = 1_650_000_000_000L
        val ALL = RestoreCategory.entries.toSet()
    }
}

/**
 * A `StagedLibrary` over real `BackupCodec` bytes: `library.json`/`queue.json` decode
 * back from their encoded text and `episodes.jsonl` streams through `EpisodeLines`, so
 * the codec's skipped-line contract is exercised, not mocked.
 */
private class CodecStaged(
    library: LibraryV1,
    episodeLines: List<EpisodeLineV1>,
    rawLines: List<String>,
    private val queue: QueueV1?,
    override val createdAtMs: Long = 1_650_000_000_000L,
) : StagedLibrary {
    private val codec = BackupCodec()
    private val libraryText = codec.encodeLibrary(library)
    private val queueText = queue?.let(codec::encodeQueue)
    private val episodesText =
        buildString {
            val sink = Buffer()
            for (line in episodeLines) codec.writeEpisodeLine(sink, line)
            append(sink.readUtf8())
            for (raw in rawLines) append(raw).append('\n')
        }

    /** Lines the codec skipped as corrupt or oversized on the last streamed pass. */
    var skippedByCodec = 0
        private set

    override suspend fun library(): LibraryV1 = codec.decodeLibrary(libraryText.byteInputStream().source())

    override suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit) {
        codec.episodeLines(episodesText.byteInputStream().source()).use { lines ->
            for (line in lines) block(line)
            skippedByCodec = lines.skippedLines
        }
    }

    override suspend fun queue(): QueueV1? = queueText?.let { codec.decodeQueue(it.byteInputStream().source()) }

    override suspend fun settings(): SettingsV1? = null
}

/** Wraps a [CodecStaged] so its stream can die after [failAfter] lines (mid-transaction stop). */
private class FailingStaged(
    private val delegate: CodecStaged,
    var failAfter: Int = Int.MAX_VALUE,
) : StagedLibrary by delegate {
    override suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit) {
        var seen = 0
        delegate.forEachEpisodeLine { line ->
            check(seen++ != failAfter) { "simulated stream failure" }
            block(line)
        }
    }
}
