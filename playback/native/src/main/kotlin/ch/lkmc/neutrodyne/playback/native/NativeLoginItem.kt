// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * The `nd_mac_login_item` shim of :playback:native (11 Start at login):
 * `SMAppService.mainApp` through the Objective-C shim. Op 0 reads the
 * SMAppServiceStatus enum, ops 1 and 2 register and unregister; a negative
 * result is an `ND_E_*` code. Only the macOS library exports the symbol.
 */
class NativeLoginItem private constructor(
    private val library: NdmediaLibrary,
) : AutoCloseable {
    private val call: MethodHandle =
        library.downcall(
            "nd_mac_login_item",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )

    fun call(op: Int): Int = call.invokeExact(op) as Int

    override fun close() = library.close()

    companion object {
        const val STATUS = 0
        const val REGISTER = 1
        const val UNREGISTER = 2

        const val STATUS_NOT_REGISTERED = 0
        const val STATUS_ENABLED = 1
        const val STATUS_REQUIRES_APPROVAL = 2
        const val STATUS_NOT_FOUND = 3

        fun open(directory: Path): NativeLoginItem {
            val library = NdmediaLibrary.load(directory)
            try {
                return NativeLoginItem(library)
            } catch (failure: Throwable) {
                library.close()
                throw failure
            }
        }
    }
}
