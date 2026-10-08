// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `Hlc`/`NodeId`/`HlcClock` per [10 Hybrid logical clocks](10-sync.md): packing, the wire form's
 * string order, tick, receive and the offset-correction clamp. The re-stamp of pending outbox rows
 * after a correction is MS0's `SyncOutboxDao.restampAbove` and is tested there.
 */
class HlcTest {
    private val node = NodeId("9f86d081884c7d65")

    @Test
    fun packingPutsMillisAboveTheCounter() {
        val hlc = Hlc.of(millis = 0x0123_4567_89ABL, counter = 3, nodeId = node)
        assertEquals(0x0123_4567_89ABL, hlc.millis)
        assertEquals(3L, hlc.counter)
        assertEquals((0x0123_4567_89ABL shl 16) or 3, hlc.packed)
    }

    @Test
    fun wireFormRoundTrips() {
        // The documented example: 2026-10-05T15:00:00Z with counter 3.
        val millis = 1_791_212_400_000L
        val hlc = Hlc.of(millis, 3, node)
        val wire = hlc.toString()
        assertEquals("01a10c942d800003-9f86d081884c7d65", wire)
        assertEquals(hlc, Hlc.parse(wire))
    }

    @Test
    fun wireStringOrderEqualsClockOrderWithNodeTieBreak() {
        val a = Hlc.of(100, 1, node)
        val b = Hlc.of(100, 2, node)
        val c = Hlc.of(101, 0, node)
        val sameClockOtherNode = Hlc.of(100, 1, NodeId("aaaaaaaaaaaaaaaa"))
        val ordered = listOf(b, c, a, sameClockOtherNode).sorted()
        assertEquals(listOf(a, sameClockOtherNode, b, c), ordered)
        assertEquals(ordered.map { it.toString() }, ordered.map { it.toString() }.sorted())
    }

    @Test
    fun parseRejectsMalformedWire() {
        assertFailsWith<IllegalArgumentException> { Hlc.parse("01a10c942d800003") }
        assertFailsWith<IllegalArgumentException> { Hlc.parse("ZZZZ0c942d800003-9f86d081884c7d65") }
        assertFailsWith<IllegalArgumentException> { Hlc.parse("01a10c942d800003-UPPERD081884c7d65") }
    }

    @Test
    fun tickAdvancesPastWallAndLastClock() {
        var wall = 1_000L
        var offset = 0L
        val clock = HlcClock(node, wallMs = { wall }, clockOffsetMs = { offset })

        val first = clock.tick()
        assertEquals(1_000L shl 16, first.packed)

        // Wall still → packed + 1 (the counter carries).
        val second = clock.tick()
        assertEquals(first.packed + 1, second.packed)

        // A later wall resets the counter.
        wall = 5_000L
        assertEquals(5_000L shl 16, clock.tick().packed)

        // The offset shifts the wall input.
        offset = 7_000L
        assertEquals(12_000L shl 16, clock.tick().packed)
    }

    @Test
    fun tickCounterOverflowCarriesIntoMillis() {
        val clock = HlcClock(node, wallMs = { 0L }, packed = (10L shl 16) or Hlc.COUNTER_MASK)
        val next = clock.tick()
        // (10 << 16) | 0xFFFF + 1 = 11 << 16: the overflow carries into the milliseconds.
        assertEquals(11L shl 16, next.packed)
    }

    @Test
    fun receiveRaisesTheClock() {
        val clock = HlcClock(node, wallMs = { 0L }, packed = 100)
        clock.receive(Hlc.of(0, 5, node))
        assertEquals(100, clock.packed)
        clock.receive(Hlc.of(9, 0, node))
        assertEquals(9L shl 16, clock.packed)
    }

    @Test
    fun clampOnlyLowersAClockAhead() {
        val clock = HlcClock(node, wallMs = { 0L }, packed = 1_000L)
        clock.clamp(2_000L)
        assertEquals(1_000L, clock.packed)
        clock.clamp(500L)
        assertEquals(500L, clock.packed)
    }

    @Test
    fun nodeIdValidatesHexShape() {
        assertEquals("9f86d081884c7d65", node.value)
        assertEquals("0000000000000000", NodeId.SERVER.value)
        assertFailsWith<IllegalArgumentException> { NodeId("too-short") }
        assertFailsWith<IllegalArgumentException> { NodeId("9f86d081884c7d6Z") }
        // random() produces 16 lowercase hex digits, twice different.
        val one = NodeId.random()
        val two = NodeId.random()
        assertTrue(one.value.matches(Regex("[0-9a-f]{16}")))
        assertTrue(one != two)
    }
}
