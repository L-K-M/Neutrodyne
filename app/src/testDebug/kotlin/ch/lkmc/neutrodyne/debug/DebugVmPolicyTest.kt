// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.debug

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugVmPolicyTest {
    private val leakClass = "android.os.strictmode.LeakedClosableViolation"
    private val message =
        "A resource was acquired at attached stack trace but never released. " +
            "See java.io.Closeable for information on avoiding resource leaks. " +
            "Callsite: InsetsSourceControl"
    private val frames =
        arrayOf(
            StackTraceElement("dalvik.system.CloseGuard", "warnIfOpen", "CloseGuard.java", 338),
            StackTraceElement("android.view.SurfaceControl", "finalize", "SurfaceControl.java", 1466),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1012),
        )

    @Test fun recognizesTheCapturedAndroid14FrameworkLeak() {
        assertTrue(isFrameworkInsetsLeak(34, leakClass, message, frames))
    }

    @Test fun otherAndroidVersionsKeepFatalLeakHandling() {
        for (api in listOf(26, 33, 35, 36, 37)) {
            assertFalse(isFrameworkInsetsLeak(api, leakClass, message, frames), "API $api")
        }
    }

    @Test fun otherViolationTypesKeepFatalHandling() {
        for (type in listOf("android.os.strictmode.SqliteObjectLeakedViolation", "java.lang.Throwable")) {
            assertFalse(isFrameworkInsetsLeak(34, type, message, frames), type)
        }
    }

    @Test fun anApplicationCloseableWithTheSameMessageKeepsFatalHandling() {
        val ownFrames =
            arrayOf(StackTraceElement("ch.lkmc.neutrodyne.SomeRepository", "open", "SomeRepository.kt", 20))
        assertFalse(isFrameworkInsetsLeak(34, leakClass, message, ownFrames))
        assertFalse(isFrameworkInsetsLeak(34, leakClass, message, frames + ownFrames))
    }

    @Test fun anotherFrameworkResourceKeepsFatalHandling() {
        assertFalse(
            isFrameworkInsetsLeak(34, leakClass, message.replace("InsetsSourceControl", "FileInputStream"), frames),
        )
        assertFalse(isFrameworkInsetsLeak(34, leakClass, null, frames))
    }

    @Test fun aDifferentSurfaceControlMethodKeepsFatalHandling() {
        val otherFrames =
            arrayOf(StackTraceElement("android.view.SurfaceControl", "release", "SurfaceControl.java", 1470))
        assertFalse(isFrameworkInsetsLeak(34, leakClass, message, otherFrames))
    }
}
