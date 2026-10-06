// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

/**
 * A snackbar-bound message (01 ViewModels and UI state): [id] de-duplicates and acknowledges it,
 * [text] is the body and [action] the optional action label.
 */
public data class UserMessage(
    val id: Long,
    val text: UiText,
    val action: UiText? = null,
)
