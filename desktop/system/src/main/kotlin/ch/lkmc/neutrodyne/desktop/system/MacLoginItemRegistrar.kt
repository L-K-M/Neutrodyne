// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.LoginItemState
import ch.lkmc.neutrodyne.playback.native.NativeLoginItem
import java.nio.file.Path

/**
 * 11 Start at login's macOS registrar: `SMAppService.mainApp` through the Objective-C
 * shim. [status] maps the SMAppServiceStatus enum, [register] and [unregister] the
 * register/unregister calls; a negative native result becomes a [LoginItemException]
 * the Settings row reports.
 *
 * The registered item is the bundle, so a translocated first start must not register:
 * while the launcher path contains `/AppTranslocation/`, [status] reports
 * [LoginItemState.TRANSLOCATED] and [register] refuses with the move hint (11 Start at
 * login).
 *
 * [MacLoginItemNative] is the seam the deterministic tests use; the production
 * constructor opens the native library.
 */
class MacLoginItemRegistrar internal constructor(
    private val native: MacLoginItemNative,
    private val launcher: String?,
) : LoginItemRegistrar {
    constructor(
        directory: Path,
        launcher: String?,
    ) : this(NativeMacLoginItem(NativeLoginItem.open(directory)), launcher)

    override fun status(): LoginItemState {
        if (launcher == null) return LoginItemState.UNSUPPORTED
        if (isTranslocated()) return LoginItemState.TRANSLOCATED
        return when (val result = native.call(NativeLoginItem.STATUS)) {
            NativeLoginItem.STATUS_NOT_REGISTERED -> LoginItemState.NOT_REGISTERED
            NativeLoginItem.STATUS_ENABLED -> LoginItemState.REGISTERED
            NativeLoginItem.STATUS_REQUIRES_APPROVAL -> LoginItemState.REQUIRES_APPROVAL
            NativeLoginItem.STATUS_NOT_FOUND -> LoginItemState.UNSUPPORTED
            else -> throw LoginItemException("SMAppService status failed: $result")
        }
    }

    override fun register() {
        if (launcher == null) {
            throw LoginItemException("no launcher path (dev run without jpackage.app-path)")
        }
        if (isTranslocated()) throw LoginItemException("move Neutrodyne to Applications first")
        val result = native.call(NativeLoginItem.REGISTER)
        if (result < 0) throw LoginItemException("SMAppService register failed: $result")
    }

    override fun unregister() {
        if (launcher == null || isTranslocated()) return
        val result = native.call(NativeLoginItem.UNREGISTER)
        if (result < 0) throw LoginItemException("SMAppService unregister failed: $result")
    }

    private fun isTranslocated(): Boolean = launcher?.contains(APP_TRANSLOCATION) == true

    private companion object {
        const val APP_TRANSLOCATION = "/AppTranslocation/"
    }
}

/** The native SMAppService calls behind a seam for the deterministic tests. */
internal fun interface MacLoginItemNative {
    fun call(op: Int): Int
}

private class NativeMacLoginItem(
    private val item: NativeLoginItem,
) : MacLoginItemNative {
    override fun call(op: Int): Int = item.call(op)
}
