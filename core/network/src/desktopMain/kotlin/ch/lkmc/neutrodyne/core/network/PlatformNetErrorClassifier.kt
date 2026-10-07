// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.okhttp.JvmNetErrors
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Desktop `NetErrorClassifier` (01 Network error taxonomy): `JvmNetErrors` with the monitor's
 * current `isConnected` as the offline discriminator — and an `Offline`, `DnsFailure` or
 * `ConnectionFailed` result triggers the monitor's immediate re-check, so the next request sees
 * fresh state instead of waiting out the 15 s poll.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class PlatformNetErrorClassifier
    @Inject
    constructor(
        private val monitor: DesktopNetworkMonitor,
    ) : NetErrorClassifier {
        override fun classify(e: Throwable): NetError {
            val error = JvmNetErrors.classify(e, monitor.status.value.isConnected)
            when (error) {
                NetError.Offline, NetError.DnsFailure, NetError.ConnectionFailed -> monitor.recheck()
                else -> Unit
            }
            return error
        }
    }
