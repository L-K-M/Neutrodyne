// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * The slice of the Windows registry `WindowsRunKeyRegistrar` needs, behind an interface so
 * unit tests exercise the registrar's logic off Windows. Everything here is `HKCU`-scoped —
 * the app never writes outside its per-user keys (11's ownership rule).
 */
interface WindowsRegistry {
    /** The `REG_SZ`/`REG_EXPAND_SZ` value's content, or `null` when absent. */
    fun readString(
        keyPath: String,
        valueName: String,
    ): String?

    /** The `REG_BINARY` value's bytes, or `null` when absent. */
    fun readBinary(
        keyPath: String,
        valueName: String,
    ): ByteArray?

    /** Creates the key when missing and writes a `REG_SZ` value. */
    fun writeString(
        keyPath: String,
        valueName: String,
        value: String,
    )

    /** Deletes the value; `false` when it was absent. */
    fun deleteValue(
        keyPath: String,
        valueName: String,
    ): Boolean
}

/** The JNA `Advapi32Util` implementation; constructed only on Windows. */
object JnaWindowsRegistry : WindowsRegistry {
    override fun readString(
        keyPath: String,
        valueName: String,
    ): String? =
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, keyPath, valueName)) {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, keyPath, valueName)
        } else {
            null
        }

    override fun readBinary(
        keyPath: String,
        valueName: String,
    ): ByteArray? =
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, keyPath, valueName)) {
            Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, keyPath, valueName)
        } else {
            null
        }

    override fun writeString(
        keyPath: String,
        valueName: String,
        value: String,
    ) {
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, keyPath)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, keyPath, valueName, value)
    }

    override fun deleteValue(
        keyPath: String,
        valueName: String,
    ): Boolean {
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, keyPath, valueName)) {
            return false
        }
        Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, keyPath, valueName)
        return true
    }
}
