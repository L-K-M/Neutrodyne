// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.model.LoginItemState
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `XdgAutostartRegistrar` against a temporary config home (11 Start at login's Linux row):
 * the entry lands at `autostart/ch.lkmc.neutrodyne.desktop`, the OS file is the status, and a
 * refused path writes nothing.
 */
class XdgAutostartRegistrarTest {
    private val launcher = "/home/alex/apps/My Apps/Neutrodyne/bin/Neutrodyne"
    private val configHome = Files.createTempDirectory("xdg-home")
    private val entryFile = configHome.resolve("autostart").resolve("ch.lkmc.neutrodyne.desktop")

    private fun registrar(launcher: String? = this.launcher) =
        XdgAutostartRegistrar(configHome.resolve("autostart"), launcher)

    @Test
    fun `no entry reads not registered`() {
        assertThat(registrar().status()).isEqualTo(LoginItemState.NOT_REGISTERED)
    }

    @Test
    fun `a missing launcher path reports unsupported`() {
        assertThat(registrar(launcher = null).status()).isEqualTo(LoginItemState.UNSUPPORTED)
    }

    @Test
    fun `register writes the autostart entry and status follows it`() {
        registrar().register()

        assertThat(Files.isRegularFile(entryFile)).isTrue()
        val entry = Files.readString(entryFile)
        assertThat(entry).contains("Exec=\"$launcher\" --background")
        assertThat(entry).contains("TryExec=$launcher")
        assertThat(entry).contains("X-GNOME-Autostart-enabled=true")
        assertThat(registrar().status()).isEqualTo(LoginItemState.REGISTERED)
    }

    @Test
    fun `unregister removes the entry`() {
        registrar().register()
        registrar().unregister()
        assertThat(Files.exists(entryFile)).isFalse()
        assertThat(registrar().status()).isEqualTo(LoginItemState.NOT_REGISTERED)
    }

    @Test
    fun `registering an unusable path fails and writes nothing`() {
        assertFailsWith<LoginItemException> {
            registrar(launcher = "/opt/a=b").register()
        }
        assertThat(Files.exists(entryFile)).isFalse()
    }

    @Test
    fun `registering without a launcher fails`() {
        assertFailsWith<LoginItemException> { registrar(launcher = null).register() }
    }
}
