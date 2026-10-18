// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowDiffTest {
    private val diff = WindowDiff()

    @Test fun equalAndEmptyWindowsNeedNoOperations() {
        assertTrue(diff.diff(emptyList(), emptyList()).isEmpty())
        val window = (1L..5L).map(::entry)
        assertTrue(diff.diff(window, window).isEmpty())
    }

    @Test fun emptyTargetClearsAndNewCurrentSetsAllWithStartPosition() {
        assertEquals(listOf(WindowOp.Clear), diff.diff(listOf(entry(1)), emptyList()))
        val want = listOf(entry(2), entry(3))
        assertEquals(
            listOf(
                WindowOp.SetAll(
                    want.map {
                        it.item
                    },
                    90_000,
                ),
            ),
            diff.diff(listOf(entry(1)), want, startPositionMs = 90_000),
        )
        assertIs<WindowOp.SetAll>(diff.diff(emptyList(), want).single())
    }

    @Test fun swappingNextPairIsExactlyOneMove() {
        val have = listOf(1L, 2, 3, 4).map(::entry)
        val want = listOf(1L, 3, 2, 4).map(::entry)
        val ops = diff.diff(have, want)
        assertIs<WindowOp.Move>(ops.single())
        assertEquals(want.map { it.item }, apply(have.map { it.item }, ops))
    }

    @Test fun movingNextBehindSeveralPeersIsStillOneMove() {
        val have = (1L..6).map(::entry)
        val want = listOf(1L, 3, 4, 5, 2, 6).map(::entry)
        assertEquals(listOf(WindowOp.Move(1, 4)), diff.diff(have, want))
    }

    @Test fun insertRemoveAndConsumedPrefixUseSequentialIndices() {
        val have = (1L..6).map(::entry)
        val want = listOf(3L, 8, 5, 9).map(::entry)
        val ops = diff.diff(have, want, currentIndex = 2)
        assertEquals(listOf(WindowOp.Remove(0), WindowOp.Remove(0)), ops.take(2))
        assertEquals(want.map { it.item }, apply(have.map { it.item }, ops))
    }

    @Test fun thirtyOperationsAreAllowedAndThirtyOneReplaceOnlyTail() {
        val have = listOf(entry(1))
        assertEquals(30, diff.diff(have, (1L..31).map(::entry)).size)
        val want = (1L..32).map(::entry)
        assertEquals(listOf(WindowOp.ReplaceTail(want.drop(1).map { it.item })), diff.diff(have, want))
    }

    @Test fun metadataChangesUpdateWithoutReloadingCurrentOrTail() {
        val have = listOf(entry(1), entry(2))
        val want = have.map { it.copy(media = it.media!!.copy(title = "Renamed")) }
        assertEquals(
            listOf(WindowOp.UpdateMetadata(0, item(1)), WindowOp.UpdateMetadata(1, item(2))),
            diff.diff(have, want),
        )
    }

    @Test fun sourceChangeReloadsOnlyNonCurrentAndDefersCurrent() {
        val have = listOf(entry(1), entry(2))
        val want = have.map { it.copy(media = it.media!!.copy(enclosureUrl = "https://example.org/new.mp3")) }
        assertEquals(listOf(WindowOp.Reload(1, item(2))), diff.diff(have, want))
    }

    @Test fun newlyLocalTailReloadsAndCurrentKeepsItsPin() {
        val have = listOf(entry(1), entry(2))
        val want = have.map { it.copy(localUri = "file:///audio") }
        assertEquals(listOf(WindowOp.Reload(1, item(2))), diff.diff(have, want))
    }

    @Test fun metadataUpdateOnCurrentSurvivesTailFallback() {
        val have = listOf(entry(1))
        val want = (1L..40).map(::entry).toMutableList()
        want[0] = want[0].copy(media = want[0].media!!.copy(title = "New"))
        val ops = diff.diff(have, want)
        assertEquals(WindowOp.UpdateMetadata(0, item(1)), ops[0])
        assertIs<WindowOp.ReplaceTail>(ops[1])
    }

    @Test fun manyTailPermutationsConvergeWithoutTouchingCurrent() {
        val random = Random(42)
        repeat(500) {
            val have = listOf(entry(1)) + (2L..15).shuffled(random).take(random.nextInt(14)).map(::entry)
            val want = listOf(entry(1)) + (2L..19).shuffled(random).take(random.nextInt(18)).map(::entry)
            val ops = diff.diff(have, want)
            assertEquals(want.map { it.item }, apply(have.map { it.item }, ops), "$have -> $want via $ops")
            assertTrue(ops.none { it is WindowOp.SetAll || it is WindowOp.Clear })
            for (op in ops) {
                when (op) {
                    is WindowOp.Move -> assertTrue(op.fromIndex > 0 && op.toIndex > 0)
                    is WindowOp.Remove -> assertTrue(op.index > 0)
                    is WindowOp.Add -> assertTrue(op.index > 0)
                    else -> Unit
                }
            }
        }
    }
}
