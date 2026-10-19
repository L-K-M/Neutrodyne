// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import org.junit.Test
import kotlin.test.assertEquals

class DesktopUpdateRuntimeInputsTest {
    @Test fun windowsOnArmKeepsNumericOsVersionSeparateFromPackageIdentity() {
        val host = DesktopPlatformInfo("Windows 11", "10.0", "aarch64")
        assertEquals(UpdateRuntimeTarget.Desktop("10.0"), DesktopUpdateRuntimeInputs("10.0").target)
        assertEquals("Windows 11; arm64", host.userAgentPlatform)
        // The desktop asset selector maps Windows targets to x64; runtime inputs must not guess an asset or parse the UA.
    }

    @Test fun defaultReadsTheJvmOsVersion() {
        assertEquals(UpdateRuntimeTarget.Desktop(System.getProperty("os.version")), DesktopUpdateRuntimeInputs().target)
    }
}
