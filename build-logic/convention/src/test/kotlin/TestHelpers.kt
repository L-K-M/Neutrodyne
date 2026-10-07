// SPDX-License-Identifier: Unlicense
import org.junit.Assert.fail

/** JUnit-4 stand-in for kotlin.test's `assertFailsWith` (build-logic tests run on plain JUnit 4). */
internal inline fun <reified E : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
        fail("expected ${E::class.simpleName} to be thrown")
    } catch (expected: Throwable) {
        if (expected !is E) throw expected
    }
}
