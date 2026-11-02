// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing.sync

import ch.lkmc.neutrodyne.sync.protocol.EffectiveEpisodeState
import ch.lkmc.neutrodyne.sync.protocol.EpisodeStateRules
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.ReplicatedOrder
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Redirects preserve all raw inputs at their terminal identity, including hidden episode marks. */
data class CanonicalSyncSnapshot(
    val raw: Map<SyncAddress, RecordState>,
    val redirects: Map<SyncAddress, SyncAddress>,
    val episodes: Map<SyncAddress, EffectiveEpisodeState>,
    val podcasts: Set<SyncAddress>,
    val groups: List<SyncAddress>,
    val members: Map<RecordId.Group, List<SyncAddress>>,
    val upNext: List<SyncAddress>,
    val settings: Map<SyncAddress, JsonElement>,
) {
    /** Stable JSON for traces, diagnostics and cross-run byte comparisons. Map equality is also checked. */
    fun canonicalRaw(): String =
        canonicalJson(
            JsonObject(
                buildMap {
                    for ((address, state) in raw) {
                        put(address.key, SyncJson.encodeToJsonElement(RecordState.serializer(), state))
                    }
                    val edges = redirects.mapKeys { it.key.key }.mapValues { JsonPrimitive(it.value.key) }
                    put("redirects", JsonObject(edges))
                },
            ),
        )
}

/** Pure canonicalisation of storage identities; never derives raw fields from visible projections. */
fun canonicalSyncSnapshot(
    records: Map<SyncAddress, RecordState>,
    knownAddresses: Set<SyncAddress> = records.keys,
): CanonicalSyncSnapshot {
    val raw = mutableMapOf<SyncAddress, RecordState>()
    val redirects = mutableMapOf<SyncAddress, SyncAddress>()
    for ((address, state) in records.entries.sortedBy { it.key.key }) {
        val target = terminalSyncAddress(address, records)
        raw[target] = RecordMerger.merge(raw[target], state.copy(mergedInto = null)).record
    }
    for (address in knownAddresses) {
        val target = terminalSyncAddress(address, records)
        if (target != address) redirects[address] = target
    }
    val episodes =
        raw
            .filterKeys {
                it.coll == SyncCollection.EPISODE
            }.mapValues { EpisodeStateRules.derive(it.value.fields) }
    val podcasts = raw.filter { it.key.coll == SyncCollection.PODCAST && !RecordMerger.isDead(it.value) }.keys
    val groups = ordered(raw.filter { it.key.coll == SyncCollection.GROUP && !RecordMerger.isDead(it.value) })
    val members =
        raw
            .filter { (address, state) ->
                val id = address.id
                address.coll == SyncCollection.MEMBER && state.boolean("in") && id is RecordId.Member &&
                    SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(id.podcast)) in podcasts &&
                    SyncAddress(SyncCollection.GROUP, RecordId.Group(id.group)) in groups
            }.entries
            .groupBy { RecordId.Group((it.key.id as RecordId.Member).group) }
            .mapValues { (_, items) -> ordered(items.associate { it.key to it.value }) }
    val upNext =
        ordered(
            raw.filter { (address, state) ->
                val id = address.id
                address.coll == SyncCollection.UPNEXT && state.boolean("in") && id is RecordId.Episode &&
                    SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(id.podcast)) in podcasts &&
                    episodes[SyncAddress(SyncCollection.EPISODE, id)]?.allowsUpNext != false
            },
        )
    val settings =
        raw.filterKeys { it.coll == SyncCollection.SETTING }.mapValues {
            it.value.fields
                .getValue("v")
                .v
        }
    return CanonicalSyncSnapshot(raw.toMap(), redirects.toMap(), episodes, podcasts, groups, members, upNext, settings)
}

internal fun terminalSyncAddress(
    address: SyncAddress,
    records: Map<SyncAddress, RecordState>,
): SyncAddress = terminalSyncAddress(address) { records[it] }

internal fun terminalSyncAddress(
    address: SyncAddress,
    lookup: (SyncAddress) -> RecordState?,
): SyncAddress {
    val seen = mutableSetOf<SyncAddress>()

    fun resolve(start: SyncAddress): SyncAddress {
        check(seen.add(start)) { "redirect cycle at ${start.key}" }
        // Interleave child redirects with each parent hop: A -> B -> C can have a rekey at B.
        // Jumping straight to C would strand an offline write whose original A child never existed on the server.
        val next = lookup(start)?.mergedInto
        if (next != null) return resolve(start.copy(id = next))
        val parent =
            when (val id = start.id) {
                is RecordId.Member -> {
                    val g = SyncAddress(SyncCollection.GROUP, RecordId.Group(id.group))
                    val p = SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(id.podcast))
                    val group = lookup(g)?.mergedInto as? RecordId.Group
                    val podcast = lookup(p)?.mergedInto as? RecordId.Podcast
                    start.copy(id = RecordId.Member(group?.id ?: id.group, podcast?.id ?: id.podcast))
                }

                is RecordId.Episode -> {
                    val p = SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(id.podcast))
                    val podcast = lookup(p)?.mergedInto as? RecordId.Podcast
                    start.copy(id = id.copy(podcast = podcast?.id ?: id.podcast))
                }

                else -> {
                    start
                }
            }
        return if (parent == start) start else resolve(parent)
    }
    return resolve(address)
}

internal fun assertSyncInvariants(snapshot: CanonicalSyncSnapshot) {
    val owners = mutableSetOf<String>()
    for (address in snapshot.podcasts) {
        for (key in feedKeys(
            snapshot.raw.getValue(address),
        )) {
            check(owners.add(key)) { "duplicate canonical feed: $key" }
        }
    }
    val names = snapshot.groups.mapNotNull { groupNameKey(snapshot.raw.getValue(it)) }
    check(names.distinct().size == names.size) { "duplicate canonical group name" }
    for (address in snapshot.upNext) {
        check(snapshot.episodes[SyncAddress(SyncCollection.EPISODE, address.id)]?.allowsUpNext != false) {
            "played Up next item"
        }
    }
    val lists = listOf(snapshot.groups, snapshot.upNext) + snapshot.members.values
    for (list in lists) {
        check(list.distinct().size == list.size) { "duplicate list identity" }
        for ((a, b) in list.zipWithNext()) {
            check(
                ReplicatedOrder.compare(
                    orderKey(snapshot.raw.getValue(a)),
                    a.id,
                    orderKey(snapshot.raw.getValue(b)),
                    b.id,
                ) <
                    0,
            ) { "list is not totally ordered" }
        }
    }
}

private fun ordered(records: Map<SyncAddress, RecordState>): List<SyncAddress> =
    records.keys.sortedWith { a, b ->
        ReplicatedOrder.compare(orderKey(records.getValue(a)), a.id, orderKey(records.getValue(b)), b.id)
    }

private fun orderKey(state: RecordState): String = (state.fields["ok"]?.v as? JsonPrimitive)?.content.orEmpty()

private fun RecordState.boolean(name: String): Boolean = (fields[name]?.v as? JsonPrimitive)?.booleanOrNull == true

internal fun canonicalJson(value: JsonElement): String =
    when (value) {
        is JsonObject -> {
            value.keys.sorted().joinToString(",", "{", "}") { key ->
                "${JsonPrimitive(key)}:${canonicalJson(value.getValue(key))}"
            }
        }

        is JsonArray -> {
            value.joinToString(",", "[", "]", transform = ::canonicalJson)
        }

        else -> {
            value.toString()
        }
    }
