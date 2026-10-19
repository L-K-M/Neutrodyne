// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing.sync

import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.GroupKeyInfo
import ch.lkmc.neutrodyne.sync.protocol.HlcClock
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.PodcastKeyInfo
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SurvivorRule
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** Collection is part of the identity: episode and Up next deliberately share an ID shape. */
data class SyncAddress(
    val coll: SyncCollection,
    val id: RecordId,
) {
    init {
        require(RecordId.fromWire(coll, id.toWire()) == id)
    }

    val key: String get() = "${coll.wire}/${id.rid}"
}

data class SequencedSyncRecord(
    val address: SyncAddress,
    val state: RecordState,
    val seq: Long,
)

data class SyncPage(
    val records: List<SequencedSyncRecord>,
    val cursor: String,
    val hasMore: Boolean,
)

/**
 * Single-account, single-writer test server. All fields use RecordMerger; this class owns only
 * identity, lifecycle and cursor mechanics. No transport, persistence, auth or retention timer.
 * Records stay within the documented revival horizon; expiry can simulate GC or a restore.
 */
class InMemorySyncServer(
    private val wallMs: () -> Long,
) {
    private val records = mutableMapOf<SyncAddress, SequencedSyncRecord>()
    private val clock = HlcClock(NodeId.SERVER, wallMs)
    private var seq = 0L
    private var epoch = 1
    private var minCursor = 0L

    fun snapshot(): Map<SyncAddress, RecordState> = records.mapValues { it.value.state }

    fun push(
        address: SyncAddress,
        state: RecordState,
    ) {
        require(address.coll == state.coll && state.mergedInto == null)
        ProtocolValidation.state(state)
        state.fields.values.mapNotNull { it.c }.plus(state.matchClocks.values).forEach {
            ProtocolValidation.admit(it, wallMs())
            clock.receive(it)
        }
        val target = resolve(address)
        val before = records[target]?.state
        merge(target, state)
        lifecycle(target, before)
        dedupe()
    }

    fun rekey(
        from: RecordId.Episode,
        to: RecordId.Episode,
    ) {
        require(from.podcast == to.podcast && from != to)
        for (coll in listOf(SyncCollection.EPISODE, SyncCollection.UPNEXT)) {
            redirect(resolve(SyncAddress(coll, from)), resolve(SyncAddress(coll, to)))
        }
    }

    fun page(
        cursor: String?,
        limit: Int,
    ): SyncPage {
        require(limit in 1..MAX_PAGE)
        val since = cursor?.let(::readCursor) ?: 0L
        val pending = records.values.filter { it.seq > since }.sortedBy { it.seq }
        val page = pending.take(limit)
        val last = page.lastOrNull()?.seq ?: seq
        return SyncPage(page, cursor(last), pending.size > page.size)
    }

    /** Rotate epoch and optionally lose records, like restoring an older server backup. */
    fun expire(restore: Map<SyncAddress, RecordState>? = null) {
        epoch++
        minCursor = if (restore == null) 0L else seq
        if (restore == null) return

        records.clear()
        for ((address, state) in restore.entries.sortedBy { it.key.key }) store(address, state)
    }

    private fun readCursor(value: String): Long {
        ProtocolValidation.cursor(value)
        val parts = value.split(':')
        val since = parts[2].toLong()
        if (parts[1] != epoch.toString(16).padStart(EPOCH_WIDTH, '0') || since < minCursor || since > seq) {
            throw ProtocolException(SyncErrorCode.CURSOR_EXPIRED, "test cursor expired")
        }
        return since
    }

    private fun cursor(at: Long) = "c:${epoch.toString(16).padStart(EPOCH_WIDTH, '0')}:$at"

    private fun merge(
        address: SyncAddress,
        incoming: RecordState,
    ) {
        val before = records[address]?.state
        val after = RecordMerger.merge(before, incoming).record
        if (after != before) store(address, after)
    }

    private fun store(
        address: SyncAddress,
        state: RecordState,
    ) {
        records[address] = SequencedSyncRecord(address, state, ++seq)
    }

    private fun resolve(address: SyncAddress): SyncAddress = terminalSyncAddress(address) { records[it]?.state }

    private fun redirect(
        from: SyncAddress,
        to: SyncAddress,
    ) {
        if (from == to) return
        val source = records[from]?.state ?: RecordState(from.coll)
        check(source.mergedInto == null) { "redirect assignment must be authoritative" }
        val before = records[to]?.state
        merge(to, source)
        merge(from, RecordState(from.coll, mergedInto = to.id))
        lifecycle(to, before)

        // Re-key children now; future writes are mapped through the same parent redirects.
        val children = records.keys.toList()
        for (child in children) {
            val target = resolve(child)
            if (target != child && records[child]?.state?.mergedInto == null) redirect(child, target)
        }
    }

    private fun lifecycle(
        address: SyncAddress,
        before: RecordState?,
    ) {
        if (address.coll !in setOf(SyncCollection.PODCAST, SyncCollection.GROUP)) return
        val after = records.getValue(address).state
        val wasDead = before?.let(RecordMerger::isDead) == true
        if (RecordMerger.isDead(after) && !wasDead && address.id is RecordId.Podcast) {
            val podcast = address.id.id
            records.keys
                .filter { it.coll == SyncCollection.UPNEXT && (it.id as RecordId.Episode).podcast == podcast }
                .forEach { child ->
                    if (!RecordMerger.isDead(records.getValue(child).state)) {
                        merge(
                            child,
                            RecordState(
                                child.coll,
                                mapOf("in" to FieldState(JsonPrimitive(false), clock.tick())),
                            ),
                        )
                    }
                }
        }
        if (!wasDead || RecordMerger.isDead(after)) return

        // Revival must make retained history visible to clients whose cursors already passed it.
        records.keys
            .filter { child ->
                val id = child.id
                val parent = address.id
                if (parent is RecordId.Podcast) {
                    (id is RecordId.Episode && id.podcast == parent.id && child.coll == SyncCollection.EPISODE) ||
                        (id is RecordId.Member && id.podcast == parent.id)
                } else {
                    parent is RecordId.Group && id is RecordId.Member && id.group == parent.id
                }
            }.forEach { store(it, records.getValue(it).state) }
    }

    private fun dedupe() {
        while (true) {
            val candidates =
                records.values
                    .filter {
                        val identity = it.address.coll in setOf(SyncCollection.PODCAST, SyncCollection.GROUP)
                        identity && it.state.mergedInto == null
                    }.sortedBy { it.address.key }
            val pair =
                candidates.firstNotNullOfOrNull { a ->
                    candidates
                        .firstOrNull { b ->
                            a.address.key < b.address.key &&
                                a.address.coll == b.address.coll && collides(a.state, b.state)
                        }?.let { a to it }
                } ?: return
            val (a, b) = pair
            val survivor = survivor(a, b)
            val loser = if (survivor == a.address) b.address else a.address
            val wasDead = RecordMerger.isDead(records.getValue(survivor).state)
            redirect(loser, survivor)
            if (wasDead && survivor.coll == SyncCollection.PODCAST) {
                val before = records.getValue(survivor).state
                merge(
                    survivor,
                    RecordState(
                        survivor.coll,
                        mapOf("subscribed" to FieldState(JsonPrimitive(true), clock.tick())),
                    ),
                )
                lifecycle(survivor, before)
            }
        }
    }

    private fun collides(
        a: RecordState,
        b: RecordState,
    ): Boolean {
        if (a.coll == SyncCollection.GROUP) {
            val name = groupNameKey(a) ?: return false
            return !RecordMerger.isDead(a) && !RecordMerger.isDead(b) && name == groupNameKey(b)
        }
        if (RecordMerger.isDead(a) && RecordMerger.isDead(b)) return false
        return feedKeys(a).intersect(feedKeys(b)).isNotEmpty()
    }

    private fun survivor(
        a: SequencedSyncRecord,
        b: SequencedSyncRecord,
    ): SyncAddress {
        if (a.address.coll == SyncCollection.PODCAST) {
            // A retained dead owner is revived even when the new subscriber's timestamp is older.
            if (RecordMerger.isDead(a.state)) return a.address
            if (RecordMerger.isDead(b.state)) return b.address
            val aId = (a.address.id as RecordId.Podcast).id
            val bId = (b.address.id as RecordId.Podcast).id
            val chosen =
                SurvivorRule.podcast(
                    PodcastKeyInfo(aId, number(a.state, "subscribedAt")),
                    PodcastKeyInfo(bId, number(b.state, "subscribedAt")),
                )
            return if (chosen.syncId == aId) a.address else b.address
        }
        val aId = (a.address.id as RecordId.Group).id
        val bId = (b.address.id as RecordId.Group).id
        val chosen =
            SurvivorRule.group(
                GroupKeyInfo(aId, number(a.state, "createdAt")),
                GroupKeyInfo(bId, number(b.state, "createdAt")),
            )
        return if (chosen.uuid == aId) a.address else b.address
    }

    private fun number(
        state: RecordState,
        name: String,
    ): Long = (state.fields[name]?.v as? JsonPrimitive)?.long ?: Long.MAX_VALUE

    private companion object {
        const val MAX_PAGE = 1_000
        const val EPOCH_WIDTH = 8
    }
}

internal fun feedKeys(state: RecordState): Set<String> =
    state.growSets["feedKeys"].orEmpty() +
        listOfNotNull((state.fields["feedUrl"]?.v as? JsonPrimitive)?.content?.let(UrlNormalizer::forIdentity))

internal fun groupNameKey(state: RecordState): String? =
    (state.fields["name"]?.v as? JsonPrimitive)?.content?.let {
        (GroupNames.validate(it) as? GroupNames.Validation.Valid)?.nameKey
    }
