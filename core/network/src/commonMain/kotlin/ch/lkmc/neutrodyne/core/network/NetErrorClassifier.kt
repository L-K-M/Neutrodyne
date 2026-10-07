// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.model.NetError

/**
 * Transport exception → `NetError` (01 Network error taxonomy). Implemented per platform by
 * `PlatformNetErrorClassifier`, which funnels OkHttp/Ktor failures through the island's
 * `JvmNetErrors` and reads connectivity from `NetworkMonitor`.
 */
interface NetErrorClassifier {
    /**
     * Classifies [e] — walk the cause chain. Implementations rethrow `CancellationException`:
     * a cancelled coroutine is control flow, never a network error.
     */
    fun classify(e: Throwable): NetError
}
