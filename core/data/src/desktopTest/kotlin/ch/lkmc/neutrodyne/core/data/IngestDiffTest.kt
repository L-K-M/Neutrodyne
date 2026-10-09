// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.database.ChapterEntity
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN M1 acceptance 3/6 — the diff algorithm of 03 "Ingestion and diff" against an in-memory
 * database: accepted items, both match passes, `isNew` and the dump guard, column rules on
 * update, `inFeed` flips, warnings and transaction integrity.
 */
class IngestDiffTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val ingestor = newIngestor(db, clock)

    private suspend fun podcastId(
        feedUrl: String = "https://example.com/feed.xml",
        subscribedAt: Long = NOW,
        initialFetch: Boolean = false,
        status: PodcastStatus = PodcastStatus.ACTIVE,
        latestEpisodeAt: Long? = null,
    ): Long =
        seedPodcast(
            db,
            feedUrl = feedUrl,
            feedKey = feedUrl,
            subscribedAt = subscribedAt,
            initialFetch = initialFetch,
            status = status,
            latestEpisodeAt = latestEpisodeAt,
        )

    private suspend fun ingest(
        id: Long,
        parsed: ParsedFeed,
        mode: IngestMode = IngestMode.REFRESH,
        partial: Boolean = false,
        meta: ch.lkmc.neutrodyne.core.data.ingest.FetchMeta = fetchMeta(),
        absenceFloor: Long? = null,
    ) = ingestor.ingest(
        dueFeedOf(db, id),
        parsed,
        IngestContext(mode = mode, partial = partial, fetch = meta, absenceFloor = absenceFloor),
    )

    // --- Accepted items and document keys -------------------------------------------------------

    @Test
    fun initialIngestInsertsEpisodesAndWritesMetadata() =
        runTest {
            val id = podcastId()
            val feed =
                parsedFeed(
                    title = "My Show",
                    items =
                        listOf(
                            parsedEpisode(0, guid = "a", pubDate = NOW - DAY),
                            parsedEpisode(1, guid = "b", pubDate = NOW - 2 * DAY),
                        ),
                )

            val result =
                ingest(id, feed, mode = IngestMode.INITIAL, meta = fetchMeta(etag = "e1", sha256Hex = "f".repeat(64)))

            assertEquals(2, result.inserted.size)
            assertEquals(2, result.accepted)
            assertEquals(emptyList(), result.newIds)
            val a = db.episodeDao().byId(result.inserted[0])!!
            assertEquals("g:a", a.identityKey)
            assertTrue(a.inFeed)
            assertFalse(a.isNew)

            val stored = db.podcastDao().byId(id)!!
            assertEquals("My Show", stored.title)
            assertEquals("e1", stored.etag)
            assertEquals("f".repeat(64), stored.contentSha256)
            assertEquals(FeedParser.VERSION, stored.parserVersion)
            assertTrue(stored.lastParseOk)
            assertEquals(NOW, stored.lastSuccessAt)
            assertTrue((stored.nextRefreshAt ?: 0) > NOW)
            assertEquals(NOW - DAY, stored.latestEpisodeAt)
        }

    @Test
    fun pendingPodcastIngestActivatesAndClearsInitialFetch() =
        runTest {
            val id =
                podcastId(
                    status = PodcastStatus.PENDING_FIRST_FETCH,
                    initialFetch = true,
                )
            val result =
                ingest(id, parsedFeed(items = listOf(parsedEpisode(0, guid = "a"))), mode = IngestMode.INITIAL)

            assertTrue(result.firstIngest)
            val stored = db.podcastDao().byId(id)!!
            assertEquals(PodcastStatus.ACTIVE, stored.status)
            assertFalse(stored.initialFetch)
        }

    @Test
    fun duplicateGuidsInOneDocumentGetFallbackKeys() =
        runTest {
            val id = podcastId()
            val feed =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", enclosureUrl = "https://cdn.example.com/a.mp3"),
                            parsedEpisode(1, guid = "dup", enclosureUrl = "https://cdn.example.com/b.mp3"),
                            parsedEpisode(
                                2,
                                guid = "dup",
                                enclosureUrl = null,
                                title = "T2",
                                pubDate = NOW,
                                externalMediaId = "m-x",
                            ),
                        ),
                )

            val result = ingest(id, feed, mode = IngestMode.INITIAL)

            assertEquals(3, result.inserted.size)
            val keys = db.ingestDao().existing(id).map { it.identityKey }
            assertEquals(3, keys.distinct().size)
            assertTrue(keys.any { it == "g:dup" })
            assertTrue(result.warnings.any { it.code == WarningCode.DUPLICATE_GUID })
        }

    @Test
    fun identicalItemsCollapseBeforeFallbackKeys() =
        runTest {
            val id = podcastId()
            // The same item twice (same GUID, same content): the repeat is dropped outright, not
            // given a fallback key (03 in-document dedupe; S2).
            val same =
                parsedEpisode(
                    0,
                    guid = "d",
                    enclosureUrl = "https://cdn.example.com/s.mp3",
                    title = "Same",
                    pubDate = NOW - DAY,
                )
            val result =
                ingest(
                    id,
                    parsedFeed(items = listOf(same, same.copy(feedOrder = 1))),
                    mode = IngestMode.INITIAL,
                )

            assertEquals(1, result.accepted)
            assertEquals(1, result.inserted.size)
            assertEquals(1, db.podcastDao().episodeCount(id))
        }

    @Test
    fun reusedGuidKeepsUserStateOnTheEnclosureMatch() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "g",
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val stored = db.episodeDao().byIdentityKey(id, "g:g")!!
            db.episodeStateDao().upsert(episodeStateEntity(stored.id, playedAt = NOW - 1_000))

            // v2: two items reuse "g"; only the second still points at the stored enclosure.
            // Doc order alone would hand the stored row — and its user state — to the first,
            // distinct episode (S1); the enclosure contest keeps it with the real match.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/a.mp3",
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val kept = db.episodeDao().byId(stored.id)!!
            assertEquals("g:g", kept.identityKey)
            assertEquals("https://cdn.example.com/a.mp3", kept.enclosureUrl)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(stored.id)!!.playedAt)
            val fresh = db.episodeDao().byId(result.inserted.single())!!
            assertEquals("https://cdn.example.com/b.mp3", fresh.enclosureUrl)
            db.episodeStateDao().upsert(episodeStateEntity(fresh.id, playedAt = NOW - 2_000))

            // v3: the sibling returns alone. Stored state already proves "g" is reused (two rows
            // carry it), so the blind "g:" claim is skipped — a wrong claim would move A's row,
            // user state and all, to B (r2 F1). B instead matches its own fallback-keyed row and
            // A's row only leaves the feed.
            val v3 =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                ),
                            ),
                    ),
                )

            assertTrue(v3.inserted.isEmpty())
            val aRow = db.episodeDao().byId(stored.id)!!
            assertEquals("g:g", aRow.identityKey)
            assertEquals("https://cdn.example.com/a.mp3", aRow.enclosureUrl)
            assertFalse(aRow.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(stored.id)!!.playedAt)
            val bRow = db.episodeDao().byId(fresh.id)!!
            assertEquals("https://cdn.example.com/b.mp3", bRow.enclosureUrl)
            assertTrue(bRow.inFeed)
            assertEquals(NOW - 2_000, db.episodeStateDao().byEpisode(fresh.id)!!.playedAt)
        }

    /**
     * A and B share "dup" — A owns the `g:` row, B a `u:` fallback. The next document
     * rotates A's enclosure query: the reuse guard rejects A's blind `g:` claim, and the
     * rejected row must stay eligible for pass 2's query-less match — reserving it would
     * insert a fresh row and strand the played state on the stale one (r3 F1).
     */
    @Test
    fun reusedGuidWithRotatedEnclosureStillUpdatesItsRow() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "dup",
                                enclosureUrl = "https://cdn.example.com/a.mp3?token=old",
                                title = "A",
                                pubDate = NOW - DAY,
                            ),
                            parsedEpisode(
                                1,
                                guid = "dup",
                                enclosureUrl = "https://cdn.example.com/b.mp3",
                                title = "B",
                                pubDate = NOW - 2 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:dup")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))
            val bBefore =
                db.ingestDao().existing(id).single {
                    it.enclosureUrl == "https://cdn.example.com/b.mp3"
                }

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/a.mp3?token=new",
                                    title = "A",
                                    pubDate = NOW - DAY,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                    title = "B",
                                    pubDate = NOW - 2 * DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(emptyList(), result.inserted)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:dup", aAfter.identityKey)
            assertEquals("https://cdn.example.com/a.mp3?token=new", aAfter.enclosureUrl)
            assertTrue(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertTrue(db.episodeDao().byId(bBefore.id)!!.inFeed)
            assertEquals(2, db.podcastDao().episodeCount(id))
        }

    /**
     * A single stored `g:` row carries no stored evidence that "dup" is reused — the
     * incoming document supplies it instead: two distinct items share the guid while A
     * aged out, so neither may claim A's row blindly. The guard's enclosure check
     * rejects both: A keeps row and state, B and C insert separately (r3 F2).
     */
    @Test
    fun documentDuplicateGuidsKeepTheAgedOutRowsState() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "dup",
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                                title = "A",
                                pubDate = NOW - 10 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:dup")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                    title = "B",
                                    pubDate = NOW - DAY,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/c.mp3",
                                    title = "C",
                                    pubDate = NOW - 2 * DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(2, result.inserted.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:dup", aAfter.identityKey)
            assertEquals("https://cdn.example.com/a.mp3", aAfter.enclosureUrl)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertEquals(
                3,
                db
                    .ingestDao()
                    .existing(id)
                    .map { it.identityKey }
                    .distinct()
                    .size,
            )
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    /**
     * The document's first "dup" item is a different episode; the true continuation
     * arrives second under a fallback document key and a rotated enclosure query. Pass
     * 2's query-less match must give the row — its `g:` key, its id and its state — to
     * the sibling whose enclosure it carries (the r3 F1/F2 matrix cell).
     */
    @Test
    fun documentDuplicateGuidHandsTheRowToTheQueryLessSibling() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "dup",
                                enclosureUrl = "https://cdn.example.com/a.mp3?token=old",
                                title = "A",
                                pubDate = NOW - 10 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:dup")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "X",
                                    pubDate = NOW - DAY,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/a.mp3?token=new",
                                    title = "A",
                                    pubDate = NOW - 10 * DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:dup", aAfter.identityKey)
            assertEquals("https://cdn.example.com/a.mp3?token=new", aAfter.enclosureUrl)
            assertTrue(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertEquals(
                "https://cdn.example.com/x.mp3",
                db.episodeDao().byId(result.inserted.single())!!.enclosureUrl,
            )
        }

    /**
     * Stored evidence of reuse (two rows carry "dup"): a document offering a third,
     * distinct episode under the same guid claims nothing — the reused rows only flip
     * out of the feed and no state moves.
     */
    @Test
    fun aNewItemSharingAReusedGuidInsertsBesideTheStoredRows() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "dup",
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                                title = "A",
                                pubDate = NOW - DAY,
                            ),
                            parsedEpisode(
                                1,
                                guid = "dup",
                                enclosureUrl = "https://cdn.example.com/b.mp3",
                                title = "B",
                                pubDate = NOW - 2 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:dup")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))
            val bBefore =
                db.ingestDao().existing(id).single {
                    it.enclosureUrl == "https://cdn.example.com/b.mp3"
                }

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "dup",
                                    enclosureUrl = "https://cdn.example.com/c.mp3",
                                    title = "C",
                                    pubDate = NOW - DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:dup", aAfter.identityKey)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertFalse(db.episodeDao().byId(bBefore.id)!!.inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    /**
     * Two rows share "dup-old" — A owns the `g:` key, B a `u:` fallback. Both GUIDs rotate
     * together with the content otherwise identical: the fallback-keyed row's stored `guid`
     * must still follow the document, even though step 6's `contentHash` gate (which excludes
     * the GUID) writes nothing. When B then returns alone, the rotated GUID makes A's `g:`
     * row off limits — B reclaims its own row and A flips out with its state intact.
     */
    @Test
    fun rotatedSharedGuidSyncsFallbackRowAndKeepsStates() =
        runTest {
            val id = podcastId()

            fun doc(
                guid: String,
                onlyB: Boolean = false,
            ) = parsedFeed(
                items =
                    listOfNotNull(
                        if (onlyB) {
                            null
                        } else {
                            parsedEpisode(
                                0,
                                guid = guid,
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                                title = "A",
                                pubDate = NOW - DAY,
                            )
                        },
                        parsedEpisode(
                            1,
                            guid = guid,
                            enclosureUrl = "https://cdn.example.com/b.mp3",
                            title = "B",
                            pubDate = NOW - 2 * DAY,
                        ),
                    ),
            )
            ingest(id, doc("dup-old"), mode = IngestMode.INITIAL)
            val aBefore = db.episodeDao().byIdentityKey(id, "g:dup-old")!!
            val bBefore =
                db.ingestDao().existing(id).single {
                    it.enclosureUrl == "https://cdn.example.com/b.mp3"
                }
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))
            db.episodeStateDao().upsert(episodeStateEntity(bBefore.id) { copy(isFavorite = true) })

            // v2: both GUIDs rotate, content unchanged — the hash gate fires no row update.
            val second = ingest(id, doc("dup-new"))

            assertEquals(emptyList(), second.inserted)
            assertEquals(0, second.updated)
            // Only A's pass-2 rekey counts; B's guid write leaves the key untouched.
            assertEquals(1, second.rekeyed)
            val aRotated = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:dup-new", aRotated.identityKey)
            assertEquals("dup-new", aRotated.guid)
            val bRotated = db.episodeDao().byId(bBefore.id)!!
            assertEquals(bBefore.identityKey, bRotated.identityKey)
            assertEquals("dup-new", bRotated.guid)

            // v3: B returns alone under the rotated GUID.
            val third = ingest(id, doc("dup-new", onlyB = true))

            assertEquals(emptyList(), third.inserted)
            assertEquals(0, third.updated)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:dup-new", aAfter.identityKey)
            assertEquals("A", aAfter.title)
            assertEquals("https://cdn.example.com/a.mp3", aAfter.enclosureUrl)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            val bAfter = db.episodeDao().byId(bBefore.id)!!
            assertEquals(bBefore.identityKey, bAfter.identityKey)
            assertEquals("B", bAfter.title)
            assertEquals("https://cdn.example.com/b.mp3", bAfter.enclosureUrl)
            assertTrue(bAfter.inFeed)
            assertTrue(db.episodeStateDao().byEpisode(bBefore.id)!!.isFavorite)
            assertEquals(2, db.podcastDao().episodeCount(id))
        }

    // --- isNew and the back-catalogue guard -----------------------------------------------------

    @Test
    fun initialIngestNeverMarksNew() =
        runTest {
            val id = podcastId()
            val feed =
                parsedFeed(
                    items = listOf(parsedEpisode(0, guid = "a", pubDate = NOW)),
                )
            ingest(id, feed, mode = IngestMode.INITIAL)
            assertFalse(db.episodeDao().byIdentityKey(id, "g:a")!!.isNew)
        }

    @Test
    fun refreshMarksRecentItemsNewAndSkipsOld() =
        runTest {
            val id = podcastId(subscribedAt = NOW - 30 * DAY)
            val feed =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "new", pubDate = NOW - DAY),
                            // Older than subscribedAt - 7 days: a re-published back-catalogue item.
                            parsedEpisode(1, guid = "old", pubDate = NOW - 38 * DAY),
                        ),
                )

            val result = ingest(id, feed)

            val newRow = db.episodeDao().byIdentityKey(id, "g:new")!!
            val oldRow = db.episodeDao().byIdentityKey(id, "g:old")!!
            assertTrue(newRow.isNew)
            assertFalse(oldRow.isNew)
            assertEquals(listOf(newRow.id), result.newIds)
        }

    @Test
    fun outOfRangePubDatesAreTreatedAsUndatedForSortAndNewness() =
        runTest {
            val id = podcastId(subscribedAt = NOW - 30 * DAY)
            // 1973 is below the 1990 floor; now+400d is past the now+365d ceiling — both count
            // as undated under `pubDateValid` (03 sortDate and clock): sortDate = firstSeenAt
            // and the newness check uses `now`, like an item with no date at all.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "pre", pubDate = 100_000_000_000L),
                                parsedEpisode(1, guid = "post", pubDate = NOW + 400 * DAY),
                            ),
                    ),
                )

            val pre = db.episodeDao().byIdentityKey(id, "g:pre")!!
            val post = db.episodeDao().byIdentityKey(id, "g:post")!!
            assertEquals(pre.firstSeenAt, pre.sortDate)
            assertEquals(post.firstSeenAt, post.sortDate)
            assertTrue(pre.isNew)
            assertTrue(post.isNew)
            assertEquals(setOf(pre.id, post.id), result.newIds.toSet())
            // The raw pubDate stays on the row — only sort/newness treats it as undated.
            assertEquals(100_000_000_000L, pre.pubDate)
        }

    @Test
    fun dumpOfNewItemsKeepsAtMostThreeNew() =
        runTest {
            val id = podcastId(subscribedAt = NOW - 30 * DAY)
            val items =
                (0 until 25).map { i ->
                    parsedEpisode(i, guid = "g$i", pubDate = NOW - i * 60 * 60 * 1_000L)
                }

            val result = ingest(id, parsedFeed(items = items))

            assertEquals(3, result.newIds.size)
            assertTrue(result.warnings.any { it.code == WarningCode.BACK_CATALOGUE_DUMP })
            // The newest three by (sortDate, feedOrder): feed order 0, 1, 2 published most recently.
            val stored = (0 until 25).map { db.episodeDao().byIdentityKey(id, "g:g$it")!! }
            assertEquals(listOf(true, true, true) + List(22) { false }, stored.map { it.isNew })
        }

    // --- Matching: pass 1 and pass 2 ------------------------------------------------------------

    @Test
    fun rewrittenGuidsMatchByEnclosureAndKeepState() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "g1",
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                                pubDate =
                                    NOW - DAY,
                            ),
                            parsedEpisode(
                                1,
                                guid = "g2",
                                enclosureUrl = "https://cdn.example.com/b.mp3",
                                pubDate =
                                    NOW - 2 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:g1")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            // v2: the publisher rewrote every GUID; enclosures stayed.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "x1",
                                    enclosureUrl = "https://cdn.example.com/a.mp3",
                                    pubDate =
                                        NOW - DAY,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "x2",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                    pubDate =
                                        NOW - 2 * DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(2, result.rekeyed)
            assertEquals(emptyList(), result.inserted)
            val aAfter = db.episodeDao().byIdentityKey(id, "g:x1")!!
            assertEquals(aBefore.id, aAfter.id)
            assertEquals("x1", aAfter.guid)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
        }

    @Test
    fun enclosureQueryRotationMatchesQueryLessAndRekeys() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/ep.mp3?token=old",
                                title = "Solo",
                                pubDate = NOW - DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.ingestDao().existing(id).single()

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/ep.mp3?token=new",
                                    title = "Solo",
                                    pubDate = NOW - DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.rekeyed)
            val after = db.ingestDao().existing(id).single()
            assertEquals(before.id, after.id)
            assertNotEquals(before.identityKey, after.identityKey)
            assertTrue(after.identityKey.startsWith("u:"))
        }

    @Test
    fun enclosurePrefixRotationMatchesByTitleAndDay() =
        runTest {
            val id = podcastId()
            val pubDate = NOW - 2 * DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://old-cdn.example.com/a/ep.mp3",
                                title = "Fixed title",
                                pubDate = pubDate,
                                durationMs = 1_000,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.ingestDao().existing(id).single()

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://new-cdn.example.com/z/ep.mp3",
                                    title = "Fixed title",
                                    pubDate = pubDate,
                                    durationMs = 1_000,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.rekeyed)
            val after = db.ingestDao().existing(id).single()
            assertEquals(before.id, after.id)
            assertEquals("https://new-cdn.example.com/z/ep.mp3", after.enclosureUrl)
        }

    // --- Pass 2 as global strength tiers (deviation 14) -------------------------------------------

    /**
     * r4 F1: a rolling feed serves episodes through an episode-selecting query —
     * `/download?id=N` — so every stored row shares the query-less URL. Aged-out A and new
     * B differ in GUID, title and day: without corroboration the query-less relation must
     * not hand A's row, its played state and `firstSeenAt` to B.
     */
    @Test
    fun rollingQueryParamFeedKeepsTheAgedOutRowAndInserts() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "e101",
                                enclosureUrl = "https://cdn.example.com/download?id=101",
                                title = "Daily 101",
                                pubDate = NOW - 10 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:e101")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "e102",
                                    enclosureUrl = "https://cdn.example.com/download?id=102",
                                    title = "Daily 102",
                                    pubDate = NOW - DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            // B is a genuinely new episode: it must surface in newIds (the conflation suppressed it).
            assertEquals(1, result.newIds.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("https://cdn.example.com/download?id=101", aAfter.enclosureUrl)
            assertEquals("Daily 101", aAfter.title)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertEquals(2, db.podcastDao().episodeCount(id))
        }

    /**
     * The legitimate query rotation the corroboration requirement must not break: same episode
     * (title and day agree), only the token rotated — the row updates in place and keeps its
     * user state.
     */
    @Test
    fun enclosureTokenRotationUpdatesInPlaceAndKeepsUserState() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/a.mp3?token=old",
                                title = "Solo",
                                pubDate = NOW - DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.ingestDao().existing(id).single()
            db.episodeStateDao().upsert(episodeStateEntity(before.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/a.mp3?token=new",
                                    title = "Solo",
                                    pubDate = NOW - DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(emptyList(), result.inserted)
            assertEquals(1, result.rekeyed)
            val after = db.episodeDao().byId(before.id)!!
            assertEquals("https://cdn.example.com/a.mp3?token=new", after.enclosureUrl)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(before.id)!!.playedAt)
        }

    /**
     * r4 F2 (a regression from 8ce528c): stored A (09:00) and sibling C share the reused GUID
     * "g"; the document offers [B at 10:00, A at 09:00 with a rotated token]. The guard rejects
     * both `g:` claims, and B's title+UTC-day relation is satisfied by A's row too — but a
     * complete stronger tier must run first: A's query-less enclosure claims its own row and
     * B's weaker title/day match falls through to an insert.
     */
    @Test
    fun weakerTitleDayMatchCannotPreemptAStrongerEnclosureMatch() =
        runTest {
            val id = podcastId()
            val dayStart = NOW - NOW % DAY - DAY
            val at9 = dayStart + 9 * 3_600_000L
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "g",
                                enclosureUrl = "https://cdn.example.com/a.mp3?token=old",
                                title = "News",
                                pubDate = at9,
                            ),
                            parsedEpisode(
                                1,
                                guid = "g",
                                enclosureUrl = "https://cdn.example.com/c.mp3",
                                title = "Earlier",
                                pubDate = NOW - 2 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:g")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))
            val cBefore =
                db.ingestDao().existing(id).single {
                    it.enclosureUrl == "https://cdn.example.com/c.mp3"
                }

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                    title = "News",
                                    pubDate = at9 + 3_600_000L,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/a.mp3?token=new",
                                    title = "News",
                                    pubDate = at9,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("g:g", aAfter.identityKey)
            assertEquals("https://cdn.example.com/a.mp3?token=new", aAfter.enclosureUrl)
            assertTrue(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            // The inserted row is B — the document's genuinely new item.
            assertEquals(
                "https://cdn.example.com/b.mp3",
                db.episodeDao().byId(result.inserted.single())!!.enclosureUrl,
            )
            val cAfter = db.episodeDao().byId(cBefore.id)!!
            assertFalse(cAfter.inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    /**
     * The uniqueness half of r4 F1: two stored rows share the query-less URL *and* the
     * title/day — an incoming `/download?id=103` relates to both, so it claims neither and
     * inserts beside them.
     */
    @Test
    fun ambiguousQueryLessRowsAreNeverClaimed() =
        runTest {
            val id = podcastId()
            val dayStart = NOW - NOW % DAY - DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "d101",
                                enclosureUrl = "https://cdn.example.com/download?id=101",
                                title = "Daily",
                                pubDate = dayStart + 9 * 3_600_000L,
                            ),
                            parsedEpisode(
                                1,
                                guid = "d102",
                                enclosureUrl = "https://cdn.example.com/download?id=102",
                                title = "Daily",
                                pubDate = dayStart + 10 * 3_600_000L,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val a1 = db.episodeDao().byIdentityKey(id, "g:d101")!!
            val a2 = db.episodeDao().byIdentityKey(id, "g:d102")!!
            db.episodeStateDao().upsert(episodeStateEntity(a1.id, playedAt = NOW - 1_000))
            db.episodeStateDao().upsert(episodeStateEntity(a2.id, playedAt = NOW - 2_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "d103",
                                    enclosureUrl = "https://cdn.example.com/download?id=103",
                                    title = "Daily",
                                    pubDate = dayStart + 11 * 3_600_000L,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            assertEquals("g:d103", db.episodeDao().byId(result.inserted.single())!!.identityKey)
            assertEquals(
                "https://cdn.example.com/download?id=101",
                db.episodeDao().byId(a1.id)!!.enclosureUrl,
            )
            assertEquals(
                "https://cdn.example.com/download?id=102",
                db.episodeDao().byId(a2.id)!!.enclosureUrl,
            )
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a1.id)!!.playedAt)
            assertEquals(NOW - 2_000, db.episodeStateDao().byEpisode(a2.id)!!.playedAt)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    // --- Weak evidence prefers a duplicate; tiers iterate (deviation 16) --------------------------

    /**
     * r5 F1's first half: a rolling `/download?id=N` feed replaces "News" (09:00) with
     * "Weather" (10:00) on the same UTC day. Under the round-4 tiers the shared query-less
     * URL plus the shared day alone corroborated, so the new episode inherited the old row's
     * played state; under deviation 16 the query-less relation needs title *and* day, so the
     * new episode inserts and the old row keeps its identity, fields and state while leaving
     * the feed.
     */
    @Test
    fun sameDayRollingFeedInsertsInsteadOfTransferringState() =
        runTest {
            val id = podcastId()
            val dayStart = NOW - NOW % DAY - DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "e101",
                                enclosureUrl = "https://cdn.example.com/download?id=101",
                                title = "News",
                                pubDate = dayStart + 9 * 3_600_000L,
                                durationMs = 180_000,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:e101")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "e102",
                                    enclosureUrl = "https://cdn.example.com/download?id=102",
                                    title = "Weather",
                                    pubDate = dayStart + 10 * 3_600_000L,
                                    durationMs = 180_000,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("https://cdn.example.com/download?id=101", aAfter.enclosureUrl)
            assertEquals("News", aAfter.title)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertEquals(2, db.podcastDao().episodeCount(id))
        }

    /**
     * r5 F1's second half: the same generic title "Daily" on a *different* UTC day also used
     * to corroborate the rolling `/download?id=N` URL. Title alone never corroborates either —
     * a visible duplicate row beats a silent state transfer.
     */
    @Test
    fun sameGenericTitleOnAnotherDayInsertsInsteadOfTransferringState() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "n201",
                                enclosureUrl = "https://cdn.example.com/download?id=201",
                                title = "Daily",
                                pubDate = NOW - 10 * DAY,
                                durationMs = 180_000,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:n201")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "n202",
                                    enclosureUrl = "https://cdn.example.com/download?id=202",
                                    title = "Daily",
                                    pubDate = NOW - DAY,
                                    durationMs = 180_000,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val aAfter = db.episodeDao().byId(aBefore.id)!!
            assertEquals("https://cdn.example.com/download?id=201", aAfter.enclosureUrl)
            assertEquals("Daily", aAfter.title)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
            assertEquals(2, db.podcastDao().episodeCount(id))
        }

    /**
     * r5 F2's scenario under the new rules: two GUID-less items "News" and "Weather" share a
     * UTC day and the query-less `/download` URL. When both tokens rotate and "News" is
     * renamed in the same refresh, the renamed+rotated item keeps no evidence identifying
     * its old row and inserts as a duplicate — deviation 16's accepted consequence — while
     * "Weather" still claims its own row. No row's state moves to a different episode.
     */
    @Test
    fun renamedAndTokenRotatedItemInsertsBesideItsOldRow() =
        runTest {
            val id = podcastId()
            val day = NOW - DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/download?id=101&token=old",
                                title = "News",
                                pubDate = day,
                            ),
                            parsedEpisode(
                                1,
                                enclosureUrl = "https://cdn.example.com/download?id=102&token=old",
                                title = "Weather",
                                pubDate = day,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val newsBefore =
                db.ingestDao().existing(id).single {
                    it.enclosureUrl == "https://cdn.example.com/download?id=101&token=old"
                }
            val weatherBefore =
                db.ingestDao().existing(id).single {
                    it.enclosureUrl == "https://cdn.example.com/download?id=102&token=old"
                }
            db.episodeStateDao().upsert(episodeStateEntity(newsBefore.id, playedAt = NOW - 1_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/download?id=101&token=new",
                                    title = "News (updated)",
                                    pubDate = day,
                                ),
                                parsedEpisode(
                                    1,
                                    enclosureUrl = "https://cdn.example.com/download?id=102&token=new",
                                    title = "Weather",
                                    pubDate = day,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            assertEquals("News (updated)", db.episodeDao().byId(result.inserted.single())!!.title)
            // "News"'s old row keeps its identity, its columns and its played state.
            val newsAfter = db.episodeDao().byId(newsBefore.id)!!
            assertEquals("https://cdn.example.com/download?id=101&token=old", newsAfter.enclosureUrl)
            assertEquals("News", newsAfter.title)
            assertFalse(newsAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(newsBefore.id)!!.playedAt)
            // "Weather" claims its own row: a token rotation keeps title and day.
            val weatherAfter = db.episodeDao().byId(weatherBefore.id)!!
            assertEquals("https://cdn.example.com/download?id=102&token=new", weatherAfter.enclosureUrl)
            assertTrue(weatherAfter.inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    /**
     * Deviation 16's iteration (r5 F2): a claim can resolve an ambiguity an earlier pass left
     * behind. Stored: two same-title same-day rows `x.mp3`/`y.mp3` and a "Wild" row sharing
     * `x.mp3` under a `t:` key. Document: "Zed" (`x.mp3`) is tier-A-ambiguous between the two
     * `x.mp3` rows; a new "Daily" item (`gone.mp3`) is title/day-ambiguous over both
     * same-title same-day rows. Pass one's tier C hands "Wild" its row; on the *next* pass
     * tier A claims one of the `x.mp3` rows for "Zed" first, then tier C hands the other
     * "Daily" row to the new item. Without iteration both leftovers insert as duplicates.
     */
    @Test
    fun aLaterClaimResolvesAnEarlierAmbiguityOnTheNextPass() =
        runTest {
            val id = podcastId()
            val day = NOW - DAY
            val otherDay = NOW - 2 * DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                title = "Daily",
                                pubDate = day,
                            ),
                            parsedEpisode(
                                1,
                                enclosureUrl = "https://cdn.example.com/y.mp3",
                                title = "Daily",
                                pubDate = day,
                            ),
                            parsedEpisode(
                                2,
                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                title = "Wild",
                                pubDate = otherDay,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val dailyX =
                db.ingestDao().existing(id).single {
                    it.identityKey == "u:cdn.example.com/x.mp3"
                }
            val dailyY =
                db.ingestDao().existing(id).single {
                    it.identityKey == "u:cdn.example.com/y.mp3"
                }
            val wild =
                db.ingestDao().existing(id).single {
                    it.identityKey.startsWith("t:")
                }
            db.episodeStateDao().upsert(episodeStateEntity(dailyX.id, playedAt = NOW - 1_000))
            db.episodeStateDao().upsert(episodeStateEntity(dailyY.id, playedAt = NOW - 2_000))

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "z",
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "Zed",
                                    pubDate = NOW - 3 * DAY,
                                ),
                                parsedEpisode(
                                    1,
                                    enclosureUrl = "https://cdn.example.com/gone.mp3",
                                    title = "Daily",
                                    pubDate = day,
                                ),
                                parsedEpisode(
                                    2,
                                    enclosureUrl = "https://cdn.example.com/w2.mp3",
                                    title = "Wild",
                                    pubDate = otherDay,
                                ),
                            ),
                    ),
                )

            assertEquals(emptyList(), result.inserted)
            // "Zed" took the u: row once "Wild" left it alone; both rows keep their state.
            val dailyXAfter = db.episodeDao().byId(dailyX.id)!!
            assertEquals("Zed", dailyXAfter.title)
            assertTrue(dailyXAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(dailyX.id)!!.playedAt)
            val dailyYAfter = db.episodeDao().byId(dailyY.id)!!
            assertEquals("https://cdn.example.com/gone.mp3", dailyYAfter.enclosureUrl)
            assertTrue(dailyYAfter.inFeed)
            assertEquals(NOW - 2_000, db.episodeStateDao().byEpisode(dailyY.id)!!.playedAt)
            val wildAfter = db.episodeDao().byId(wild.id)!!
            assertEquals("https://cdn.example.com/w2.mp3", wildAfter.enclosureUrl)
            assertTrue(wildAfter.inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    // --- Column rules on update ------------------------------------------------------------------

    @Test
    fun titleEditRewritesFeedColumnsAndChildren() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(items = listOf(parsedEpisode(0, guid = "a", title = "Old", descriptionHtml = "d1"))),
                mode = IngestMode.INITIAL,
            )
            val before = db.episodeDao().byIdentityKey(id, "g:a")!!

            val result =
                ingest(
                    id,
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "a", title = "New", descriptionHtml = "d2"))),
                )

            assertEquals(1, result.updated)
            val after = db.episodeDao().byId(before.id)!!
            assertEquals("New", after.title)
            assertNotEquals(before.contentHash, after.contentHash)
        }

    @Test
    fun absentFieldsPreserveStoredColumns() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "a",
                                title = "Same",
                                durationMs = 3_600_000,
                                descriptionHtml = "v1",
                            ).copy(artwork = listOf(artwork("https://img.example.com/ep.png"))),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.episodeDao().byIdentityKey(id, "g:a")!!
            assertEquals(3_600_000, before.durationMs)
            assertEquals("https://img.example.com/ep.png", before.imageUrl)

            // Same item but the doc dropped duration/artwork and changed the description.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "a", title = "Same", durationMs = null, descriptionHtml = "v2"),
                            ),
                    ),
                )

            assertEquals(1, result.updated)
            val after = db.episodeDao().byId(before.id)!!
            assertEquals(3_600_000, after.durationMs)
            assertEquals("https://img.example.com/ep.png", after.imageUrl)
        }

    @Test
    fun futurePubDateClampsSortDateToFirstSeenPlusDay() =
        runTest {
            val id = podcastId()
            val feed = parsedFeed(items = listOf(parsedEpisode(0, guid = "a", pubDate = NOW + 10 * DAY)))
            ingest(id, feed, mode = IngestMode.INITIAL)
            val row = db.episodeDao().byIdentityKey(id, "g:a")!!
            assertEquals(row.firstSeenAt + DAY, row.sortDate)
        }

    @Test
    fun serverDateCorrectsFirstSeenAt() =
        runTest {
            val id = podcastId()
            val serverNow = NOW - 40 * DAY
            val feed = parsedFeed(items = listOf(parsedEpisode(0, guid = "a", pubDate = null)))
            ingest(id, feed, mode = IngestMode.INITIAL, meta = fetchMeta(serverDateMs = serverNow))
            val row = db.episodeDao().byIdentityKey(id, "g:a")!!
            assertEquals(serverNow, row.firstSeenAt)
        }

    @Test
    fun changedChaptersUrlDeletesFetchedJsonChapters() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(items = listOf(parsedEpisode(0, guid = "a", chaptersUrl = "https://x.example.com/c1.json"))),
                mode = IngestMode.INITIAL,
            )
            val episodeId = db.episodeDao().byIdentityKey(id, "g:a")!!.id
            db
                .chapterDao()
                .replace(
                    episodeId,
                    ChapterSource.PODCASTING20_JSON,
                    listOf(
                        ChapterEntity(
                            episodeId = episodeId,
                            source = ChapterSource.PODCASTING20_JSON,
                            ordinal = 0,
                            startMs = 0,
                        ),
                    ),
                )
            assertEquals(1, db.chapterDao().ofSource(episodeId, ChapterSource.PODCASTING20_JSON).size)

            ingest(
                id,
                parsedFeed(items = listOf(parsedEpisode(0, guid = "a", chaptersUrl = "https://x.example.com/c2.json"))),
            )

            assertEquals(emptyList(), db.chapterDao().ofSource(episodeId, ChapterSource.PODCASTING20_JSON))
        }

    // --- inFeed flips ---------------------------------------------------------------------------

    @Test
    fun removedItemsFlipOutAndReappearingRestores() =
        runTest {
            val id = podcastId()
            val three =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "a", pubDate = NOW - DAY),
                            parsedEpisode(1, guid = "b", pubDate = NOW - 2 * DAY),
                            parsedEpisode(2, guid = "c", pubDate = NOW - 3 * DAY),
                        ),
                )
            ingest(id, three, mode = IngestMode.INITIAL)

            ingest(id, parsedFeed(items = listOf(parsedEpisode(0, guid = "a"), parsedEpisode(1, guid = "b"))))
            assertFalse(db.episodeDao().byIdentityKey(id, "g:c")!!.inFeed)

            ingest(id, three)
            assertTrue(db.episodeDao().byIdentityKey(id, "g:c")!!.inFeed)
        }

    @Test
    fun partialDocumentFlipsAbsentRowsOnlyInsideWindow() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        (0..12).map {
                            parsedEpisode(it, guid = "d$it", pubDate = NOW - it * DAY)
                        },
                ),
                mode = IngestMode.INITIAL,
            )

            // A partial page-1 document covering days 7..9: stored rows newer than the window
            // floor that are absent flip out; rows older than the window are outside its
            // coverage and stay (03 step 8's partial rule).
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d7", pubDate = NOW - 7 * DAY),
                            parsedEpisode(1, guid = "d9", pubDate = NOW - 9 * DAY),
                        ),
                ),
                partial = true,
            )

            assertFalse(db.episodeDao().byIdentityKey(id, "g:d5")!!.inFeed)
            assertFalse(db.episodeDao().byIdentityKey(id, "g:d8")!!.inFeed)
            assertTrue(db.episodeDao().byIdentityKey(id, "g:d11")!!.inFeed)
            assertTrue(db.episodeDao().byIdentityKey(id, "g:d7")!!.inFeed)
        }

    // --- Empty and unsupported feeds --------------------------------------------------------------

    @Test
    fun emptyChannelLeavesEveryStoredRowInFeed() =
        runTest {
            val id = podcastId()
            ingest(id, parsedFeed(items = listOf(parsedEpisode(0, guid = "a"))), mode = IngestMode.INITIAL)

            ingest(id, parsedFeed(title = "Still the show", items = emptyList()))

            assertTrue(db.episodeDao().byIdentityKey(id, "g:a")!!.inFeed)
        }

    @Test
    fun itemsWithoutMediaAreRejectedWithNoMedia() =
        runTest {
            val id = podcastId()
            val before = db.podcastDao().byId(id)!!
            val feed =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "a", enclosureUrl = null, externalMediaId = null),
                        ),
                )

            val result = ingest(id, feed)

            assertEquals(FeedErrorKind.NO_MEDIA, result.emptyKind)
            assertEquals(0, result.accepted)
            assertEquals(before, db.podcastDao().byId(id))
        }

    @Test
    fun listMediumFeedReportsUnsupportedListFeed() =
        runTest {
            val id = podcastId()
            val result = ingest(id, parsedFeed(items = emptyList(), medium = "podcastL"))
            assertEquals(FeedErrorKind.UNSUPPORTED_LIST_FEED, result.emptyKind)
        }

    // --- Integrity -------------------------------------------------------------------------------

    @Test
    fun assignedKeyCollisionResolvesWithoutConflict() =
        runTest {
            val id = podcastId()
            // v1: row B (u:encX), row C (g:gc with the same enclosure URL), row D (t: key of T+day).
            val day = NOW - DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                title = "B",
                                pubDate = day,
                            ),
                            parsedEpisode(
                                1,
                                guid = "gc",
                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                title = "C",
                                pubDate = day,
                            ),
                            parsedEpisode(2, enclosureUrl = null, externalMediaId = "m1", title = "T", pubDate = day),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val cBefore = db.episodeDao().byIdentityKey(id, "g:gc")!!
            val dBefore = db.ingestDao().existing(id).single { it.enclosureUrl == null }

            // v2: two items share enclosure x.mp3. The second's assigned key is its t: fallback —
            // exactly row D's key, so it claims D in pass 1 (S3: exact-match the assigned key;
            // every assigned key is reserved, so no pass-2 rekey can collide and nothing aborts
            // on UNIQUE(podcastId, identityKey)).
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "B2",
                                    pubDate = day,
                                ),
                                parsedEpisode(
                                    1,
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "T",
                                    pubDate = day,
                                ),
                            ),
                    ),
                )

            assertEquals(0, result.inserted.size)
            assertEquals(2, result.updated)
            val d = db.episodeDao().byId(dBefore.id)!!
            assertEquals("https://cdn.example.com/x.mp3", d.enclosureUrl)
            assertEquals("T", d.title)
            // The unclaimed row C is absent from the complete document (03 step 8).
            val c = db.episodeDao().byId(cBefore.id)!!
            assertEquals("g:gc", c.identityKey)
            assertFalse(c.inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    @Test
    fun ingestInTransactionCommitsInsideTheCallersTransaction() =
        runTest {
            val id = podcastId()
            // The subscribe path: ingestInTransaction inside a caller's write transaction.
            val result =
                db.withWriteTransaction {
                    db
                        .podcastDao()
                        .insertAlias(
                            ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity(
                                url = "https://alias.example.com/feed",
                                podcastId = id,
                                reason = ch.lkmc.neutrodyne.core.model.AliasReason.SUBSCRIBE_INPUT,
                                addedAt = NOW,
                            ),
                        )
                    ingestor.ingestInTransaction(
                        dueFeedOf(db, id),
                        parsedFeed(items = listOf(parsedEpisode(0, guid = "a"))),
                        IngestContext(mode = IngestMode.INITIAL, partial = false, fetch = fetchMeta()),
                    )
                }
            assertEquals(1, result.inserted.size)
            assertEquals(1, db.podcastDao().aliases(id).size)
            assertNotNull(db.episodeDao().byIdentityKey(id, "g:a"))
        }

    private fun artwork(url: String) =
        ch.lkmc.neutrodyne.feeds.model.ArtworkCandidate(
            url = url,
            source = ch.lkmc.neutrodyne.feeds.model.ArtworkSource.ITUNES_IMAGE,
        )

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY = 86_400_000L
    }
}
