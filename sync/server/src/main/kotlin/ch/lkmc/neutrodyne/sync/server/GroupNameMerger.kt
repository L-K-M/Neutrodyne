// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.GroupKeyInfo
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SurvivorRule
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

internal class GroupNameMerger(
    private val changes: RecordChanges,
) {
    fun dedupe(key: RecordKey): RecordKey {
        var survivor = key
        while (true) {
            val raw = changes.record(survivor)?.raw ?: return survivor
            val name = RecordChanges.groupName(raw) ?: return survivor
            val owner = changes.groupOwner(name, survivor) ?: return survivor
            val ours = changes.record(survivor)!!
            val other = changes.record(owner)!!
            val chosen = SurvivorRule.group(info(ours), info(other))
            val winner = if (chosen.uuid == (survivor.id as RecordId.Group).id) survivor else owner
            val loser = if (winner == survivor) owner else survivor
            changes.move(loser, winner)
            val loserId = (loser.id as RecordId.Group).id
            val winnerId = (winner.id as RecordId.Group).id
            changes
                .records()
                .filter { it.raw.mergedInto == null && it.key.id is RecordId.Member }
                .toList()
                .forEach { member ->
                    val id = member.key.id as RecordId.Member
                    if (id.group == loserId) {
                        changes.move(
                            member.key,
                            changes.resolve(RecordKey(SyncCollection.MEMBER, id.copy(group = winnerId))),
                        )
                    }
                }
            survivor = winner
        }
    }

    fun lifecycle(
        key: RecordKey,
        before: StoredRecord?,
    ) {
        if (before == null || !RecordMerger.isDead(before.raw) || RecordMerger.isDead(changes.record(key)!!.raw)) return
        val group = (key.id as RecordId.Group).id
        changes
            .records()
            .filter {
                val id = it.key.id
                id is RecordId.Member && id.group == group && !RecordMerger.isDead(it.raw)
            }.toList()
            .forEach { changes.resequence(it.key) }
    }

    private fun info(record: StoredRecord): GroupKeyInfo =
        GroupKeyInfo(
            (record.key.id as RecordId.Group).id,
            (record.raw.fields["createdAt"]?.v as? JsonPrimitive)?.long ?: Long.MAX_VALUE,
        )
}
