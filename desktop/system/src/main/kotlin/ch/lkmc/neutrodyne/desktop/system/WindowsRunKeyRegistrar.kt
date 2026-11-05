// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.LoginItemState

/**
 * 11's Windows start at login: the `Neutrodyne` value `"<launcher>" --background` under
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, the registry itself behind
 * [WindowsRegistry] so the logic is unit-testable off Windows.
 *
 * Task Manager can disable the entry without removing it; that state is read from
 * `StartupApproved`'s binary value — first byte `02` enabled, `03` disabled (undocumented,
 * 11 marks it Unverified). Reading status without that value falls back to plain
 * REGISTERED/NOT_REGISTERED.
 */
class WindowsRunKeyRegistrar(
    private val registry: WindowsRegistry,
    private val launcher: String?,
) : LoginItemRegistrar {
    override fun status(): LoginItemState {
        if (registry.readString(RUN_KEY, VALUE_NAME) != null) {
            val approved = registry.readBinary(APPROVED_KEY, VALUE_NAME)
            if (approved != null && approved.isNotEmpty() && approved[0] == DISABLED_MARKER) {
                return LoginItemState.DISABLED_BY_OS
            }
            return LoginItemState.REGISTERED
        }
        return if (launcher == null) LoginItemState.UNSUPPORTED else LoginItemState.NOT_REGISTERED
    }

    override fun register() {
        val path = launcher ?: throw LoginItemException("no launcher path (dev run without jpackage.app-path)")
        registry.writeString(RUN_KEY, VALUE_NAME, "\"$path\" $BACKGROUND_FLAG")
    }

    override fun unregister() {
        registry.deleteValue(RUN_KEY, VALUE_NAME)
    }

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
        const val VALUE_NAME = "Neutrodyne"
        const val BACKGROUND_FLAG = "--background"
        const val DISABLED_MARKER: Byte = 0x03
    }
}
