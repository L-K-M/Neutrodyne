// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/** Keep cold reads live without blocking other state or flooding failure notices. */
internal fun <T> Flow<T>.retryPlayerRead(
    fallback: T,
    onFailure: () -> Unit,
): Flow<T> =
    flow {
        var failureReported = false
        emitAll(
            this@retryPlayerRead
                .onEach { failureReported = false }
                .retryWhen { _, _ ->
                    if (!failureReported) {
                        onFailure()
                        failureReported = true
                    }
                    emit(fallback)
                    delay(READ_RETRY_DELAY_MS)
                    true
                },
        )
    }

private const val READ_RETRY_DELAY_MS = 1_000L
