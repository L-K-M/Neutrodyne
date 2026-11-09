// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

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

    suspend fun post(notice: SyncNotice) = _notices.emit(notice)
}
