// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class OutcomeTest {
    @Test
    fun `map transforms the success value`() {
        assertEquals(Outcome.Success(6), Outcome.Success(3).map { it * 2 })
    }

    @Test
    fun `map leaves failures untouched`() {
        val failure = Outcome.Failure(7)
        val mapped = failure.map { _: Int -> 0 }
        assertSame(failure, mapped)
    }

    @Test
    fun `getOrNull unwraps Success only`() {
        assertEquals("v", Outcome.Success("v").getOrNull())
        assertNull(Outcome.Failure(IllegalStateException()).getOrNull())
    }

    @Test
    fun `Failure carries the typed error`() {
        val error = IllegalStateException("boom")
        val failure = assertIs<Outcome.Failure<IllegalStateException>>(Outcome.Failure(error))
        assertSame(error, failure.error)
    }
}
