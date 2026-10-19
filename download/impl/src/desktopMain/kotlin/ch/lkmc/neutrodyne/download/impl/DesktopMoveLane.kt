// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlin.time.Instant

/** W7 binds this lane and pokes it on root/session changes; quit cancels the common mover. */
internal class DesktopMoveLane(
    private val settings: SettingStore,
    private val mover: DownloadMover,
) : JobLane {
    override val name = NAME

    override suspend fun run(now: Instant) {
        val target = settings.get(DownloadSettingKeys.MOVE_TARGET)
        if (target.isNotEmpty()) mover.run(target)
    }

    companion object {
        const val NAME = "downloads-move"
    }
}
