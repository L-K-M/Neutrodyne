// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import ch.lkmc.neutrodyne.core.domain.ExitReasonProbe
import ch.lkmc.neutrodyne.core.domain.ProcessExit
import ch.lkmc.neutrodyne.core.domain.ProcessExitReason

internal class AndroidExitReasonProbe(
    private val context: Context,
) : ExitReasonProbe {
    override fun latest(): ProcessExit? {
        // Before 34, app updates also used USER_REQUESTED and UIDT did not exist.
        if (Build.VERSION.SDK_INT < 34) return null

        val exit =
            context
                .getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return null
        val reason =
            if (exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
                ProcessExitReason.USER_REQUESTED
            } else {
                ProcessExitReason.OTHER
            }
        return ProcessExit(reason, exit.timestamp)
    }
}
