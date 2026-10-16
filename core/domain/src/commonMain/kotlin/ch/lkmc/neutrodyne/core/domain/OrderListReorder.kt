// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/** A row's place in an `orderKey`-ordered list: identity plus current key, sorted by (key, id). */
data class OrderedRow(
    val id: Long,
    val orderKey: String,
)

/**
 * Minimal-write reordering of `orderKey` lists (05 Order, 02 "Group and member ordering"): keeps
 * the longest increasing subsequence of the current positions — the longest run of rows already in
 * relative order — and assigns `OrderKeys.between(previous, next)` only to the others, so one drag
 * writes one row. Returns the `id -> new orderKey` writes to apply (empty when the list is already
 * in the requested order), or null when [requestedIds] is not a permutation of [current] — callers
 * then report `GroupError.NotFound` and write nothing.
 *
 * Falls back to `OrderKeys.rewrite(n)` over the whole requested order when a moved row would have
 * to sit between equal neighbour keys (possible after concurrent inserts on two devices, 10 Ordered
 * lists) or a `between` result would exceed [OrderKeys.MAX_LENGTH]; rewrite keys are deterministic.
 */
fun planOrderKeyWrites(
    current: List<OrderedRow>,
    requestedIds: List<Long>,
    orderKeys: OrderKeys,
): Map<Long, String>? {
    val positions = current.withIndex().associate { (index, row) -> row.id to index }
    if (requestedIds.size != current.size || requestedIds.toSet() != positions.keys) return null

    val keep = longestKeptRun(requestedIds.map { positions.getValue(it) })
    val keyOf = current.associate { it.id to it.orderKey }

    val keptPositions = keep.indices.filter { keep[it] }
    val writes = linkedMapOf<Long, String>()
    var lower: String? = null
    var nextKept = 0
    var rewriteAll = false
    for (i in requestedIds.indices) {
        val id = requestedIds[i]
        if (keep[i]) {
            lower = keyOf.getValue(id)
            nextKept++
            continue
        }
        val upper = keptPositions.getOrNull(nextKept)?.let { keyOf.getValue(requestedIds[it]) }
        if (lower != null && upper != null && lower >= upper) {
            rewriteAll = true
            break
        }
        val key = orderKeys.between(lower, upper)
        if (key.length > OrderKeys.MAX_LENGTH) {
            rewriteAll = true
            break
        }
        writes[id] = key
        lower = key
    }

    if (rewriteAll) {
        val fresh = orderKeys.rewrite(current.size)
        writes.clear()
        for ((index, id) in requestedIds.withIndex()) {
            if (fresh[index] != keyOf.getValue(id)) writes[id] = fresh[index]
        }
    }
    return writes
}

/**
 * Indices of [sequence] forming a longest strictly increasing subsequence — the rows whose current
 * order already matches the requested one. Patience sorting with parent reconstruction, O(n log n).
 */
private fun longestKeptRun(sequence: List<Int>): BooleanArray {
    val tails = mutableListOf<Int>()
    val previous = IntArray(sequence.size) { -1 }
    for (i in sequence.indices) {
        var low = 0
        var high = tails.size
        while (low < high) {
            val mid = (low + high) / 2
            if (sequence[tails[mid]] < sequence[i]) low = mid + 1 else high = mid
        }
        if (low == tails.size) tails += i else tails[low] = i
        if (low > 0) previous[i] = tails[low - 1]
    }

    val keep = BooleanArray(sequence.size)
    var i = tails.lastOrNull() ?: return keep
    while (i >= 0) {
        keep[i] = true
        i = previous[i]
    }
    return keep
}
