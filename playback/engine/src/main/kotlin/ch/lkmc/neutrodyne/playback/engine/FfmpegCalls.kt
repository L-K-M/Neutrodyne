// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

/**
 * Downcall helpers (11 FFM bindings). `MethodHandle.invoke` performs the
 * as-type conversions the foreign ABI needs and boxes the result, which is what
 * makes a hand-written handle safe to call from Kotlin: `invokeExact` would need
 * the exact static signature at every call site, and a mismatch there fails at
 * run time with `WrongMethodTypeException` rather than at compile time. These
 * calls run at packet rate, not at audio-callback rate, so the boxing is free.
 */
internal fun MethodHandle.callInt(args: Array<out Any?>): Int = invoke(*args) as Int

internal fun MethodHandle.callVoid(args: Array<out Any?>) {
    invoke(*args)
}

internal fun MethodHandle.callAddress(args: Array<out Any?>): MemorySegment = invoke(*args) as MemorySegment
