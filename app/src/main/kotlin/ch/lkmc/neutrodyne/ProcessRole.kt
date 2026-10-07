// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Which of the app's processes this is (01 Application start-up, D73). Classified once in
 * `attachBaseContext`, before any graph exists: `ch.lkmc.neutrodyne:ytx` is [YTX], `…:acra` is [ACRA],
 * everything else [MAIN].
 */
internal enum class ProcessRole {
    MAIN,
    YTX,
    ACRA,
    ;

    companion object {
        private const val YTX_SUFFIX = ":ytx"
        private const val ACRA_SUFFIX = ":acra"
        private const val CMDLINE = "/proc/self/cmdline"
        private const val NUL = '\u0000'

        fun current(): ProcessRole = fromProcessName(processName())

        /** Pure classification, unit-tested: `ch.lkmc.neutrodyne.debug:ytx` → [YTX]. */
        fun fromProcessName(name: String): ProcessRole =
            when {
                name.endsWith(YTX_SUFFIX) -> YTX
                name.endsWith(ACRA_SUFFIX) -> ACRA
                else -> MAIN
            }

        /** `Application.getProcessName()` on API 28+; the first NUL-terminated token of the cmdline on 26–27. */
        private fun processName(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
            return File(CMDLINE).readText().substringBefore(NUL).trim()
        }
    }
}
