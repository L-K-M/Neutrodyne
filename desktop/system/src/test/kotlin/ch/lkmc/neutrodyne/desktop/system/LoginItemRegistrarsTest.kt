// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.model.DesktopOs
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.test.Test

/** The registrar factory's OS dispatch, including the deferred macOS registrar. */
class LoginItemRegistrarsTest {
    @Test
    fun `windows and linux get their registrars`() {
        assertThat(LoginItemRegistrars.create(DesktopOs.WINDOWS, null, null))
            .isInstanceOf(WindowsRunKeyRegistrar::class.java)
        assertThat(LoginItemRegistrars.create(DesktopOs.LINUX, null, Path.of("/cfg")))
            .isInstanceOf(XdgAutostartRegistrar::class.java)
    }

    @Test
    fun `macos and a missing linux config home get the unsupported registrar`() {
        assertThat(LoginItemRegistrars.create(DesktopOs.MACOS, null, null))
            .isSameInstanceAs(UnsupportedLoginItemRegistrar)
        assertThat(LoginItemRegistrars.create(DesktopOs.LINUX, null, null))
            .isSameInstanceAs(UnsupportedLoginItemRegistrar)
        assertThat(LoginItemRegistrars.create(null, null, null))
            .isSameInstanceAs(UnsupportedLoginItemRegistrar)
    }
}
