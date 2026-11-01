// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.platform

import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidUpdateRuntimeInputsTest {
    @Test fun preferredDeviceAbiIsIndependentOfInstalledApk() {
        val installedApkAbi = "armeabi-v7a"
        val target = AndroidUpdateRuntimeInputs(listOf("arm64-v8a", installedApkAbi), 37).target
        assertEquals(UpdateRuntimeTarget.Android("arm64-v8a", 37), target)
    }

    @Test fun missingPreferredAbiIsNotReplacedWithAnInstalledAbi() {
        assertNull(AndroidUpdateRuntimeInputs(emptyList(), 26).target.preferredAbi)
    }
}
