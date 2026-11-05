// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The engine-to-controller notice channel (10 Pull and apply step 5: one-shot UI notices are best
 * effort). The engine posts without suspending; [SyncControllerImpl] relays into its `notices`
 * flow for the shells' snackbar collection.
 */
@Inject
@SingleIn(AppScope::class)
class SyncNoticeBus {
    val shared: MutableSharedFlow<SyncNotice> = MutableSharedFlow(extraBufferCapacity = NOTICE_BUFFER)

    fun post(notice: SyncNotice) {
        shared.tryEmit(notice)
    }

    private companion object {
        const val NOTICE_BUFFER = 16
    }
}
