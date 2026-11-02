// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.HlcClock
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A single change's tentative writes. Conflicts/quota failures discard this before touching SQL. */
internal class RecordChanges(
    private val store: SyncWrite,
    val clock: HlcClock,
    private val now: Long,
    private val byDevice: String?,
) {
    private val changed = linkedMapOf<RecordKey, StoredRecord>()
    private val redirects = linkedMapOf<RecordKey, RecordKey>()
    private val authority = store.redirects()

    fun record(key: RecordKey): StoredRecord? = changed[key] ?: store.record(key)

    fun hasRedirect(key: RecordKey): Boolean =
        key in redirects || key in authority || record(key)?.raw?.mergedInto != null

    fun routeUnseen(
        from: RecordKey,
        to: RecordKey,
    ) {
        if (!hasRedirect(from)) redirect(from, to)
    }

    fun records(): Sequence<StoredRecord> = store.records().filter { it.key !in changed } + changed.values.asSequence()

    fun resolve(key: RecordKey): RecordKey {
        var current = key
        val visited = mutableSetOf<RecordKey>()
        repeat(ProtocolLimits.MAX_REDIRECT_HOPS + 1) { hops ->
            if (!visited.add(current)) invalidChange("redirect cycle")
            val direct =
                redirects[current] ?: authority[current] ?: record(current)?.raw?.mergedInto?.let {
                    RecordKey(current.collection, it)
                }
            val next = direct ?: remapParents(current)
            if (next == current) return current
            if (hops == ProtocolLimits.MAX_REDIRECT_HOPS) invalidChange("redirect hop limit")
            current = next
        }
        error("unreachable redirect traversal")
    }

    private fun remapParents(key: RecordKey): RecordKey =
        when (val id = key.id) {
            is RecordId.Episode -> {
                val parent = resolve(RecordKey(SyncCollection.PODCAST, RecordId.Podcast(id.podcast)))
                key.copy(id = id.copy(podcast = (parent.id as RecordId.Podcast).id))
            }

            is RecordId.Member -> {
                val group = resolve(RecordKey(SyncCollection.GROUP, RecordId.Group(id.group)))
                val podcast = resolve(RecordKey(SyncCollection.PODCAST, RecordId.Podcast(id.podcast)))
                key.copy(id = RecordId.Member((group.id as RecordId.Group).id, (podcast.id as RecordId.Podcast).id))
            }

            else -> {
                key
            }
        }

    fun save(
        key: RecordKey,
        raw: RecordState,
    ) {
        ProtocolValidation.state(raw)
        val prior = record(key)
        if (prior?.raw == raw) return
        val deadAt = if (RecordMerger.isDead(raw)) prior?.deadAtMs ?: now else null
        changed[key] = StoredRecord(key, raw, prior?.seq ?: 0, byDevice, now, deadAt)
    }

    fun stamp(
        key: RecordKey,
        name: String,
        value: JsonElement,
    ) {
        val raw = record(key)?.raw ?: RecordState(key.collection)
        save(
            key,
            RecordMerger
                .merge(
                    raw,
                    RecordState(
                        key.collection,
                        fields = mapOf(name to FieldState(value, clock.tick())),
                    ),
                ).record,
        )
    }

    fun resequence(key: RecordKey) {
        record(key)?.let { changed[key] = it.copy(byDevice = byDevice, updatedAtMs = now) }
    }

    /** The ledger survives tombstone GC; never derive a new destination from current survivor ranks. */
    fun redirect(
        from: RecordKey,
        to: RecordKey,
    ) {
        if (from == to || from.collection != to.collection) invalidChange("invalid redirect")
        val assigned =
            redirects[from] ?: authority[from] ?: record(from)?.raw?.mergedInto?.let {
                RecordKey(from.collection, it)
            }
        if (assigned != null && assigned != to) invalidChange("conflicting authoritative redirect")
        redirects[from] = to
        resolve(from)
        val raw = record(from)?.raw ?: RecordState(from.collection)
        save(from, RecordMerger.merge(raw, RecordState(from.collection, mergedInto = to.id)).record)
    }

    fun move(
        from: RecordKey,
        to: RecordKey,
    ) {
        if (from == to) return
        val raw = record(from)?.raw ?: RecordState(from.collection)
        save(to, RecordMerger.merge(record(to)?.raw, raw.copy(mergedInto = null)).record)
        redirect(from, to)
    }

    fun podcastOwners(keys: Set<String>): Set<RecordKey> =
        (
            keys.flatMap { store.podcastOwners(it) }.filter { it !in changed } +
                changed.values
                    .filter {
                        it.key.collection == SyncCollection.PODCAST &&
                            podcastKeys(it.raw).any(keys::contains)
                    }.map { it.key }
        ).toSet()

    fun groupOwner(
        name: String,
        excluding: RecordKey,
    ): RecordKey? =
        changed.values
            .firstOrNull {
                it.key != excluding && it.key.collection == SyncCollection.GROUP &&
                    groupName(it.raw) == name
            }?.key
            ?: store.groupOwner(name)?.takeIf { it != excluding && it !in changed }

    fun commit(liveCount: Long): Long {
        val delta =
            changed.values.sumOf { next ->
                val before = store.record(next.key)?.let { !RecordMerger.isDead(it.raw) } == true
                val after = !RecordMerger.isDead(next.raw)
                (if (after) 1L else 0L) - (if (before) 1L else 0L)
            }
        if (delta > 0 && liveCount + delta > store.account.quotaRecords) {
            throw ProtocolException(SyncErrorCode.QUOTA, "account record quota reached")
        }
        // Release all old names before inserting the survivor to respect the unique name index.
        changed.keys.filter { it.collection == SyncCollection.GROUP }.forEach { store.setGroupName(it, null) }
        changed.values.forEach { next ->
            store.put(next.copy(seq = store.nextSequence()))
            when (next.key.collection) {
                SyncCollection.PODCAST -> store.setPodcastKeys(next.key, podcastKeys(next.raw))
                SyncCollection.GROUP -> store.setGroupName(next.key, groupName(next.raw))
                else -> Unit
            }
        }
        redirects.forEach { (from, to) -> store.assignRedirect(from, to) }
        store.setAccount(store.account.copy(clockPacked = clock.packed))
        return liveCount + delta
    }

    companion object {
        fun podcastKeys(raw: RecordState): Set<String> =
            raw.growSets["feedKeys"].orEmpty() +
                listOfNotNull(
                    raw.fields["feedUrl"]?.v?.let {
                        UrlNormalizer.forIdentity((it as JsonPrimitive).content) ?: invalidChange("invalid feed URL")
                    },
                )

        fun groupName(raw: RecordState): String? {
            if (RecordMerger.isDead(raw)) return null
            val name = raw.fields["name"]?.v ?: return null
            return (GroupNames.validate((name as JsonPrimitive).content) as? GroupNames.Validation.Valid)?.nameKey
                ?: invalidChange("invalid group name")
        }
    }
}

internal fun invalidChange(message: String): Nothing = throw ProtocolException(SyncErrorCode.INVALID, message)
