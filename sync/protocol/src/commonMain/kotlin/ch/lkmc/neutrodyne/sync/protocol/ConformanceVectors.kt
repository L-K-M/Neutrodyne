// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Versioned, language-neutral fixtures. The runner deliberately performs no resource/file access. */
@Serializable
data class ConformanceVector(
    val name: String,
    val collection: String,
    val id: JsonElement,
    val stored: VectorRecord? = null,
    val changes: List<VectorRecord> = emptyList(),
    val expect: VectorExpectation = VectorExpectation(),
    val permutations: Boolean = false,
    val serverTimeMs: Long? = null,
    val order: List<VectorOrderItem> = emptyList(),
    val candidates: List<VectorCandidate> = emptyList(),
    val serverOnly: String? = null,
    val scenario: JsonObject? = null,
    val version: Int = 1,
)

@Serializable
data class VectorRecord(
    val fields: Map<String, FieldValue> = emptyMap(),
    val match: MatchHints? = null,
    val matchClocks: Map<String, String> = emptyMap(),
    val mergedInto: JsonElement? = null,
    val op: String? = null,
    val to: JsonElement? = null,
)

@Serializable
data class VectorExpectation(
    val status: List<String> = emptyList(),
    val fields: Map<String, FieldValue>? = null,
    val match: MatchHints? = null,
    val matchClocks: Map<String, String>? = null,
    val mergedInto: JsonElement? = null,
    val derived: VectorDerived? = null,
    val dead: Boolean? = null,
    val maxClock: String? = null,
    val error: String? = null,
    val order: List<String>? = null,
    val survivor: String? = null,
    val rekeyTo: String? = null,
)

@Serializable
data class VectorDerived(
    val state: EpisodeProgress,
    val positionMs: Long,
    val favourite: Boolean = false,
)

@Serializable
data class VectorOrderItem(
    val id: JsonElement,
    val ok: String,
)

@Serializable
data class VectorCandidate(
    val id: String,
    val at: Long,
)

enum class VectorDisposition { PASSED, DEFERRED_MS1 }

data class VectorRun(
    val name: String,
    val disposition: VectorDisposition,
    val permutationsChecked: Int = 0,
)

object ConformanceVectors {
    fun run(
        text: String,
        merge: (RecordState?, RecordState) -> MergeResult = RecordMerger::merge,
    ): VectorRun {
        val vector = SyncJson.decodeFromJsonElement(ConformanceVector.serializer(), ProtocolCodec.parse(text))
        require(vector.version == 1) { "unsupported vector version" }
        require(vector.name.isNotBlank()) { "unnamed vector" }
        if (vector.serverOnly != null) {
            require(vector.serverOnly.isNotBlank() && vector.scenario != null) { "server vector needs scenario" }
            return VectorRun(vector.name, VectorDisposition.DEFERRED_MS1)
        }
        try {
            val coll = SyncCollection.fromWire(vector.collection)
            val id = RecordId.fromWire(coll, vector.id)
            val stored = vector.stored?.let { read(coll, vector.id, it) }
            var record = stored
            val statuses = mutableListOf<String>()
            for (change in vector.changes) {
                if (change.op != null) {
                    val validated =
                        ProtocolValidation.change(
                            ChangeDto(
                                coll.wire,
                                vector.id,
                                change.op,
                                change.to,
                                change.match,
                                change.fields,
                            ),
                            vector.serverTimeMs,
                        )
                    val rekey = validated as? ValidatedChange.Rekey ?: error("expected rekey")
                    equal(vector.expect.rekeyTo, rekey.to.rid, vector.name)
                    continue
                }
                val incoming = read(coll, vector.id, change)
                vector.serverTimeMs?.let { now ->
                    incoming.fields.values
                        .mapNotNull { it.c }
                        .forEach { ProtocolValidation.admit(it, now) }
                }
                val result = merge(record, incoming)
                record = result.record
                statuses += result.status.wire
            }
            check(vector.expect.error == null) { "${vector.name}: expected rejection ${vector.expect.error}" }
            equal(vector.expect.status, statuses, vector.name)
            record?.let { result ->
                val dto = result.toDto(id, 0, null)
                vector.expect.fields?.let { equal(it, dto.fields, vector.name) }
                vector.expect.match?.let { equal(it, result.match, vector.name) }
                vector.expect.matchClocks?.let { equal(it, dto.matchClocks, vector.name) }
                equal(vector.expect.mergedInto, dto.mergedInto, vector.name)
                vector.expect.dead?.let { equal(it, RecordMerger.isDead(result), vector.name) }
                vector.expect.maxClock?.let { equal(it, result.maxClock?.toString(), vector.name) }
                vector.expect.derived?.let { expected ->
                    val derived = EpisodeStateRules.derive(result.fields)
                    equal(expected, VectorDerived(derived.state, derived.positionMs, derived.favourite), vector.name)
                    equal(derived.state != EpisodeProgress.PLAYED, derived.allowsUpNext, vector.name)
                }
                // Full-record and raw-state serialization must preserve explicit null and all metadata.
                val decoded = SyncJson.decodeFromString<RecordDto>(SyncJson.encodeToString(RecordDto.serializer(), dto))
                equal(result, ProtocolValidation.record(decoded), vector.name)
                equal(
                    result,
                    SyncJson.decodeFromString<RecordState>(SyncJson.encodeToString(RecordState.serializer(), result)),
                    vector.name,
                )
            }
            if (vector.order.isNotEmpty()) {
                val ordered =
                    vector.order
                        .sortedWith { a, b ->
                            ReplicatedOrder.compare(
                                a.ok,
                                RecordId.fromWire(coll, a.id),
                                b.ok,
                                RecordId.fromWire(coll, b.id),
                            )
                        }.map { RecordId.fromWire(coll, it.id).rid }
                equal(vector.expect.order, ordered, vector.name)
            }
            if (vector.candidates.isNotEmpty()) {
                val survivor =
                    when (coll) {
                        SyncCollection.PODCAST -> {
                            vector.candidates
                                .map { PodcastKeyInfo(PodcastId(it.id), it.at) }
                                .reduce(SurvivorRule::podcast)
                                .syncId.value
                        }

                        SyncCollection.GROUP -> {
                            vector.candidates
                                .map { GroupKeyInfo(GroupId(it.id), it.at) }
                                .reduce(SurvivorRule::group)
                                .uuid.value
                        }

                        else -> {
                            error("survivor vector needs podcast/group")
                        }
                    }
                equal(vector.expect.survivor, survivor, vector.name)
            }
            var permutationsChecked = 0
            if (vector.permutations) {
                require(vector.changes.size in 1..MAX_PERMUTATION_CHANGES) { "permutation fixture too large" }
                for (permutation in permutations(vector.changes)) {
                    var replica = stored
                    for (change in permutation) {
                        replica = merge(replica, read(coll, vector.id, change)).record
                    }
                    equal(record, replica, vector.name)
                    permutationsChecked++
                }
            }
            return VectorRun(vector.name, VectorDisposition.PASSED, permutationsChecked)
        } catch (failure: ProtocolException) {
            if (failure.code.wire != vector.expect.error) throw failure
            return VectorRun(vector.name, VectorDisposition.PASSED)
        } catch (failure: IllegalArgumentException) {
            if (vector.expect.error != SyncErrorCode.INVALID.wire) throw failure
            return VectorRun(vector.name, VectorDisposition.PASSED)
        }
    }

    private fun read(
        coll: SyncCollection,
        id: JsonElement,
        record: VectorRecord,
    ): RecordState =
        ProtocolValidation.record(
            RecordDto(
                coll.wire,
                id,
                0,
                null,
                record.fields,
                record.match,
                record.mergedInto,
                record.matchClocks,
            ),
        )

    private fun <T> equal(
        expected: T,
        actual: T,
        name: String,
    ) {
        check(expected == actual) { "$name: expected $expected, got $actual" }
    }

    private fun <T> permutations(values: List<T>): Sequence<List<T>> =
        sequence {
            if (values.isEmpty()) {
                yield(emptyList())
            } else {
                for (index in values.indices) {
                    val rest = values.filterIndexed { at, _ -> at != index }
                    for (tail in permutations(rest)) yield(listOf(values[index]) + tail)
                }
            }
        }

    private const val MAX_PERMUTATION_CHANGES = 6
}
