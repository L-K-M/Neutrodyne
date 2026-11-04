// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long

/** Pure merge over admitted writes. Conflicting equal-clock LWW writes violate the write identity contract. */
object RecordMerger {
    fun merge(
        stored: RecordState?,
        incoming: RecordState,
    ): MergeResult {
        ProtocolValidation.state(incoming)
        require(stored == null || stored.coll == incoming.coll) { "collection mismatch" }
        val base = stored ?: RecordState(incoming.coll)
        val fields = base.fields.toMutableMap()
        val sets = base.growSets.toMutableMap()
        val advanced = mutableSetOf<String>()
        val lost = mutableSetOf<String>()

        for ((name, next) in incoming.fields) {
            val prior = fields[name]
            val kind = incoming.coll.fields[name]?.kind ?: FieldKind.LWW
            if (kind == FieldKind.LWW_POSITION && positionMs(next) == 0L && !next.reset) {
                lost += name
                continue
            }
            val merged =
                when (kind) {
                    FieldKind.MIN_FIELD, FieldKind.MAX_FIELD -> extrema(prior, next, kind)
                    else -> lww(prior, next)
                }
            if (merged != prior) {
                fields[name] = merged
                advanced += name
            } else if (merged != next) {
                lost += name
            }
        }
        for ((name, additions) in incoming.growSets) {
            val union = (sets[name].orEmpty() + additions).sorted().take(ProtocolLimits.MAX_GROW_SET).toSet()
            if (union != sets[name].orEmpty()) {
                sets[name] = union
                advanced += name
            }
        }

        if (base.mergedInto != null && incoming.mergedInto != null && base.mergedInto != incoming.mergedInto) {
            invalid("conflicting authoritative redirect")
        }
        val redirect = base.mergedInto ?: incoming.mergedInto
        if (redirect != base.mergedInto) advanced += "mergedInto"

        val hints =
            base.match
                ?.values()
                .orEmpty()
                .toMutableMap()
        val clocks = base.matchClocks.toMutableMap()
        for ((name, value) in incoming.match?.values().orEmpty()) {
            val clock = incoming.matchClocks[name] ?: incoming.maxClock ?: invalid("hint needs provenance")
            val priorClock = clocks[name] ?: base.maxClock
            val prior = hints[name]
            // Hints have no action clock on pushes. A canonical scalar tie handles distinct hints
            // attached to the same record action without making arrival order observable.
            if (prior == null || priorClock == null || clock > priorClock ||
                (clock == priorClock && value.toString() > prior.toString())
            ) {
                hints[name] = value
                clocks[name] = clock
                if (prior != value || priorClock != clock) advanced += "match.$name"
            } else if (name !in clocks) {
                clocks[name] = priorClock
            }
        }
        return MergeResult(
            RecordState(
                incoming.coll,
                fields.toMap(),
                sets.toMap(),
                hintsFrom(hints),
                redirect,
                clocks.toMap(),
            ),
            advanced.toSet(),
            lost.toSet(),
        )
    }

    fun isDead(record: RecordState): Boolean {
        if (record.mergedInto != null) return true
        val tombstone =
            when (record.coll) {
                SyncCollection.PODCAST -> "subscribed" to false
                SyncCollection.GROUP -> "deleted" to true
                SyncCollection.MEMBER, SyncCollection.UPNEXT -> "in" to false
                else -> return false
            }
        return (record.fields[tombstone.first]?.v as? JsonPrimitive)?.booleanOrNull == tombstone.second
    }

    private fun lww(
        prior: FieldState?,
        next: FieldState,
    ): FieldState {
        if (prior == null) return next
        val order = requireNotNull(next.c).compareTo(requireNotNull(prior.c))
        if (order == 0 && prior != next) invalid("conflicting equal-clock write")
        return if (order > 0) next else prior
    }

    private fun extrema(
        prior: FieldState?,
        next: FieldState,
        kind: FieldKind,
    ): FieldState {
        if (prior == null) return next
        val old = (prior.v as? JsonPrimitive)?.takeUnless { it == JsonNull }?.long
        val new = (next.v as? JsonPrimitive)?.takeUnless { it == JsonNull }?.long
        val chooseNew = old == null || (new != null && if (kind == FieldKind.MIN_FIELD) new < old else new > old)
        // The value and newest observed clock are independent: an unadvanced extremum still
        // raises max_hlc and must survive full records, replay, and convergence comparisons.
        return FieldState(if (chooseNew) next.v else prior.v, listOfNotNull(prior.c, next.c).maxOrNull())
    }
}

internal fun positionMs(field: FieldState): Long = ((field.v as JsonObject).getValue("ms") as JsonPrimitive).long
