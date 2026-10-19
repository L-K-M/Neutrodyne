// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class FieldState(
    val v: JsonElement,
    val c: Hlc?,
    val reset: Boolean = false,
)

/** Raw wire inputs; effective Room values must never replace these on acknowledgement/replay. */
@Serializable
data class RecordState(
    val coll: SyncCollection,
    val fields: Map<String, FieldState> = emptyMap(),
    val growSets: Map<String, Set<String>> = emptyMap(),
    val match: MatchHints? = null,
    val mergedInto: RecordId? = null,
    val matchClocks: Map<String, Hlc> = emptyMap(),
) {
    val maxClock: Hlc? get() = (fields.values.mapNotNull { it.c } + matchClocks.values).maxOrNull()

    fun toDto(
        id: RecordId,
        seq: Long,
        by: String?,
    ): RecordDto {
        require(RecordId.fromWire(coll, id.toWire()) == id) { "ID does not belong to collection" }
        return RecordDto(
            coll.wire,
            id.toWire(),
            seq,
            by,
            fields.mapValues { (_, state) -> FieldValue(state.v, state.c?.toString(), state.reset.takeIf { it }) } +
                growSets.mapValues { (_, entries) -> FieldValue(add = entries.sorted()) },
            match,
            mergedInto?.toWire(),
            matchClocks.mapValues { it.value.toString() },
        )
    }
}

enum class ChangeStatus(
    val wire: String,
) {
    APPLIED("applied"),
    STALE("stale"),
    MERGED("merged"),
    REJECTED("rejected"),
}

data class MergeResult(
    val record: RecordState,
    val advanced: Set<String>,
    val lost: Set<String>,
) {
    val status: ChangeStatus get() =
        when {
            record.mergedInto != null -> ChangeStatus.MERGED
            advanced.isEmpty() && lost.isNotEmpty() -> ChangeStatus.STALE
            else -> ChangeStatus.APPLIED
        }
}
