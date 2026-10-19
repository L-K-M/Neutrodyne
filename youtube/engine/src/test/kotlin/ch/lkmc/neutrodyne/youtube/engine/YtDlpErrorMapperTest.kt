// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class YtDlpErrorMapperTest {
    private val clock = TestClock()
    private val errors = YtDlpErrorMapper(clock)

    @Test fun perVideoReasonsRemainUnavailable() {
        for (reason in listOf(
            Availability.AGE_RESTRICTED,
            Availability.MEMBERS_ONLY,
            Availability.PRIVATE,
            Availability.REGION_BLOCKED,
            Availability.UPCOMING,
            Availability.LIVE,
            Availability.KIDS_ONLY,
            Availability.UNAVAILABLE,
        )) {
            assertEquals(ResolveResult.Unavailable(reason), errors.map(reason.name, VIDEO))
        }
    }

    @Test fun transientCodesRemainDistinctAndUnknownNeverPersistsUnavailable() {
        for (kind in listOf(
            TransientKind.NETWORK,
            TransientKind.TIMEOUT,
            TransientKind.ENGINE_UNAVAILABLE,
            TransientKind.RATE_LIMITED,
            TransientKind.EXTRACTION,
        )) {
            assertEquals(kind, assertIs<ResolveResult.Transient>(errors.map(kind.name, VIDEO)).kind)
        }
        val unknown = assertIs<ResolveResult.Transient>(errors.map("https://secret.invalid/token", VIDEO))
        assertEquals(TransientKind.EXTRACTION, unknown.kind)
        assertFalse(unknown.cause.stackTraceToString().contains("secret"))
        assertEquals(
            TransientKind.EXTRACTION,
            assertIs<ResolveResult.Transient>(errors.map("CURSOR_EXPIRED", VIDEO)).kind,
        )
        assertFailsWith<CancellationException> { errors.map("CANCELLED", VIDEO) }
    }

    @Test fun thirdDistinctUnavailableWithinTenMinutesBecomesParseFailure() {
        repeat(4) { assertIs<ResolveResult.Unavailable>(errors.map("UNAVAILABLE", VIDEO)) }
        assertIs<ResolveResult.Unavailable>(errors.map("UNAVAILABLE", OTHER_VIDEO))
        assertEquals(
            TransientKind.EXTRACTION,
            assertIs<ResolveResult.Transient>(errors.map("UNAVAILABLE", THIRD_VIDEO)).kind,
        )
        clock.elapsedMs = 10 * 60000
        assertIs<ResolveResult.Unavailable>(errors.map("UNAVAILABLE", THIRD_VIDEO))
    }

    @Test fun otherFailuresAndClockRollbackCannotCreateUnavailableCluster() {
        errors.map("UNAVAILABLE", VIDEO)
        errors.map("NETWORK", OTHER_VIDEO)
        errors.map("PRIVATE", THIRD_VIDEO)
        assertIs<ResolveResult.Unavailable>(errors.map("UNAVAILABLE", THIRD_VIDEO))
        clock.elapsedMs = -1
        assertIs<ResolveResult.Unavailable>(errors.map("UNAVAILABLE", OTHER_VIDEO))
    }
}
