// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing.sync

import ch.lkmc.neutrodyne.sync.protocol.EpisodeKey
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.GroupId
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.HlcClock
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.PodcastId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random

/** A bounded deterministic run. Failure messages contain the seed and complete ordered wire trace. */
class SyncConvergenceHarness(
    private val seed: Long,
    serverFactory: (() -> Long) -> SyncServerAdapter = { InMemorySyncServer(it) },
) {
    private val random = Random(seed xor TRANSPORT_SALT)
    private var now = START_MS
    private val server = serverFactory { now }
    private val replicas =
        List(SeededSyncOperations.REPLICAS) { index ->
            val node = NodeId((index + 1).toString(16).padStart(NodeId.LENGTH, '0'))
            Replica(index, HlcClock(node, { now + SKEWS[index] }))
        }
    private val network = mutableListOf<Delivery>()
    private val trace = mutableListOf<String>()
    private var backup: Map<SyncAddress, RecordState> = emptyMap()
    private var expiries = 0

    fun run(operations: List<SeededSyncOperation> = SeededSyncOperations.generate(seed)): CanonicalSyncSnapshot {
        try {
            bootstrap()
            for (operation in operations) {
                now += STEP_MS
                execute(operation)
                // A device can edit for many steps while disconnected; queued packets can outlive it.
                if (random.nextInt(SCHEDULE_CHANCE) == 0) round(replicas.random(random))
            }
            quiesce()
            val serverRaw = server.snapshot()
            val known = serverRaw.keys + replicas.flatMap { it.raw.keys }
            val expected = canonicalSyncSnapshot(serverRaw, known)
            for ((address, state) in expected.raw) {
                check(serverRaw[address] == state) { "server failed to forward raw fields at ${address.key}" }
            }
            assertSyncInvariants(expected)
            for (replica in replicas) {
                val actual = canonicalSyncSnapshot(replica.raw, known)
                check(actual == expected) {
                    "replica=${replica.index} differs\nserver=${expected.canonicalRaw()}\n" +
                        "replica=${actual.canonicalRaw()}"
                }
                assertSyncInvariants(actual)
                check(replica.outbox.isEmpty()) { "undrained outbox" }
            }
            check(network.isEmpty()) { "undelivered packets" }
            return expected
        } catch (failure: Exception) {
            throw AssertionError("seed=$seed\n${trace.joinToString("\n")}\n${failure.message}", failure)
        }
    }

    private fun bootstrap() {
        // Start every device offline, creating genuine same-feed/name conflicts before any pull.
        for (replica in replicas) {
            execute(SeededSyncOperation(replica.index, SyncOperation.SUBSCRIBE, replica.index, 0, 0))
            execute(SeededSyncOperation(replica.index, SyncOperation.GROUP_CREATE, replica.index, 0, 0))
            execute(SeededSyncOperation(replica.index, SyncOperation.MEMBER_ADD, replica.index, 0, 0))
            execute(SeededSyncOperation(replica.index, SyncOperation.QUEUE_ADD, replica.index, 0, 0))
            execute(SeededSyncOperation(replica.index, SyncOperation.POSITION, replica.index, 0, 123))
        }
        replicas.forEach {
            it.online = true
            enqueue(it)
        }
        deliverAll()
        replicas.forEach(::pullAll)
        backup = server.snapshot()
        // With valid cursors and locally retained history, a restore forces original-clock replay.
        expire()
        replicas.forEach(::pullAll)
    }

    private fun execute(action: SeededSyncOperation) {
        val replica = replicas[action.device]
        val podcast = podcast(action.subject)
        val group = group(action.subject)
        val episode = RecordId.Episode(podcast.id, EpisodeKey("g:legacy-${action.item}"))
        val p = SyncAddress(SyncCollection.PODCAST, podcast)
        val g = SyncAddress(SyncCollection.GROUP, group)
        val e = SyncAddress(SyncCollection.EPISODE, episode)
        val q = SyncAddress(SyncCollection.UPNEXT, episode)
        val m = SyncAddress(SyncCollection.MEMBER, RecordId.Member(group.id, podcast(action.device).id))
        log(replica.index, action.operation.name, "[${action.subject},${action.item},${action.value}]")

        fun write(
            address: SyncAddress,
            values: Map<String, JsonElement>,
            sets: Map<String, Set<String>> = emptyMap(),
            reset: Set<String> = emptySet(),
        ) {
            val clock = replica.clock.tick()
            val hints =
                if (address.coll ==
                    SyncCollection.EPISODE
                ) {
                    MatchHints(guid = "episode-${action.item}", title = "Title ${action.value}")
                } else {
                    null
                }
            val state =
                RecordState(
                    address.coll,
                    values.mapValues { (name, value) ->
                        FieldState(
                            value,
                            clock,
                            name in reset,
                        )
                    },
                    sets,
                    hints,
                )
            replica.accept(address, state)
            replica.outbox += Delivery.Write(address, state)
        }

        fun scalar(
            address: SyncAddress,
            name: String,
            value: JsonElement,
        ) = write(address, mapOf(name to value))
        when (action.operation) {
            SyncOperation.SUBSCRIBE -> {
                write(
                    p,
                    mapOf(
                        "feedUrl" to JsonPrimitive(feed(action.subject)),
                        "subscribed" to JsonPrimitive(true),
                        "subscribedAt" to JsonPrimitive(START_MS - action.subject * STEP_MS),
                        "title" to JsonPrimitive("Feed ${action.subject}"),
                    ),
                    mapOf("feedKeys" to setOf("feeds.test/${action.subject % FEEDS}")),
                )
            }

            SyncOperation.UNSUBSCRIBE -> {
                scalar(p, "subscribed", JsonPrimitive(false))
            }

            SyncOperation.FEED_MOVE -> {
                write(
                    p,
                    mapOf("feedUrl" to JsonPrimitive(feed(action.subject + 1))),
                    mapOf(
                        "feedKeys" to
                            setOf("feeds.test/${action.subject % FEEDS}", "feeds.test/${(action.subject + 1) % FEEDS}"),
                    ),
                )
            }

            SyncOperation.GROUP_CREATE -> {
                write(
                    g,
                    mapOf(
                        "name" to JsonPrimitive("Group ${action.subject % GROUPS}"),
                        "deleted" to JsonPrimitive(false),
                        "createdAt" to JsonPrimitive(START_MS - action.subject * STEP_MS),
                        "ok" to JsonPrimitive(order(action.value)),
                    ),
                )
            }

            SyncOperation.GROUP_RENAME -> {
                scalar(
                    g,
                    "name",
                    JsonPrimitive(
                        if (action.value % GROUPS ==
                            0
                        ) {
                            "  GROUP 0 "
                        } else {
                            "Group 1"
                        },
                    ),
                )
            }

            SyncOperation.GROUP_DELETE -> {
                scalar(g, "deleted", JsonPrimitive(true))
            }

            SyncOperation.MEMBER_ADD -> {
                write(
                    m,
                    mapOf(
                        "in" to JsonPrimitive(true),
                        "ok" to JsonPrimitive(order(action.value)),
                        "addedAt" to JsonPrimitive(now),
                    ),
                )
            }

            SyncOperation.MEMBER_REMOVE -> {
                scalar(m, "in", JsonPrimitive(false))
            }

            SyncOperation.MEMBER_MOVE -> {
                scalar(m, "ok", JsonPrimitive(order(action.value)))
            }

            SyncOperation.QUEUE_ADD -> {
                write(
                    q,
                    mapOf(
                        "in" to JsonPrimitive(true),
                        "ok" to JsonPrimitive(order(action.value)),
                    ),
                )
            }

            SyncOperation.QUEUE_MOVE -> {
                scalar(q, "ok", JsonPrimitive(order(action.value)))
            }

            SyncOperation.QUEUE_REMOVE -> {
                scalar(q, "in", JsonPrimitive(false))
            }

            SyncOperation.POSITION -> {
                write(e, mapOf("pos" to position(action.value.toLong() + 1)))
            }

            SyncOperation.ZERO_WITHOUT_RESET -> {
                write(e, mapOf("pos" to position(0)))
            }

            SyncOperation.RESET -> {
                write(e, mapOf("pos" to position(0)), reset = setOf("pos"))
            }

            SyncOperation.PLAYED, SyncOperation.UNPLAYED -> {
                val played = action.operation == SyncOperation.PLAYED
                write(
                    e,
                    mapOf(
                        "played" to JsonPrimitive(played),
                        "playedAt" to if (played) JsonPrimitive(now) else JsonNull,
                        "playCount" to JsonPrimitive(action.value),
                        "lastPlayedAt" to if (played) JsonPrimitive(now) else JsonNull,
                    ),
                )
                if (played) {
                    write(e, mapOf("pos" to position(0)), reset = setOf("pos"))
                    scalar(q, "in", JsonPrimitive(false))
                }
            }

            SyncOperation.FAVOURITE -> {
                scalar(e, "fav", JsonPrimitive(action.value % 2 == 0))
            }

            SyncOperation.SETTING -> {
                scalar(
                    SyncAddress(SyncCollection.SETTING, RecordId.Setting("playback.speed")),
                    "v",
                    if (action.value % GROUPS == 0) JsonNull else JsonPrimitive(1.0 + action.item * 0.5),
                )
            }

            SyncOperation.REKEY -> {
                replica.outbox.add(Delivery.Rekey(episode, episode.copy(key = EpisodeKey("g:stable-${action.item}"))))
            }

            SyncOperation.OFFLINE -> {
                replica.online = false
            }

            SyncOperation.ONLINE -> {
                replica.online = true
                round(replica)
            }

            SyncOperation.PUSH -> {
                if (replica.online) enqueue(replica)
            }

            SyncOperation.DELIVER -> {
                deliverOne()
            }

            SyncOperation.PULL -> {
                if (replica.online) pullPage(replica)
            }

            SyncOperation.EXPIRE -> {
                expire()
            }
        }
    }

    private fun round(replica: Replica) {
        if (!replica.online) return
        enqueue(replica)
        deliverOne()
        pullPage(replica)
    }

    private fun enqueue(replica: Replica) {
        network += replica.outbox
        if (replica.outbox.isNotEmpty()) {
            // Immutable sent snapshots can arrive after later writes and after their acknowledgement.
            network += replica.outbox.random(random)
        }
        replica.outbox.clear()
    }

    private fun deliverOne() {
        if (network.isEmpty()) return
        val delivery = network.removeAt(random.nextInt(network.size))
        when (delivery) {
            is Delivery.Write -> {
                val payload = canonicalJson(SyncJson.encodeToJsonElement(RecordState.serializer(), delivery.state))
                log(SERVER, "deliver", "${delivery.address.key}:$payload")
                server.push(delivery.address, delivery.state)
            }

            is Delivery.Rekey -> {
                log(SERVER, "rekey", "${delivery.from.rid}->${delivery.to.rid}")
                server.rekey(delivery.from, delivery.to)
            }
        }
    }

    private fun deliverAll() {
        while (network.isNotEmpty()) deliverOne()
    }

    private fun pullPage(replica: Replica): Boolean {
        val page =
            try {
                server.page(replica.cursor, random.nextInt(1, MAX_PAGE_CUT + 1))
            } catch (failure: ProtocolException) {
                if (failure.code != SyncErrorCode.CURSOR_EXPIRED) throw failure
                log(replica.index, "cursor_expired", JsonPrimitive(replica.cursor).toString())
                expiries++
                replica.cursor = null
                replica.replaying = true
                server.page(null, random.nextInt(1, MAX_PAGE_CUT + 1))
            }
        log(replica.index, "page", "${page.cursor}:[${page.records.joinToString { "${it.seq}/${it.address.key}" }}]")
        for (record in page.records.shuffled(random)) {
            replica.accept(record.address, record.state)
            replica.accept(record.address, record.state)
        }
        replica.cursor = page.cursor
        if (!page.hasMore && replica.replaying) {
            // Re-assert raw values, tombstones and reset flags with the complete original clocks.
            for ((address, state) in canonicalSyncSnapshot(replica.raw).raw) {
                replica.outbox += Delivery.Write(address, state)
            }
            replica.replaying = false
        }
        return page.hasMore
    }

    private fun pullAll(replica: Replica) {
        var count = 0
        while (pullPage(replica)) check(++count < PAGE_BUDGET) { "pagination did not terminate" }
    }

    private fun expire() {
        // S15 has one authoritative redirect assignment. Roll back retained content, not that
        // identity ledger; arbitrary identity rollback needs the real server's MS1 restore work.
        val restore =
            if (expiries % 2 == 0) {
                server.snapshot().mapValues { (address, state) ->
                    val prior = backup[address]
                    if (address.coll in CONTENT_COLLECTIONS && state.mergedInto == null && prior?.mergedInto == null) {
                        prior ?: state
                    } else {
                        state
                    }
                }
            } else {
                null
            }
        server.expire(restore)
        log(SERVER, "expire", if (restore == null) "epoch" else "content-rollback:${restore.size}")
    }

    private fun quiesce() {
        replicas.forEach {
            it.online = true
            enqueue(it)
        }
        repeat(QUIESCENCE_ROUNDS) {
            val before = server.snapshot()
            deliverAll()
            replicas.forEach(::pullAll)
            replicas.forEach(::enqueue)
            deliverAll()
            replicas.forEach(::pullAll)
            if (before == server.snapshot() && network.isEmpty() && replicas.all { it.outbox.isEmpty() }) return
        }
        error("did not quiesce")
    }

    private fun log(
        device: Int,
        operation: String,
        payload: String,
    ) {
        trace += "${trace.size}:$device:$operation:$payload"
    }

    private class Replica(
        val index: Int,
        val clock: HlcClock,
    ) {
        val raw = mutableMapOf<SyncAddress, RecordState>()
        val outbox = mutableListOf<Delivery>()
        var cursor: String? = null
        var online = false
        var replaying = false

        fun accept(
            address: SyncAddress,
            state: RecordState,
        ) {
            // Receiving a losing or guarded field still raises the HLC before any later local edit.
            state.fields.values
                .mapNotNull { it.c }
                .plus(state.matchClocks.values)
                .forEach(clock::receive)
            val before = raw[address]
            val merged = RecordMerger.merge(before, state).record
            val pos = state.fields["pos"]
            if (pos != null && pos.v.jsonObject
                    .getValue("ms")
                    .jsonPrimitive.long == 0L && !pos.reset
            ) {
                check(merged.fields["pos"] == before?.fields?.get("pos")) { "unguarded zero changed position" }
            }
            raw[address] = merged
        }
    }

    private sealed class Delivery {
        data class Write(
            val address: SyncAddress,
            val state: RecordState,
        ) : Delivery()

        data class Rekey(
            val from: RecordId.Episode,
            val to: RecordId.Episode,
        ) : Delivery()
    }

    private companion object {
        const val START_MS = 1_791_072_000_000L
        const val STEP_MS = 1_000L
        const val TRANSPORT_SALT = 0x51f15eL
        const val FEEDS = 3
        const val GROUPS = 2
        const val SERVER = -1
        const val MAX_PAGE_CUT = 7
        const val PAGE_BUDGET = 1_000
        const val QUIESCENCE_ROUNDS = 8
        const val SCHEDULE_CHANCE = 3
        val CONTENT_COLLECTIONS =
            setOf(
                SyncCollection.EPISODE,
                SyncCollection.UPNEXT,
                SyncCollection.MEMBER,
                SyncCollection.SETTING,
            )
        val SKEWS = listOf(-240_000L, -120_000L, 0L, 120_000L, 240_000L)
        val ORDER_KEYS = listOf("a0", "a1", "a2") // Deliberate ties exercise record-ID ordering.

        fun podcast(index: Int) = RecordId.Podcast(PodcastId(uuid(index)))

        fun group(index: Int) = RecordId.Group(GroupId(uuid(index + SeededSyncOperations.REPLICAS)))

        fun uuid(index: Int) = "00000000-0000-4000-8000-${(index + 1).toString(16).padStart(12, '0')}"

        fun feed(index: Int) = "https://feeds.test/${index % FEEDS}"

        fun order(value: Int) = ORDER_KEYS[value % ORDER_KEYS.size]

        fun position(ms: Long) =
            JsonObject(
                mapOf(
                    "ms" to JsonPrimitive(ms),
                    "dur" to JsonPrimitive(3_600_000),
                    "src" to JsonPrimitive("STREAM"),
                ),
            )
    }
}
