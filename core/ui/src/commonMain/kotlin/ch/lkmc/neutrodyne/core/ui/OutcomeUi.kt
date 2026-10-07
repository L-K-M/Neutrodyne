// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.Outcome

/**
 * 01 "UI converts an outcome once per screen with the `whenOutcome` helper" — folds [Outcome] into
 * the screen's rendering without leaking the sealed type into composables.
 */
public inline fun <T, E, R> Outcome<T, E>.whenOutcome(
    onSuccess: (T) -> R,
    onFailure: (E) -> R,
): R =
    when (this) {
        is Outcome.Success -> onSuccess(value)
        is Outcome.Failure -> onFailure(error)
    }
