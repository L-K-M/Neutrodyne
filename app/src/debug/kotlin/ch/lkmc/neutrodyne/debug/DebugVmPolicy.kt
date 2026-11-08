// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.debug

import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executor
import kotlin.system.exitProcess

internal enum class DebugVmPolicyMode { DEVELOPMENT, INSTRUMENTED_TEST }

internal fun debugVmPolicy(mode: DebugVmPolicyMode): StrictMode.VmPolicy {
    val builder =
        StrictMode.VmPolicy
            .Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()
            .penaltyLog()

    if (mode == DebugVmPolicyMode.INSTRUMENTED_TEST && Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Android 14 can orphan an IME animation leash. The app never owns that control.
        // penaltyDeath runs before a VM listener, so this listener preserves fatal handling
        // itself for every violation except the exact captured framework finalizer.
        builder.penaltyListener(Executor { it.run() }) { violation ->
            if (isFrameworkInsetsLeak(
                    Build.VERSION.SDK_INT,
                    violation.javaClass.name,
                    violation.message,
                    violation.stackTrace,
                )
            ) {
                Log.w("StrictMode", "Android 14 instrumented IME leash finalized by the framework", violation)
            } else {
                Log.e("StrictMode", "Fatal VM policy violation", violation)
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    } else {
        builder.penaltyDeath()
    }
    return builder.build()
}

/** Recognizes only the framework-owned allocation captured in API 34's recorded E8 run. */
internal fun isFrameworkInsetsLeak(
    apiLevel: Int,
    violationClass: String,
    message: String?,
    frames: Array<StackTraceElement>,
): Boolean {
    if (apiLevel != Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        violationClass != "android.os.strictmode.LeakedClosableViolation" ||
        message?.endsWith("Callsite: InsetsSourceControl") != true
    ) {
        return false
    }
    return frames.any { it.className == "android.view.SurfaceControl" && it.methodName == "finalize" } &&
        frames.none { it.className.startsWith("ch.lkmc.neutrodyne.") }
}
