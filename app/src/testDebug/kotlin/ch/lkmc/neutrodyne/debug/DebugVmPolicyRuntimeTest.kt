// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.debug

import android.app.Application
import android.os.StrictMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DebugVmPolicyRuntimeTest {
    private fun originalPolicy(): StrictMode.VmPolicy =
        StrictMode.VmPolicy
            .Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()
            .penaltyLog()
            .penaltyDeath()
            .build()

    private fun mask(policy: StrictMode.VmPolicy): Int = ReflectionHelpers.getField(policy, "mask")

    @Test fun developmentModeKeepsTheOriginalFatalPolicy() {
        val policy = debugVmPolicy(DebugVmPolicyMode.DEVELOPMENT)
        assertEquals(mask(originalPolicy()), mask(policy))
        assertNull(ReflectionHelpers.getField<Any?>(policy, "mListener"))
    }

    @Test fun instrumentedAndroid14KeepsEveryDetectorAndUsesTheListener() {
        val original = mask(originalPolicy())
        val death =
            mask(
                StrictMode.VmPolicy
                    .Builder()
                    .penaltyDeath()
                    .build(),
            )
        val policy = debugVmPolicy(DebugVmPolicyMode.INSTRUMENTED_TEST)
        assertEquals(original and death.inv(), mask(policy))
        assertNotNull(ReflectionHelpers.getField<Any?>(policy, "mListener"))
    }

    @Test
    @Config(sdk = [36])
    fun otherInstrumentedVersionsKeepTheOriginalFatalPolicy() {
        val policy = debugVmPolicy(DebugVmPolicyMode.INSTRUMENTED_TEST)
        assertEquals(mask(originalPolicy()), mask(policy))
        assertNull(ReflectionHelpers.getField<Any?>(policy, "mListener"))
    }
}
