// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * What produced a `play_session` row (02 `play_session.contextType` + `contextKey`): a scope, not a
 * foreign key, so [EXTERNAL] and [ALL] exist next to scoped contexts.
 */
enum class ContextType { GROUP, PODCAST, ALL, UNGROUPED, DOWNLOADS, EXTERNAL }

/** How the position in an `episode_position` row was produced (02 `episode_position.source`). */
enum class PositionSource { STREAM, DOWNLOAD }
