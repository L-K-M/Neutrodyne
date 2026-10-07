// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

/**
 * `requireDatabase()`'s UI-thread guard (02 Error handling and recovery): the Android main thread
 * and the Swing EDT.
 */
internal expect fun isUiThread(): Boolean
