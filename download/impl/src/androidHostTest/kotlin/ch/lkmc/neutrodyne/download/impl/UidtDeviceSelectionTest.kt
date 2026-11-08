// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.internal.runner.TestRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Use the pinned runner to prove that unsupported cases are deselected before execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33, 34, 36])
class UidtDeviceSelectionTest {
    @Test fun uidtRunsOnlyOnSupportedApisAndNotificationFailuresRemainVisible() {
        val request = TestRequestBuilder().addTestClass(FailureFixture::class.java.name).build()
        val result = JUnitCore().run(request)
        val expected =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setOf("uidtFailure", "notificationFailure")
            } else {
                setOf("notificationFailure")
            }
        assertEquals(expected.size, result.runCount)
        assertEquals(0, result.ignoreCount)
        assertEquals(expected.size, result.failureCount)
        assertEquals(expected, result.failures.map { it.description.methodName }.toSet())
        result.failures.forEach { assertEquals("Selected scheduling check still fails", it.message) }
    }

    class FailureFixture {
        @Test
        @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun uidtFailure() = fail("Selected scheduling check still fails")

        @Test fun notificationFailure() = fail("Selected scheduling check still fails")
    }
}
