// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

/**
 * Downcall helpers (11 FFM bindings).
 *
 * `MethodHandle.invoke` is signature-polymorphic, which makes it impossible to
 * call from Kotlin: the compiler needs the exact static signature at every call
 * site, a spread into its arguments is prohibited, and a mismatch fails at run
 * time with `WrongMethodTypeException` instead of at compile time.
 * `invokeWithArguments` takes an ordinary vararg, performs the same as-type
 * conversions the foreign ABI needs and boxes the result. These calls run at
 * packet rate, not at audio-callback rate, so the boxing costs nothing.
 */
internal fun MethodHandle.callInt(args: Array<out Any?>): Int = invokeWithArguments(*args) as Int

internal fun MethodHandle.callVoid(args: Array<out Any?>) {
    invokeWithArguments(*args)
}

internal fun MethodHandle.callAddress(args: Array<out Any?>): MemorySegment =
    invokeWithArguments(*args) as MemorySegment
