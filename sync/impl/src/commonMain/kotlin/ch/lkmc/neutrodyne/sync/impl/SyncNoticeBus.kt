// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The engine-side channel for one-shot [SyncNotice]s (08 Sync notices): the engine and the
 * first-link merge post here; [SyncControllerImpl] merges it into its own notice flow so the
 * shell's snackbar sees pull-side notices like `FeedPasswordNeeded`.
 */
@Inject
@SingleIn(AppScope::class)
class SyncNoticeBus {
    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)
    val notices: Flow<SyncNotice> = _notices

    /**
     * One app-scoped synchronous observer invoked inside [post] before the replay-0 emit —
     * [MarkedPlayedElsewhereTracker] uses it to stamp the notice with the load that is current
     * at post time, which the deferred collector could no longer reconstruct. A StateFlow so
     * the write is safely published to whatever dispatcher calls [post]; the observer must not
     * suspend, block, lock or touch IO.
     */
    internal val preEmit = MutableStateFlow<((SyncNotice) -> Unit)?>(null)

    suspend fun post(notice: SyncNotice) {
        preEmit.value?.invoke(notice)
        _notices.emit(notice)
    }
}
