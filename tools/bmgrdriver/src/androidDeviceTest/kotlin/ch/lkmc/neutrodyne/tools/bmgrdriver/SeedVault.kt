// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.content.Context
import java.io.File

/** The driver's `files/bmgr/` vault: the only subtree its FileProvider ever shares. */
internal object SeedVault {
    const val AUTHORITY = "ch.lkmc.neutrodyne.bmgrdriver.fileprovider"

    // Production English strings from the acquisition-error dialog (app R.strings); the
    // negative leg asserts one of them rather than any internal error plumbing.
    const val TEXT_UNSUPPORTED = "This file isn't a supported subscription list or backup."
    const val TEXT_HOSTILE = "This file can't be imported safely."

    fun dir(context: Context): File = File(context.filesDir, "bmgr").apply { mkdirs() }
}
