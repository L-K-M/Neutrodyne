// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.os.Build
import androidx.test.filters.AbstractFilter
import androidx.test.filters.CustomFilter
import org.junit.runner.Description

/** Deselect the unsupported CPython device before execution; its assumption guard stays in place. */
@CustomFilter(filterClass = SupportedPythonDeviceFilter::class)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
internal annotation class SupportedPythonDevice

// AndroidJUnitRunner instantiates @CustomFilter classes through their public no-arg constructor.
class SupportedPythonDeviceFilter : AbstractFilter() {
    override fun evaluateTest(description: Description): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P || Build.SUPPORTED_ABIS.first() != "x86_64"

    override fun describe(): String = "CPython 3.14 requires API 28+ on x86_64 (Android 8.x seccomp)"
}
