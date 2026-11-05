// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.model.LoginItemState
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `WindowsRunKeyRegistrar` against a map-backed registry (11 Start at login's Windows row):
 * the Run value plus `--background`, and `StartupApproved`'s first byte `03` surfacing
 * "disabled in Task Manager".
 */
class WindowsRunKeyRegistrarTest {
    private val launcher = """C:\Apps\Neutrodyne\Neutrodyne.exe"""
    private val registry = MapWindowsRegistry()

    private fun registrar(launcher: String? = this.launcher) = WindowsRunKeyRegistrar(registry, launcher)

    @Test
    fun `no Run value reads not registered`() {
        assertThat(registrar().status()).isEqualTo(LoginItemState.NOT_REGISTERED)
    }

    @Test
    fun `no Run value and no launcher reads unsupported`() {
        assertThat(registrar(launcher = null).status()).isEqualTo(LoginItemState.UNSUPPORTED)
    }

    @Test
    fun `a Run value reads registered`() {
        registry.writeString(RUN_KEY, "Neutrodyne", "\"$launcher\" --background")
        assertThat(registrar().status()).isEqualTo(LoginItemState.REGISTERED)
    }

    @Test
    fun `StartupApproved byte 03 reports the OS-disabled state`() {
        registry.writeString(RUN_KEY, "Neutrodyne", "\"$launcher\" --background")
        registry.binary[APPROVED_KEY to "Neutrodyne"] = byteArrayOf(0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertThat(registrar().status()).isEqualTo(LoginItemState.DISABLED_BY_OS)

        registry.binary[APPROVED_KEY to "Neutrodyne"] = byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertThat(registrar().status()).isEqualTo(LoginItemState.REGISTERED)
    }

    @Test
    fun `register writes the quoted launcher with the background flag`() {
        registrar().register()
        assertThat(registry.readString(RUN_KEY, "Neutrodyne")).isEqualTo("\"$launcher\" --background")
    }

    @Test
    fun `register without a launcher fails`() {
        assertFailsWith<LoginItemException> { registrar(launcher = null).register() }
    }

    @Test
    fun `unregister removes only the Run value`() {
        registrar().register()
        registrar().unregister()
        assertThat(registry.readString(RUN_KEY, "Neutrodyne")).isNull()
    }

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
    }

    private class MapWindowsRegistry : WindowsRegistry {
        val strings = mutableMapOf<Pair<String, String>, String>()
        val binary = mutableMapOf<Pair<String, String>, ByteArray>()

        override fun readString(
            keyPath: String,
            valueName: String,
        ): String? = strings[keyPath to valueName]

        override fun readBinary(
            keyPath: String,
            valueName: String,
        ): ByteArray? = binary[keyPath to valueName]

        override fun writeString(
            keyPath: String,
            valueName: String,
            value: String,
        ) {
            strings[keyPath to valueName] = value
        }

        override fun deleteValue(
            keyPath: String,
            valueName: String,
        ): Boolean = strings.remove(keyPath to valueName) != null
    }
}
