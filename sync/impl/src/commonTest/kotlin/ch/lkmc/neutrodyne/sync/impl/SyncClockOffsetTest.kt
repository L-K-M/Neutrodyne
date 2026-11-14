// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncClockOffsetTest {
    @Test
    fun aPersistedOffsetCannotOverflowOrEscapeThePackedClockRange() {
        assertEquals(1_700_000_000_000L, adjustedSyncWallMs(1_700_021_600_000L, -21_600_000))
        assertNull(adjustedSyncWallMs(-1, 0))
        assertNull(adjustedSyncWallMs(0, Long.MIN_VALUE))
        assertNull(adjustedSyncWallMs(Long.MAX_VALUE, 1))
        assertNull(adjustedSyncWallMs(0, -1))
        assertNull(adjustedSyncWallMs(Hlc.MAX_MILLIS, 1))
        assertEquals(Hlc.MAX_MILLIS, adjustedSyncWallMs(Hlc.MAX_MILLIS - 1, 1))
    }

    @Test
    fun midpointAndSmallJitterLeaveLocalTimeUnchanged() {
        for (server in listOf(1_700_000_000_500L, 1_700_000_120_500L, 1_699_999_880_500L)) {
            assertEquals(
                SyncClockOffset(0, 1_700_000_001_000L),
                estimateSyncClockOffset(server, 1_700_000_000_000L, 1_700_000_001_000L),
            )
        }
    }

    @Test
    fun correctionStartsStrictlyBeyondTwoMinutesInEitherDirection() {
        assertEquals(
            SyncClockOffset(120_001, 1_700_000_121_001L),
            estimateSyncClockOffset(1_700_000_120_501L, 1_700_000_000_000L, 1_700_000_001_000L),
        )
        assertEquals(
            SyncClockOffset(-120_001, 1_699_999_880_999L),
            estimateSyncClockOffset(1_699_999_880_499L, 1_700_000_000_000L, 1_700_000_001_000L),
        )
    }

    @Test
    fun oddRoundTripUsesTheEarlierWholeMillisecondMidpoint() {
        assertEquals(
            SyncClockOffset(120_001, 1_700_000_121_002L),
            estimateSyncClockOffset(1_700_000_120_501L, 1_700_000_000_000L, 1_700_000_001_001L),
        )
    }

    @Test
    fun aHugeWrongLocalClockCanStillBeCorrectedWithoutOverflow() {
        val result = estimateSyncClockOffset(1_700_000_000_000L, Long.MAX_VALUE - 1_000, Long.MAX_VALUE)
        assertEquals(1_700_000_000_500L, result?.adjustedReceivedAtMs)
        assertEquals(-9_223_370_336_854_775_307L, result?.offsetMs)
    }

    @Test
    fun negativeOrBackwardsLocalSamplesAreRejected() {
        assertNull(estimateSyncClockOffset(1_700_000_000_000L, -1, 0))
        assertNull(estimateSyncClockOffset(1_700_000_000_000L, 10, 9))
        assertNull(estimateSyncClockOffset(1_700_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun serverTimeMustFitTheProtocolAdmissionRange() {
        assertNull(estimateSyncClockOffset(ProtocolLimits.MIN_CLOCK_MS - 1, 0, 0))
        assertNull(estimateSyncClockOffset(Hlc.MAX_MILLIS + 1, 0, 0))
        assertEquals(
            SyncClockOffset(ProtocolLimits.MIN_CLOCK_MS, ProtocolLimits.MIN_CLOCK_MS),
            estimateSyncClockOffset(ProtocolLimits.MIN_CLOCK_MS, 0, 0),
        )
        assertEquals(
            SyncClockOffset(Hlc.MAX_MILLIS, Hlc.MAX_MILLIS),
            estimateSyncClockOffset(Hlc.MAX_MILLIS, 0, 0),
        )
    }

    @Test
    fun adjustedTimeMustRemainPackableEvenForAnUnboundedRoundTrip() {
        assertNull(estimateSyncClockOffset(1_700_000_000_000L, 0, Long.MAX_VALUE))
        assertNull(estimateSyncClockOffset(Hlc.MAX_MILLIS, 0, 2))
    }
}
