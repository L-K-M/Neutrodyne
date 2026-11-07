// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The exactly-once gate of [DbusDesktopPortal.chooseDirectory]'s outcomes: the portal's
 * `Response` signal and the call's error paths race, and a second `resume` on the same
 * `CancellableContinuation` throws (`Already resumed, but proposed with update`). The
 * compare-and-set makes the first caller win and every later one a no-op, so exactly one
 * outcome resumes and the signal match closes once.
 */
internal class OnceOnly {
    private val done = AtomicBoolean(false)

    fun claim(): Boolean = done.compareAndSet(false, true)
}
