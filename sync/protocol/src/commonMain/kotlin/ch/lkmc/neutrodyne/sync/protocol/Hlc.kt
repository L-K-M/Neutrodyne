// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.protocol

import kotlin.random.Random

/**
 * A device's node identity ([10 Hybrid logical clocks](10-sync.md)): 16 random lowercase hex
 * digits, generated at each link. The server writes on [SERVER] (`0000000000000000`).
 */
@JvmInline
value class NodeId(
    val value: String,
) {
    init {
        require(value.length == LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "node ID is 16 lowercase hex digits: $value"
        }
    }

    override fun toString(): String = value

    companion object {
        const val LENGTH = 16
        val SERVER = NodeId("0000000000000000")

        /** Random tie-break identity; `Random.Default` is not cryptographically secure. */
        fun random(): NodeId {
            val bytes = ByteArray(LENGTH / 2)
            Random.nextBytes(bytes)
            return NodeId(bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') })
        }
    }
}

/**
 * Hybrid logical clock value ([10 Hybrid logical clocks](10-sync.md), after Kulkarni et al.):
 * [packed] = `(millis << 16) | counter` as a signed 64-bit value (valid until the year 6429) plus
 * the [nodeId] whose clock produced it.
 *
 * The wire form is `HHHHHHHHHHHHCCCC-NNNNNNNNNNNNNNNN` (16 lowercase hex digits of the packed
 * value, `-`, the node ID), so byte-wise string order equals clock order with the node ID as
 * tie-break — [compareTo] implements the same order.
 */
data class Hlc(
    val packed: Long,
    val nodeId: NodeId,
) : Comparable<Hlc> {
    init {
        require(packed >= 0) { "packed clock out of range: $packed" }
    }

    val millis: Long get() = packed ushr COUNTER_BITS
    val counter: Long get() = packed and COUNTER_MASK

    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, { it.packed }, { it.nodeId.value })

    /** The wire form; [parse] reads it back. */
    override fun toString(): String = "${packed.toString(16).padStart(PACKED_HEX, '0')}-$nodeId"

    companion object {
        const val COUNTER_BITS = 16
        const val COUNTER_MASK = (1L shl COUNTER_BITS) - 1
        const val MAX_MILLIS = Long.MAX_VALUE ushr COUNTER_BITS
        private const val PACKED_HEX = 16
        private const val WIRE_LENGTH = PACKED_HEX + 1 + NodeId.LENGTH

        fun of(
            millis: Long,
            counter: Long,
            nodeId: NodeId,
        ): Hlc {
            require(millis in 0..MAX_MILLIS) { "millis out of range: $millis" }
            require(counter in 0..COUNTER_MASK) { "counter out of range: $counter" }
            return Hlc((millis shl COUNTER_BITS) or counter, nodeId)
        }

        fun parse(wire: String): Hlc {
            require(wire.length == WIRE_LENGTH && wire[PACKED_HEX] == '-') {
                "invalid HLC wire form: $wire"
            }
            val packedHex = wire.substring(0, PACKED_HEX)
            require(packedHex.all { it in '0'..'9' || it in 'a'..'f' }) {
                "invalid HLC wire form: $wire"
            }
            val packed = packedHex.toLongOrNull(16)
            require(packed != null && packed >= 0) { "invalid HLC wire form: $wire" }
            return Hlc(packed, NodeId(wire.substring(PACKED_HEX + 1)))
        }
    }
}

/**
 * One device's clock ([10 Hybrid logical clocks](10-sync.md)). The state lives in `sync_state`
 * (`hlc`, `nodeId`, `clockOffsetMs`); the caller persists [packed] after every [tick], [receive]
 * and [clamp], with the corresponding capture/apply/restamp transaction, and restores it on start.
 * Confine each instance to one owner or serialize its mutators; they are not thread-safe.
 *
 * `wallMs` and `clockOffsetMs` are read on every tick so a clock-offset correction ([10 Offset
 * correction](10-sync.md#hybrid-logical-clocks)) takes effect without rebuilding the clock.
 */
class HlcClock(
    val nodeId: NodeId,
    private val wallMs: () -> Long,
    private val clockOffsetMs: () -> Long = { 0L },
    packed: Long = 0L,
) {
    var packed: Long = packed
        private set

    /** `hlc = max(hlc + 1, (wallMs + clockOffsetMs) << 16)`; a counter overflow carries into ms. */
    fun tick(): Hlc {
        packed = maxOf(packed + 1, (wallMs() + clockOffsetMs()) shl Hlc.COUNTER_BITS)
        return Hlc(packed, nodeId)
    }

    /** The receive rule: this clock rises to the largest packed clock it has received. */
    fun receive(remote: Hlc) {
        if (remote.packed > packed) packed = remote.packed
    }

    /**
     * Offset-correction clamp: a persisted clock ahead of the adjusted wall clock is lowered.
     * Correction re-stamps pending LOCAL rows; older accepted stamps can win LWW until wall time catches up.
     */
    fun clamp(maxPacked: Long) {
        if (packed > maxPacked) packed = maxPacked
    }
}
