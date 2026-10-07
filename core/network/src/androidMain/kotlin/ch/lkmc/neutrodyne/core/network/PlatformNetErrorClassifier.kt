// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.okhttp.JvmNetErrors
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android `NetErrorClassifier` (01 Network error taxonomy): `JvmNetErrors` with the monitor's
 * current `isConnected` as the offline discriminator.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class PlatformNetErrorClassifier
    @Inject
    constructor(
        private val monitor: NetworkMonitor,
    ) : NetErrorClassifier {
        override fun classify(e: Throwable): NetError = JvmNetErrors.classify(e, monitor.status.value.isConnected)
    }
