// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded S15 seed at merge level (MS0 acceptance 2): five replicas with skewed walls inside the
 * admission bound exchange validated changes in shuffled, duplicated order and must converge to
 * identical raw state. Server-side semantics (dedupe, seq/cursor, RecordWriter) stay with the
 * `:core:testing`/`InMemorySyncServer` harness; this exercises only `RecordMerger`, validation
 * and the episode derivation, so it lives in `commonTest` of `:sync:protocol`.
 */
class SeededMergeConvergenceTest {
    private class Replica(
        node: Long,
        skewMs: Long,
        now: () -> Long,
    ) {
        val clock = HlcClock(NodeId(node.toString(16).padStart(16, '0')), wallMs = { now() + skewMs })
        val records = mutableMapOf<String, RecordState>()
        val sent = mutableListOf<Pair<RecordId, RecordState>>()
        val keys = mutableMapOf<String, MutableList<String>>()
    }

    private val podcast = PodcastId("11111111-1111-4111-8111-111111111111")
    private val podcastB = PodcastId("33333333-3333-4333-8333-333333333333")
    private val group = GroupId("22222222-2222-4222-8222-222222222222")
    private val episode = RecordId.Episode(podcast, EpisodeKey("g:ep1"))
    private val episodeB = RecordId.Episode(podcast, EpisodeKey("g:ep2"))
    private val member = RecordId.Member(group, podcast)

    @Test
    fun replicasConvergeUnderShuffledDuplicatedDelivery() {
        for (seed in SEEDS) {
            Sim(seed).run()
        }
    }

    private class Op(
        val coll: SyncCollection,
        val id: RecordId,
        val fields: Map<String, FieldValue>,
        val match: MatchHints? = null,
    )

    private inner class Sim(
        seed: Long,
    ) {
        private val random = Random(seed)
        private val now = WALL_BASE
        private val replicas =
            SKEWS.mapIndexed { index, skew ->
                Replica(index.toLong() + 1, skew, ::nowMillis)
            }

        private fun nowMillis() = now

        fun run() {
            repeat(OPS_PER_SEED) {
                val replica = replicas[random.nextInt(replicas.size)]
                for (op in nextOps(replica)) {
                    emit(replica, op)
                }
            }
            // Full gossip: every receiver merges everyone else's changes in its own seeded order,
            // with a slice delivered twice (redelivery is idempotent).
            for (receiver in replicas) {
                val incoming =
                    replicas
                        .filter { it !== receiver }
                        .flatMap { it.sent }
                        .shuffled(random)
                for ((id, state) in incoming + incoming.take(incoming.size / 8 + 1)) {
                    mergeInto(receiver, id, state)
                }
            }
            assertConverged()
        }

        // Episode and Up next share the `{p, k}` rid shape; the store key is (collection, rid).
        private fun keyOf(
            coll: SyncCollection,
            id: RecordId,
        ) = "${coll.wire}/${id.rid}"

        private fun mergeInto(
            replica: Replica,
            id: RecordId,
            state: RecordState,
        ) {
            val key = keyOf(state.coll, id)
            replica.records[key] = RecordMerger.merge(replica.records[key], state).record
        }

        // One emitted change goes through the same validation a peer's push would face, then
        // merges locally and is queued for gossip.
        private fun emit(
            replica: Replica,
            op: Op,
        ) {
            val dto = ChangeDto(op.coll.wire, op.id.toWire(), fields = op.fields, match = op.match)
            val write =
                ProtocolValidation.change(dto, nowMillis()) as? ValidatedChange.Write
                    ?: error("emitted change failed validation")
            mergeInto(replica, write.id, write.state)
            replica.sent += write.id to write.state
        }

        private fun nextOps(replica: Replica): List<Op> {
            val c = replica.clock.tick().toString()
            fun fv(
                value: JsonElement,
                clock: String = c,
            ) = FieldValue(value, clock)

            return when (random.nextInt(14)) {
                0 ->
                    listOf(
                        Op(
                            SyncCollection.PODCAST,
                            RecordId.Podcast(podcast),
                            mapOf("title" to fv(JsonPrimitive("show-${random.nextInt(4)}"))),
                        ),
                    )

                1 ->
                    listOf(
                        Op(
                            SyncCollection.PODCAST,
                            RecordId.Podcast(podcast),
                            mapOf("subscribed" to fv(JsonPrimitive(random.nextBoolean()))),
                        ),
                    )

                2 ->
                    listOf(
                        Op(
                            SyncCollection.PODCAST,
                            RecordId.Podcast(podcast),
                            mapOf(
                                "feedKeys" to
                                    FieldValue(
                                        add = List(random.nextInt(1, 4)) { "example.com/feed-${random.nextInt(8)}" },
                                    ),
                            ),
                        ),
                    )

                3 ->
                    listOf(
                        Op(
                            SyncCollection.PODCAST,
                            RecordId.Podcast(podcast),
                            mapOf(
                                "subscribedAt" to
                                    FieldValue(JsonPrimitive(random.nextLong(0, 1_000)), c.takeIf { random.nextBoolean() }),
                            ),
                        ),
                    )

                4 -> {
                    // Position save: non-zero listening, a guarded zero or an explicit reset.
                    val ms = random.nextInt(0, 4) * 900
                    listOf(
                        Op(
                            SyncCollection.EPISODE,
                            episode,
                            mapOf(
                                "pos" to
                                    FieldValue(
                                        buildJsonObject {
                                            put("ms", ms)
                                            put("dur", 3_600_000)
                                            put("src", "STREAM")
                                        },
                                        c,
                                        (ms == 0 && random.nextBoolean()).takeIf { ms == 0 },
                                    ),
                            ),
                            match = MatchHints(guid = "g:ep1", enc = "https://cdn.example/ep1.mp3", kv = 1),
                        ),
                    )
                }

                5 -> {
                    // Mark played/unplayed: the pair shares one clock and a mark-played also
                    // tombstones Up next in the same transaction (same stamp, 10 Tombstones).
                    val played = random.nextBoolean()
                    val pair =
                        Op(
                            SyncCollection.EPISODE,
                            episode,
                            mapOf(
                                "played" to fv(JsonPrimitive(played)),
                                "playedAt" to fv(if (played) JsonPrimitive(now) else JsonNull),
                            ),
                        )
                    val remove =
                        Op(
                            SyncCollection.UPNEXT,
                            episode,
                            mapOf(
                                "in" to fv(JsonPrimitive(false)),
                                "ok" to fv(JsonPrimitive(nextKey(replica, episode.rid))),
                            ),
                        )
                    if (played) listOf(pair, remove) else listOf(pair)
                }

                6 ->
                    listOf(
                        Op(SyncCollection.EPISODE, episodeB, mapOf("fav" to fv(JsonPrimitive(random.nextBoolean())))),
                    )

                7 ->
                    listOf(
                        Op(
                            SyncCollection.EPISODE,
                            episodeB,
                            mapOf(
                                "playCount" to
                                    FieldValue(JsonPrimitive(random.nextInt(0, 10)), c.takeIf { random.nextBoolean() }),
                                "lastPlayedAt" to
                                    FieldValue(
                                        JsonPrimitive(random.nextLong(0, now)),
                                        c.takeIf { random.nextBoolean() },
                                    ),
                            ),
                        ),
                    )

                8 ->
                    listOf(
                        Op(
                            SyncCollection.MEMBER,
                            member,
                            mapOf(
                                "in" to fv(JsonPrimitive(random.nextBoolean())),
                                "ok" to fv(JsonPrimitive(nextKey(replica, member.rid))),
                            ),
                        ),
                    )

                9 ->
                    listOf(
                        Op(
                            SyncCollection.GROUP,
                            RecordId.Group(group),
                            mapOf(
                                "name" to fv(JsonPrimitive("group-${random.nextInt(3)}")),
                                "deleted" to fv(JsonPrimitive(false)),
                            ),
                        ),
                    )

                10 ->
                    listOf(
                        Op(
                            SyncCollection.GROUP,
                            RecordId.Group(group),
                            mapOf("deleted" to fv(JsonPrimitive(true))),
                        ),
                    )

                11 ->
                    listOf(
                        Op(
                            SyncCollection.SETTING,
                            RecordId.Setting("playback.speed"),
                            mapOf("v" to fv(JsonPrimitive(1.0 + random.nextInt(0, 20) / 10.0))),
                        ),
                    )

                12 ->
                    listOf(
                        Op(
                            SyncCollection.SESSION,
                            RecordId.Session,
                            mapOf(
                                "episode" to fv(episode.toWire()),
                                "context" to
                                    fv(
                                        buildJsonObject {
                                            put("type", "GROUP")
                                            put("group", group.value)
                                            put("order", "NEWEST_FIRST")
                                        },
                                    ),
                            ),
                            match = MatchHints(guid = "g:ep1", kv = 1),
                        ),
                    )

                else ->
                    listOf(
                        Op(
                            SyncCollection.PODCAST,
                            RecordId.Podcast(podcastB),
                            mapOf(
                                "title" to fv(JsonPrimitive("other-${random.nextInt(4)}")),
                                "feedUrl" to fv(JsonPrimitive("https://example.com/feed-${random.nextInt(8)}")),
                            ),
                        ),
                    )
            }
        }

        // Order key for a list row: jittered midpoint inside the replica's own key pool; a pool
        // that would breach the 64-char cap is rewritten first (10 Ordered lists).
        private fun nextKey(
            replica: Replica,
            rid: String,
        ): String {
            val pool = replica.keys.getOrPut(rid) { mutableListOf() }
            if (pool.isEmpty()) {
                val key = OrderKey.between(null, null, random)
                pool += key
                return key
            }
            pool.sort()
            val at = random.nextInt(pool.size + 1)
            var key = OrderKey.between(pool.getOrNull(at - 1), pool.getOrNull(at), random)
            if (key.length > ProtocolLimits.MAX_ORDER_KEY) {
                val rewritten = OrderKey.rewrite(pool.size + 1)
                pool.clear()
                pool += rewritten.dropLast(1)
                key = rewritten.last()
            }
            pool += key
            return key
        }

        private fun assertConverged() {
            val canonical = replicas.first().records
            for (replica in replicas.drop(1)) {
                assertEquals(canonical, replica.records, "replicas diverged")
            }
            for ((rid, record) in canonical) {
                // N1: a zero position can only exist with an explicit reset.
                val pos = record.fields["pos"]
                if (pos != null) {
                    assertTrue(pos.reset || positionMs(pos) != 0L, "unflagged zero position survived in $rid")
                }
                if (record.coll == SyncCollection.EPISODE) {
                    // The played pair always keeps one clock and consistent values.
                    if ("played" in record.fields) {
                        val played = record.fields.getValue("played")
                        val at = record.fields.getValue("playedAt")
                        assertEquals(played.c, at.c, "played pair clock split in $rid")
                        assertEquals(
                            (played.v as? JsonPrimitive)?.booleanOrNull == true,
                            at.v != JsonNull,
                            "played pair inconsistent in $rid",
                        )
                    }
                    // A live Up next row for an effectively played episode is only explainable by
                    // a deliberate re-add newer than the mark (appliers gate on allowsUpNext).
                    if (EpisodeStateRules.derive(record.fields).state == EpisodeProgress.PLAYED) {
                        val queue = canonical["${SyncCollection.UPNEXT.wire}/${rid.substringAfter('/')}"]
                        if (queue != null && !RecordMerger.isDead(queue)) {
                            val mark = requireNotNull(record.fields.getValue("played").c)
                            val added = requireNotNull(queue.fields.getValue("in").c)
                            assertTrue(added > mark, "stale Up next add outlived mark-played in $rid")
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val WALL_BASE = 1_791_212_400_000L
        val SKEWS = listOf(0L, 120_000L, -240_000L, 299_000L, -60_000L)
        val SEEDS = listOf(7L, 42L, 20_261_019L)
        const val OPS_PER_SEED = 80
    }
}
