// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.os.Build
import androidx.test.internal.runner.TestRequestBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 28, 36])
class SupportedPythonDeviceTest {
    @Test
    fun x86OreoIsDeselectedButSupportedX86StillFails() {
        verifySelection("x86_64", expectedExecutions = if (Build.VERSION.SDK_INT < 28) 0 else 1)
    }

    @Test
    fun arm64StillExecutesAndFailsOnEveryApi() {
        verifySelection("arm64-v8a", expectedExecutions = 1)
    }

    private fun verifySelection(
        abi: String,
        expectedExecutions: Int,
    ) {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf(abi))
        val request = TestRequestBuilder().addTestClass(FailureFixture::class.java.name).build()
        val result = JUnitCore().run(request)
        assertThat(result.runCount).isEqualTo(expectedExecutions)
        assertThat(result.ignoreCount).isEqualTo(0)
        assertThat(result.failureCount).isEqualTo(expectedExecutions)
        result.failures.forEach { assertThat(it.message).isEqualTo("selected Python check still fails") }
    }

    class FailureFixture {
        @Test
        @SupportedPythonDevice
        fun selectedFailure() = fail("selected Python check still fails")
    }
}
