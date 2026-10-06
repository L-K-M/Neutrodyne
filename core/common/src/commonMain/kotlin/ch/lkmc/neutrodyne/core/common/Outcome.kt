// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * The success/failure type every suspend API that can fail returns (01 Errors). Expected
 * failures (network, parse, storage, invalid input) are values of the area's sealed error type
 * [E] — exceptions are for programming errors only. UI converts an outcome once per screen with
 * the `whenOutcome` helper that lives in `:core:ui`.
 */
sealed interface Outcome<out T, out E> {
    data class Success<out T>(
        val value: T,
    ) : Outcome<T, Nothing>

    data class Failure<out E>(
        val error: E,
    ) : Outcome<Nothing, E>
}

/** Maps the success value; failures pass through unchanged. */
inline fun <T, E, R> Outcome<T, E>.map(f: (T) -> R): Outcome<R, E> =
    when (this) {
        is Outcome.Success -> Outcome.Success(f(value))
        is Outcome.Failure -> this
    }

/** The success value or `null`. */
fun <T> Outcome<T, *>.getOrNull(): T? = (this as? Outcome.Success)?.value
