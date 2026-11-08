// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/**
 * The Windows identity exports of `ndmedia` (11 Start-up step 4, Windows MSI and ZIP):
 * the process `AppUserModelID` and the Start-menu shortcut stamp. Both symbols are
 * optional — an `ndmedia` built before this slice leaves [supported] false and the
 * callers degrade with a logged warning; off Windows the exports simply do not
 * exist, so only Windows code opens this class.
 */
class NativeWindowsIdentity private constructor(
    private val library: NdmediaLibrary,
) : AutoCloseable {
    private val processSet = library.optionalDowncall("nd_win_process_set_aumid", SET_AUMID)
    private val shortcutSet = library.optionalDowncall("nd_win_shortcut_set_aumid", SET_SHORTCUT_AUMID)

    /** Both exports resolved — false on a pre-identity or non-Windows build of ndmedia. */
    val supported: Boolean get() = processSet != null && shortcutSet != null

    /**
     * `SetCurrentProcessExplicitAppUserModelID(aumid)`: `0` applied, `<0` the
     * native failure code (`HRESULT` or `ND_E_*`), [ND_UNSUPPORTED] without the export.
     */
    fun setProcessAumid(aumid: String): Int {
        val handle = processSet ?: return ND_UNSUPPORTED
        return Arena.ofConfined().use { arena ->
            handle.invokeExact(arena.allocateFrom(aumid)) as Int
        }
    }

    /**
     * `System.AppUserModel.ID` on the `.lnk` through `IShellLinkW` + `IPropertyStore`
     * + `IPersistFile::Save`; same result contract as [setProcessAumid].
     */
    fun setShortcutAumid(
        lnkPath: String,
        aumid: String,
    ): Int {
        val handle = shortcutSet ?: return ND_UNSUPPORTED
        return Arena.ofConfined().use { arena ->
            handle.invokeExact(arena.allocateFrom(lnkPath), arena.allocateFrom(aumid)) as Int
        }
    }

    override fun close() = library.close()

    companion object {
        /** Caller's sentinel for "the export is absent" — never returned by the C side. */
        const val ND_UNSUPPORTED = -100

        private val SET_AUMID = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        private val SET_SHORTCUT_AUMID =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)

        /** Loads `ndmedia` from [directory]; throws when the file is absent. */
        fun open(directory: Path): NativeWindowsIdentity = NativeWindowsIdentity(NdmediaLibrary.load(directory))
    }
}
