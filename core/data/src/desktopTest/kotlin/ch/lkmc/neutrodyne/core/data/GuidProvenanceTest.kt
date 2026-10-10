// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.IngestResult
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PO-49 / D98 feed-scoped GUID provenance: a GUID once observed shared stays ambiguous for the
 * life of the subscription; a GUID tracked from its first introduction under complete observation
 * is known-independent; rows without coverage never infer independence. These tests pin the
 * behavioural contract on stored rows, user state and counters.
 */
class GuidProvenanceTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val ingestor = newIngestor(db, clock)

    private suspend fun podcastId() =
        seedPodcast(
            db,
            feedUrl = "https://example.com/feed.xml",
            feedKey = "https://example.com/feed.xml",
            subscribedAt = NOW - 30 * DAY,
            status = PodcastStatus.ACTIVE,
        )

    private suspend fun ingest(
        id: Long,
        parsed: ParsedFeed,
        mode: IngestMode = IngestMode.REFRESH,
        partial: Boolean = false,
        meta: FetchMeta = fetchMeta(),
    ): IngestResult = ingestor.ingest(dueFeedOf(db, id), parsed, IngestContext(mode, partial, meta))

    private suspend fun byKey(
        id: Long,
        key: String,
    ) = db.episodeDao().byIdentityKey(id, key)

    private suspend fun byEnclosure(
        id: Long,
        url: String,
    ) = db.ingestDao().existing(id).single { it.enclosureUrl == url }

    /** Two distinct episodes once shared GUID `dup`; afterwards every stored GUID must persist. */
    @Test
    fun sharedGuidRotationPersistsFallbackGuidAndKeepsStates() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = "dup", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val a = byKey(id, "g:dup")!!
            val b = byEnclosure(id, "https://cdn/b.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })
            db
                .positionDao()
                .upsert(
                    EpisodePositionEntity(
                        a.id,
                        positionMs = 42_000,
                        durationMs = null,
                        positionSource = PositionSource.STREAM,
                        updatedAt = NOW,
                    ),
                )
            db.queueDao().insert(queueEntryEntity(a.id, "a1"))
            db.downloadDao().insert(downloadEntity(b.id, sourceRef = "https://cdn/b.mp3"))

            // Both rotate to a new GUID in one document with unchanged content.
            val second =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "dup2", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                                parsedEpisode(1, guid = "dup2", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            assertEquals(2, second.accepted)
            assertEquals(0, second.inserted.size)

            // The fallback-keyed sibling's current GUID must persist too.
            assertEquals("dup2", byKey(id, "g:dup2")!!.guid)
            assertEquals("dup2", byEnclosure(id, "https://cdn/b.mp3").guid)

            // B returns alone under the rotated GUID. The ambiguous marker must route it to its
            // own row rather than to the g:dup2 row it shares the GUID with.
            val third =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(1, guid = "dup2", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            assertEquals(0, third.inserted.size)
            assertEquals(0, third.updated)

            val aAfter = byKey(id, "g:dup2")!!
            val bAfter =
                db.ingestDao().existing(id).single { it.id == b.id }
            assertEquals("https://cdn/a.mp3", aAfter.enclosureUrl)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            assertEquals(42_000, db.positionDao().byEpisode(a.id)!!.positionMs)
            assertEquals(listOf(a.id), db.queueDao().entries().map { it.episodeId })
            assertEquals("https://cdn/b.mp3", bAfter.enclosureUrl)
            assertTrue(bAfter.inFeed)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
            assertEquals(b.id, db.downloadDao().byEpisode(b.id)!!.episodeId)
        }

    /** B's GUID interval (null) must persist, and `dup` must stay ambiguous after it comes back. */
    @Test
    fun nullGuidIntervalKeepsSiblingRowsAndStates() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = "dup", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val a = byKey(id, "g:dup")!!
            val b = byEnclosure(id, "https://cdn/b.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            // B drops its GUID while A keeps `dup`.
            val second =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                                parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            assertEquals(0, second.updated)
            assertEquals(0, second.rekeyed)
            assertNull(byEnclosure(id, "https://cdn/b.mp3").guid)

            // B returns under `dup`. The stored ambiguity must keep it off A's row.
            val third =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(1, guid = "dup", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            assertEquals(0, third.inserted.size)
            assertEquals(0, third.updated)

            val aAfter = byKey(id, "g:dup")!!
            assertEquals("https://cdn/a.mp3", aAfter.enclosureUrl)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            val bAfter = db.ingestDao().existing(id).single { it.id == b.id }
            assertTrue(bAfter.inFeed)
            assertEquals("dup", bAfter.guid)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
        }

    /**
     * A podcast without coverage (pre-provenance library) never learns independence: a foreign
     * enclosure owner blocks the bare `g:` claim, so the item inserts beside both rows instead of
     * transferring A's row and state.
     */
    @Test
    fun unknownGuidConflictInsertsDuplicateAndKeepsBothStates() =
        runTest {
            // podcastEntity() carries no coverage: this podcast bootstraps unknown.
            val id =
                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(
                            feedUrl = "https://example.com/feed.xml",
                            feedKey = "https://example.com/feed.xml",
                            subscribedAt = NOW - 30 * DAY,
                        ) { copy(status = PodcastStatus.ACTIVE, initialFetch = false) },
                    )
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "gA", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val a = byKey(id, "g:gA")!!
            val b = byEnclosure(id, "https://cdn/b.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            // A contested item: gA's identity, B's enclosure. Unknown provenance must not pick.
            val second =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    9,
                                    guid = "gA",
                                    title = "Ghost",
                                    enclosureUrl = "https://cdn/b.mp3",
                                    pubDate =
                                        NOW - 9 * DAY,
                                ),
                            ),
                    ),
                )
            assertEquals(1, second.inserted.size)
            assertEquals(3, db.podcastDao().episodeCount(id))

            val aAfter = db.ingestDao().existing(id).single { it.id == a.id }
            assertEquals("https://cdn/a.mp3", aAfter.enclosureUrl)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            val bAfter = db.ingestDao().existing(id).single { it.id == b.id }
            assertFalse(bAfter.inFeed)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)

            // A repeated contested document still cannot resolve: no transfer, and the item is
            // dropped once every claimable key is taken rather than overwriting either owner.
            val third =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    9,
                                    guid = "gA",
                                    title = "Ghost",
                                    enclosureUrl = "https://cdn/b.mp3",
                                    pubDate =
                                        NOW - 9 * DAY,
                                ),
                            ),
                    ),
                )
            assertEquals(0, third.inserted.size)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertEquals(
                "https://cdn/a.mp3",
                db
                    .ingestDao()
                    .existing(id)
                    .single { it.id == a.id }
                    .enclosureUrl,
            )
        }

    /**
     * Under complete coverage the GUID's sole introduction is tracked independent: the same
     * contested item claims the `g:` row and keeps its state, and the GUID-less fallback owner
     * stays untouched: independent authority outranks an unrelated occupied URL.
     */
    @Test
    fun independentGuidKeepsPrecedenceOverOccupiedEnclosure() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "gA", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val a = byKey(id, "g:gA")!!
            val b = byEnclosure(id, "https://cdn/b.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            val second =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    9,
                                    guid = "gA",
                                    title = "Ghost",
                                    enclosureUrl = "https://cdn/b.mp3",
                                    pubDate =
                                        NOW - 9 * DAY,
                                ),
                            ),
                    ),
                )
            assertEquals(0, second.inserted.size)
            assertEquals(2, db.podcastDao().episodeCount(id))

            val aAfter = db.ingestDao().existing(id).single { it.id == a.id }
            assertEquals("https://cdn/b.mp3", aAfter.enclosureUrl)
            assertTrue(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            val bAfter = db.ingestDao().existing(id).single { it.id == b.id }
            assertFalse(bAfter.inFeed)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
        }

    /**
     * `dup` is ambiguous forever: after both carriers rotate to fresh GUIDs, `dup` has no stored
     * carrier at all, yet a lone `dup` item still may not claim the surviving `g:` slot by primary
     * key; it inserts beside the pair.
     */
    @Test
    fun ambiguousAuthoritySurvivesRotationAndReturn() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = "dup", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val a = byKey(id, "g:dup")!!
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })

            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup2", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = "dup3", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
            )
            assertEquals("dup2", byKey(id, "g:dup2")!!.guid)
            assertEquals("dup3", byKey(id, "g:dup3")!!.guid)

            val third =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    9,
                                    guid = "dup",
                                    title = "Ghost",
                                    enclosureUrl = "https://cdn/c.mp3",
                                    pubDate =
                                        NOW - 9 * DAY,
                                ),
                            ),
                    ),
                )
            assertEquals(1, third.inserted.size)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
        }

    /**
     * A GUID first seen through a paging window is a durable observation without authority:
     * the later complete document showing it once must not promote it, and the contested item
     * (d's identity, B's enclosure) takes insert-or-drop beside both owners instead of
     * claiming the played A row (D98; review probe `partial-introduction`).
     */
    @Test
    fun partialFirstObservationBlocksLaterSingletonPromotion() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                partial = true,
            )
            // The first sighting is durable memory, but carries no authority.
            assertNull(db.ingestDao().guidKnowledge(id)["d"])
            assertTrue("d" in db.ingestDao().recordedGuids(id))
            val a = byKey(id, "g:d")!!
            val b = byEnclosure(id, "https://cdn/b.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            // The same document without the window: `d` was already observed, so the sole
            // sighting is not an introduction and must not promote.
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
            )
            assertNull(db.ingestDao().guidKnowledge(id)["d"])

            val contested =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(9, guid = "d", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            assertEquals(1, contested.inserted.size)
            assertEquals(3, db.podcastDao().episodeCount(id))
            val aAfter = db.ingestDao().existing(id).single { it.id == a.id }
            assertEquals("https://cdn/a.mp3", aAfter.enclosureUrl)
            assertFalse(aAfter.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
        }

    /**
     * A document that carries page-1 paging links is incomplete on every entry path, even when
     * the caller's `partial` flag says otherwise (the subscribe path cannot widen it): `d` is
     * recorded without authority and the contested item preserves both owners (D98; review
     * probe `paged-subscribe`).
     */
    @Test
    fun documentPagingLinksBlockIndependenceRegardlessOfCallerFlag() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                    paging = Paging(next = "https://example.com/older.xml"),
                ),
                mode = IngestMode.INITIAL,
                partial = false,
            )
            assertNull(db.ingestDao().guidKnowledge(id)["d"])
            assertTrue("d" in db.ingestDao().recordedGuids(id))
            val a = byKey(id, "g:d")!!
            val b = byEnclosure(id, "https://cdn/b.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            val contested =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(9, guid = "d", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            assertEquals(1, contested.inserted.size)
            assertEquals(3, db.podcastDao().episodeCount(id))
            val aAfter = db.ingestDao().existing(id).single { it.id == a.id }
            assertEquals("https://cdn/a.mp3", aAfter.enclosureUrl)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
        }

    /**
     * A parser item cap makes the document incomplete even though paging links are absent: an
     * item whose duplicate was truncated away looks sole-observed but is not an introduction,
     * so `d` records without KNOWN_INDEPENDENT authority (D98; review probe `default-item-cap`).
     */
    @Test
    fun itemTruncatedDocumentCannotProveIndependence() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = null, title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                        ),
                    warnings = listOf(ParseWarning(WarningCode.ITEMS_TRUNCATED)),
                ),
            )
            assertNull(db.ingestDao().guidKnowledge(id)["d"])
            assertTrue("d" in db.ingestDao().recordedGuids(id))
        }

    /**
     * A GUID that earns KNOWN_INDEPENDENT inside the contested document itself must be honoured:
     * the authority lookup ran before this ingest recorded it, so the stale map alone still
     * reports contested. The `g:X` row whose stored GUID rotated away (a key shell) keeps its
     * slot and the item claims it; the unrelated enclosure owner is untouched (R70 follow-up).
     */
    @Test
    fun sameIngestIndependentHonoursClaimAgainstGuidShell() =
        runTest {
            val id = podcastId()
            // A `g:X`-keyed row whose current GUID is no longer X (rotation left the slot) and an
            // unrelated row that owns the incoming item's enclosure.
            db
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = id, identityKey = "g:X") {
                            copy(guid = "old", title = "Shell", enclosureUrl = "https://cdn/s.mp3")
                        },
                        episodeEntity(podcastId = id, identityKey = "g:Z") {
                            copy(guid = "Z", title = "Owner", enclosureUrl = "https://cdn/b.mp3")
                        },
                    ),
                )
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(9, guid = "X", title = "Ghost", enclosureUrl = "https://cdn/b.mp3"),
                            ),
                    ),
                )
            // X sole-introduced under a complete document: KNOWN_INDEPENDENT is recorded and must
            // apply to this ingest's own pass 1 — the item claims the `g:X` slot, no duplicate.
            assertEquals(GuidKnowledge.KNOWN_INDEPENDENT, db.ingestDao().guidKnowledge(id)["X"])
            assertEquals(0, result.inserted.size)
            assertEquals(2, db.podcastDao().episodeCount(id))
            val shell = byKey(id, "g:X")!!
            assertEquals("X", shell.guid)
            assertTrue(shell.inFeed)
        }

    /**
     * A V1-migrated (uncovered) feed publishing two distinct episodes that share one enclosure
     * (the re-published/retracted pattern): the first contested refresh lands the items beside
     * both owners, and every later refresh must converge — bounded rows, no `g:`-keyed row ever
     * acquires an ambiguous GUID, and both owners' state survives (R70 follow-up probe).
     */
    @Test
    fun uncoveredSharedEnclosureConvergesWithoutChurn() =
        runTest {
            val id =
                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(
                            feedUrl = "https://example.com/feed.xml",
                            feedKey = "https://example.com/feed.xml",
                            subscribedAt = NOW - 30 * DAY,
                        ) { copy(status = PodcastStatus.ACTIVE, initialFetch = false) },
                    )
            val doc =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "a",
                                title = "Alpha",
                                enclosureUrl = "https://cdn/e.mp3",
                                pubDate =
                                    NOW - DAY,
                            ),
                            parsedEpisode(
                                1,
                                guid = "b",
                                title = "Beta",
                                enclosureUrl = "https://cdn/e.mp3",
                                pubDate =
                                    NOW - 2 * DAY,
                            ),
                        ),
                )
            ingest(id, doc, mode = IngestMode.INITIAL)
            val a = byKey(id, "g:a")!!
            val b = byKey(id, "g:b")!!
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            for (round in 1..4) ingest(id, doc)

            val rows = db.ingestDao().existing(id)
            // Converged: the contested inserts are bounded and the fifth refresh adds nothing.
            val countAfter4 = db.podcastDao().episodeCount(id)
            ingest(id, doc)
            assertEquals(countAfter4, db.podcastDao().episodeCount(id))
            // No ambiguous GUID ever occupies a `g:` slot beyond the rows that legitimately
            // introduced under it: every newly landed contested row holds a non-`g:` content key.
            assertEquals(
                setOf(a.id, b.id),
                rows.filter { it.identityKey.startsWith("g:") }.map { it.id }.toSet(),
            )
            assertTrue(rows.all { !it.identityKey.startsWith("g:") || it.guid in setOf("a", "b") })
            // Both original owners keep their rows and state; nothing transferred.
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
            assertEquals("https://cdn/e.mp3", rows.single { it.id == a.id }.enclosureUrl)
            assertEquals("https://cdn/e.mp3", rows.single { it.id == b.id }.enclosureUrl)
            // Persisted ambiguity stays put once both rows carry the GUIDs under non-g keys.
            assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, db.ingestDao().guidKnowledge(id)["a"])
            assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, db.ingestDao().guidKnowledge(id)["b"])
        }

    /**
     * An `fh:archive` document is a stable slice of older entries (RFC 5005): never the whole
     * feed, so a GUID sole in it cannot be a strict introduction — it records OBSERVED even on a
     * covered feed and even when it carries no paging links (D98; review follow-up).
     */
    @Test
    fun archiveDocumentCannotProveIndependence() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                        ),
                    paging = Paging(fhArchive = true),
                ),
                mode = IngestMode.INITIAL,
            )
            assertNull(db.ingestDao().guidKnowledge(id)["d"])
            assertTrue("d" in db.ingestDao().recordedGuids(id))
        }

    /** Reversed document order hands `g:dup` to B instead; the sibling still resolves by tier. */
    @Test
    fun reversedDocumentOrderKeepsSiblingStates() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(1, guid = "dup", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                            parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val b = byKey(id, "g:dup")!!
            val a = byEnclosure(id, "https://cdn/a.mp3")
            db.episodeStateDao().upsert(episodeStateEntity(a.id) { copy(playedAt = NOW - 1_000) })
            db.episodeStateDao().upsert(episodeStateEntity(b.id) { copy(isFavorite = true) })

            val second =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            ),
                    ),
                )
            assertEquals(0, second.inserted.size)

            val aAfter = db.ingestDao().existing(id).single { it.id == a.id }
            assertTrue(aAfter.inFeed)
            assertEquals("https://cdn/a.mp3", aAfter.enclosureUrl)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(a.id)!!.playedAt)
            val bAfter = db.ingestDao().existing(id).single { it.id == b.id }
            assertFalse(bAfter.inFeed)
            assertTrue(db.episodeStateDao().byEpisode(b.id)!!.isFavorite)
        }

    /**
     * The information-loss proof (PO-49): two covered podcasts converge to identical stored
     * episode projections (same identity keys, GUIDs, enclosures and content) and then receive
     * the same contested item (`dup`'s identity on `x`'s enclosure). The stored rows alone cannot
     * arbitrate; the persisted provenance decides: `dup` recorded ambiguous on P hands the item
     * to the enclosure owner via pass 2, while `dup` recorded independent on Q lets the `g:` row
     * claim it.
     */
    @Test
    fun identicalRowsDivergeOnlyByPersistedProvenance() =
        runTest {
            val p =
                seedPodcast(
                    db,
                    feedUrl = "https://example.com/feed-p.xml",
                    feedKey = "https://example.com/feed-p.xml",
                    subscribedAt = NOW - 30 * DAY,
                )
            val q =
                seedPodcast(
                    db,
                    feedUrl = "https://example.com/feed-q.xml",
                    feedKey = "https://example.com/feed-q.xml",
                    subscribedAt = NOW - 30 * DAY,
                )

            // History P: `dup` introduces, then a later document shares it between two items;
            // recorded ambiguous. Those items land as fallback rows and rotate to fresh GUIDs.
            ingest(
                p,
                parsedFeed(
                    items = listOf(parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3")),
                ),
                mode = IngestMode.INITIAL,
            )
            ingest(
                p,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(1, guid = "dup", title = "Gamma", enclosureUrl = "https://cdn/c.mp3"),
                            parsedEpisode(2, guid = "dup", title = "Delta", enclosureUrl = "https://cdn/d.mp3"),
                        ),
                ),
            )
            ingest(
                p,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = "x", title = "Gamma", enclosureUrl = "https://cdn/c.mp3"),
                            parsedEpisode(2, guid = "y", title = "Delta", enclosureUrl = "https://cdn/d.mp3"),
                        ),
                ),
            )

            // History Q: the same rows in one complete document: every GUID sole-observed.
            ingest(
                q,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                            parsedEpisode(1, guid = "x", title = "Gamma", enclosureUrl = "https://cdn/c.mp3"),
                            parsedEpisode(2, guid = "y", title = "Delta", enclosureUrl = "https://cdn/d.mp3"),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )

            // Identical ordinary projections; only the recorded knowledge differs.
            assertEquals(
                db
                    .ingestDao()
                    .existing(p)
                    .map { it.copy(id = 0) }
                    .toSet(),
                db
                    .ingestDao()
                    .existing(q)
                    .map { it.copy(id = 0) }
                    .toSet(),
            )
            assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, db.ingestDao().guidKnowledge(p)["dup"])
            assertEquals(GuidKnowledge.KNOWN_INDEPENDENT, db.ingestDao().guidKnowledge(q)["dup"])

            val contested =
                parsedFeed(
                    items = listOf(parsedEpisode(9, guid = "dup", title = "Gamma", enclosureUrl = "https://cdn/c.mp3")),
                )
            val pResult = ingest(p, contested)
            val qResult = ingest(q, contested)

            // P: ambiguity denies the bare `g:` claim; unique enclosure evidence matches the
            // item to x's row, which persists the new GUID beside its own key.
            assertEquals(0, pResult.inserted.size)
            val pDup = byKey(p, "g:dup")!!
            assertEquals("https://cdn/a.mp3", pDup.enclosureUrl)
            val pX = byKey(p, "g:x")!!
            assertEquals("dup", pX.guid)
            assertEquals("https://cdn/c.mp3", pX.enclosureUrl)

            // Q: independent authority keeps `dup`'s precedence over the unrelated occupied URL.
            assertEquals(0, qResult.inserted.size)
            val qDup = byKey(q, "g:dup")!!
            assertEquals("https://cdn/c.mp3", qDup.enclosureUrl)
            val qX = byKey(q, "g:x")!!
            assertEquals("x", qX.guid)
            assertEquals("https://cdn/c.mp3", qX.enclosureUrl)
        }

    /** Persisted ambiguity is a database fact: it survives closing and reopening the file. */
    @Test
    fun learnedAmbiguitySurvivesCloseAndReopen() =
        runTest {
            val dir = Files.createTempDirectory("guid-provenance-restart")
            val id: Long
            TestDb.file(dir, clock = clock).let { first ->
                id = seedPodcast(first, feedUrl = "https://example.com/feed.xml")
                newIngestor(first, clock)
                    .ingest(
                        dueFeedOf(first, id),
                        parsedFeed(
                            items =
                                listOf(
                                    parsedEpisode(0, guid = "dup", title = "Alpha", enclosureUrl = "https://cdn/a.mp3"),
                                    parsedEpisode(1, guid = "dup", title = "Beta", enclosureUrl = "https://cdn/b.mp3"),
                                ),
                        ),
                        IngestContext(IngestMode.INITIAL, false, fetchMeta()),
                    )
                assertEquals(
                    GuidKnowledge.KNOWN_AMBIGUOUS,
                    first.ingestDao().guidKnowledge(id)["dup"],
                )
                first.close()
            }

            TestDb.file(dir, clock = clock).let { second ->
                try {
                    assertEquals(
                        GuidKnowledge.KNOWN_AMBIGUOUS,
                        second.ingestDao().guidKnowledge(id)["dup"],
                    )
                    val res =
                        newIngestor(second, clock)
                            .ingest(
                                dueFeedOf(second, id),
                                parsedFeed(
                                    items =
                                        listOf(
                                            parsedEpisode(
                                                9,
                                                guid = "dup",
                                                title = "Ghost",
                                                enclosureUrl = "https://cdn/c.mp3",
                                            ),
                                        ),
                                ),
                                IngestContext(IngestMode.REFRESH, false, fetchMeta()),
                            )
                    assertEquals(1, res.inserted.size)
                    assertEquals(3, second.ingestDao().existing(id).size)
                    assertEquals("https://cdn/a.mp3", second.episodeDao().byIdentityKey(id, "g:dup")!!.enclosureUrl)
                } finally {
                    second.close()
                }
            }
        }

    /**
     * Provenance writes ride the ingest transaction: an abort after the record leaves no
     * provenance row, no episode and no alias; nothing commits in pieces (02 Transactions).
     */
    @Test
    fun provenanceWritesRollbackWithTheIngestTransaction() =
        runTest {
            val id = podcastId()
            assertFailsWith<IllegalStateException> {
                db.withWriteTransaction {
                    db.ingestDao().recordGuidKnowledge(id, setOf("rb"), emptySet(), emptySet())
                    db.ingestDao().insertEpisodes(listOf(episodeEntity(podcastId = id, identityKey = "g:rb")))
                    db
                        .podcastDao()
                        .insertAlias(
                            PodcastUrlAliasEntity("https://alias.example.com/x", id, AliasReason.REDIRECT, NOW),
                        )
                    throw IllegalStateException("aborting after provenance")
                }
            }
            assertTrue(db.ingestDao().guidKnowledge(id).isEmpty())
            assertTrue(db.ingestDao().existing(id).isEmpty())
            assertTrue(db.podcastDao().aliases(id).isEmpty())
            assertNotNull(db.podcastDao().byId(id))
        }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = TestClock.DEFAULT_NOW
    }
}
