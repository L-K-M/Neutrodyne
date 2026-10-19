// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.IpFamily

/** Main-process googlevideo family, adapted to DnsFamilyHints by each platform. No IP persisted. */
interface IpFamilyHints {
    fun current(): IpFamily?

    fun set(family: IpFamily?)
}

/** Identity equality, not connectivity flags. Host adapters invoke on every default-network change,
 * including Wi-Fi to Wi-Fi and loss. No platform network handles enter DTOs or persistent state.
 */
class DefaultNetworkReset(
    private val reset: () -> Unit,
) {
    private var initialized = false
    private var identity: Any? = null

    @Synchronized fun changed(newIdentity: Any?) {
        if (initialized && identity == newIdentity) return
        initialized = true
        identity = newIdentity
        reset()
    }
}
