// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * The `nd_power_keep_awake` shim of :playback:native (11 Power): Windows
 * `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`, macOS
 * `IOPMAssertionCreateWithName(kIOPMAssertionTypeNoIdleSleep)`. Linux has no
 * native shim and keeps the portal path, so [open] is never called there.
 *
 * The native side is idempotent; [keepAwake] only throws when the OS call
 * itself fails, which the inhibitor maps to its documented "cannot be taken"
 * degradation.
 */
class NativePower private constructor(
    private val library: NdmediaLibrary,
) : AutoCloseable {
    private val keepAwake: MethodHandle =
        library.downcall(
            "nd_power_keep_awake",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )

    fun keepAwake(on: Boolean) {
        val result = keepAwake.invokeExact(if (on) 1 else 0) as Int
        check(result >= 0) { "nd_power_keep_awake failed: $result" }
    }

    override fun close() = library.close()

    companion object {
        fun open(directory: Path): NativePower {
            val library = NdmediaLibrary.load(directory)
            try {
                return NativePower(library)
            } catch (failure: Throwable) {
                library.close()
                throw failure
            }
        }
    }
}
