// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.work.Data
import androidx.work.workDataOf

/** `import-{sessionId}` work input and the lane's tag (05 §6 step 4, §7). */
internal object ImportWorkData {
    private const val KEY_SESSION_ID = "sessionId"

    const val TAG_IMPORT = "import"
    const val IMPORT_BACKOFF_MS = 30_000L

    fun workName(sessionId: Long) = "import-$sessionId"

    fun dataOf(sessionId: Long): Data = workDataOf(KEY_SESSION_ID to sessionId)

    /** The encoded session, or `null` — a spec without input finishes without a run. */
    fun sessionId(data: Data): Long? = data.getLong(KEY_SESSION_ID, -1L).takeIf { it >= 0L }
}
