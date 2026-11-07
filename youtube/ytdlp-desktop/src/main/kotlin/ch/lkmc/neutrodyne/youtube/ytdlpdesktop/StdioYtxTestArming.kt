// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import okio.Path

/**
 * Test-only replay arming: when set, the next child is spawned `--replay <dir>` and replays the
 * recorded exchanges instead of reaching YouTube (11 "Stdio protocol", W9 AC1). Production
 * graphs never provide this; tests inject it through the transport's internal constructor.
 */
fun interface StdioYtxTestArming {
    fun replayDir(): Path?
}
