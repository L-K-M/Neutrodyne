// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlin.time.Instant

/** Root/session changes poke this lane; quit cancels the common mover without clearing its target. */
internal class DesktopMoveLane(
    private val settings: SettingStore,
    private val move: suspend (String) -> Unit,
    private val startup: suspend () -> Unit = {},
) : JobLane {
    constructor(settings: SettingStore, mover: DownloadMover) : this(settings, { mover.run(it) })

    override val name = NAME

    override suspend fun run(now: Instant) {
        startup()
        val target = settings.get(DownloadSettingKeys.MOVE_TARGET)
        if (target.isNotEmpty()) move(target)
    }

    companion object {
        const val NAME = "downloads-move"
    }
}
