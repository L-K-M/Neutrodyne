// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.download.api.DownloadProgressSource
import ch.lkmc.neutrodyne.download.api.LiveProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Explicit snapshots; tests drive time and publish ticks themselves. */
class FakeDownloadProgressSource : DownloadProgressSource {
    val progress = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())

    override fun observe(ids: Set<Long>): Flow<Map<Long, LiveProgress>> {
        val selected = ids.toSet()
        return progress.map { rows -> rows.filterKeys { it in selected } }.distinctUntilChanged()
    }
}
