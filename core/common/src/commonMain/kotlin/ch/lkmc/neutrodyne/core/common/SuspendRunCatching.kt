// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` for suspend code (01 Errors): cancellation is rethrown, every other
 * [Throwable] is captured. Plain `runCatching` in suspend code is banned in favour of this.
 */
suspend inline fun <T> suspendRunCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
