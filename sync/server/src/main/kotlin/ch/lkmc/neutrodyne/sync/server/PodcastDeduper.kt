// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.PodcastKeyInfo
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SurvivorRule
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

internal class PodcastDeduper(
    private val changes: RecordChanges,
    private val now: Long,
    private val revivalWindowMs: Long,
) {
    fun dedupe(key: RecordKey): RecordKey {
        var survivor = key
        while (true) {
            val raw = changes.record(survivor)?.raw ?: return survivor
            if (RecordMerger.isDead(raw)) return survivor
            val owners =
                changes
                    .podcastOwners(RecordChanges.podcastKeys(raw))
                    .filter { it != survivor }
                    .mapNotNull(changes::record)
                    .filter { it.raw.mergedInto == null }
            // Multiple retained owners are possible after separate deaths. Select deterministically.
            val deadOwner =
                owners
                    .filter {
                        RecordMerger.isDead(it.raw) && it.deadAtMs != null && now - it.deadAtMs < revivalWindowMs
                    }.minWithOrNull(compareBy({ subscribedAt(it) }, { it.key.id.rid }))
            val liveOwner =
                owners
                    .filter { !RecordMerger.isDead(it.raw) }
                    .minWithOrNull(compareBy({ subscribedAt(it) }, { it.key.id.rid }))
            val owner = deadOwner ?: liveOwner ?: return survivor
            val winner =
                if (deadOwner != null) {
                    owner.key
                } else {
                    val ours = changes.record(survivor)!!
                    val chosen = SurvivorRule.podcast(info(ours), info(owner))
                    if (chosen.syncId == (survivor.id as RecordId.Podcast).id) survivor else owner.key
                }
            val loser = if (winner == survivor) owner.key else survivor
            merge(loser, winner)
            if (deadOwner != null) {
                changes.stamp(winner, "subscribed", JsonPrimitive(true))
                lifecycle(winner, owner)
            }
            survivor = winner
        }
    }

    private fun merge(
        loser: RecordKey,
        winner: RecordKey,
    ) {
        changes.move(loser, winner)
        val loserId = (loser.id as RecordId.Podcast).id
        val winnerId = (winner.id as RecordId.Podcast).id
        changes.records().filter { it.raw.mergedInto == null }.toList().forEach { child ->
            val destination =
                when (val id = child.key.id) {
                    is RecordId.Episode -> if (id.podcast == loserId) id.copy(podcast = winnerId) else null
                    is RecordId.Member -> if (id.podcast == loserId) id.copy(podcast = winnerId) else null
                    else -> null
                }
            destination?.let { changes.move(child.key, changes.resolve(child.key.copy(id = it))) }
        }
    }

    fun rekey(
        from: RecordId.Episode,
        to: RecordId.Episode,
    ): RecordKey {
        val source = changes.resolve(RecordKey(SyncCollection.EPISODE, from))
        val target = changes.resolve(RecordKey(SyncCollection.EPISODE, to))
        if (source != target) {
            // Parent relocation preserves the episode key and may precede its first rekey.
            // A prior episode-key assignment still cannot be changed, even through a parent alias.
            if ((source.id as RecordId.Episode).key != from.key) {
                invalidChange("conflicting rekey destination")
            }
            changes.move(source, target)
        }
        val queueSource = changes.resolve(RecordKey(SyncCollection.UPNEXT, from))
        val queueTarget = changes.resolve(RecordKey(SyncCollection.UPNEXT, to))
        if (queueSource != queueTarget) changes.move(queueSource, queueTarget)
        return target
    }

    fun lifecycle(
        key: RecordKey,
        before: StoredRecord?,
    ) {
        val after = changes.record(key) ?: return
        val wasDead = before?.let { RecordMerger.isDead(it.raw) } == true
        val dead = RecordMerger.isDead(after.raw)
        if (!dead && !wasDead) return
        if (dead && wasDead) return
        val podcast = (key.id as RecordId.Podcast).id
        changes.records().filter { !RecordMerger.isDead(it.raw) }.toList().forEach { child ->
            val id = child.key.id
            if (dead && child.key.collection == SyncCollection.UPNEXT &&
                (id as RecordId.Episode).podcast == podcast
            ) {
                changes.stamp(child.key, "in", JsonPrimitive(false))
            } else if (!dead && (
                    (
                        child.key.collection == SyncCollection.EPISODE &&
                            (id as RecordId.Episode).podcast == podcast
                    ) ||
                        (id is RecordId.Member && id.podcast == podcast)
                )
            ) {
                changes.resequence(child.key)
            }
        }
    }

    private fun info(record: StoredRecord): PodcastKeyInfo =
        PodcastKeyInfo((record.key.id as RecordId.Podcast).id, subscribedAt(record))

    private fun subscribedAt(record: StoredRecord): Long =
        (record.raw.fields["subscribedAt"]?.v as? JsonPrimitive)?.long ?: Long.MAX_VALUE
}
