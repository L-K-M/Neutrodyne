// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.native.NativePower
import java.nio.file.Path

/**
 * The macOS and Windows [IdleSleepInhibitor]s over `nd_power_keep_awake` (11 Power's
 * "Keep the computer awake while playing" row): macOS takes an `IOPMAssertion`
 * `NoIdleSleep`, Windows calls `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`.
 * Both are idempotent, so [acquire] and [release] map straight onto the native on/off.
 *
 * [PowerAssertion] is the seam the deterministic tests use; the production constructor
 * opens the native library and closes it with the inhibitor.
 */
fun interface PowerAssertion : AutoCloseable {
    fun keepAwake(on: Boolean)

    override fun close() {}
}

class MacIdleSleepInhibitor internal constructor(
    private val assertion: PowerAssertion,
) : IdleSleepInhibitor,
    AutoCloseable {
    constructor(directory: Path) : this(NativePowerAssertion(NativePower.open(directory)))

    override fun acquire(reason: String) = assertion.keepAwake(true)

    override fun release() = assertion.keepAwake(false)

    override fun close() = assertion.close()
}

class WindowsIdleSleepInhibitor internal constructor(
    private val assertion: PowerAssertion,
) : IdleSleepInhibitor,
    AutoCloseable {
    constructor(directory: Path) : this(NativePowerAssertion(NativePower.open(directory)))

    override fun acquire(reason: String) = assertion.keepAwake(true)

    override fun release() = assertion.keepAwake(false)

    override fun close() = assertion.close()
}

private class NativePowerAssertion(
    private val power: NativePower,
) : PowerAssertion {
    override fun keepAwake(on: Boolean) = power.keepAwake(on)

    override fun close() = power.close()
}
