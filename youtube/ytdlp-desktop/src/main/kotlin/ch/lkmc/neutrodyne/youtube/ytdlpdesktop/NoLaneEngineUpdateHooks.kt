// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateTrigger

/**
 * The desktop has no engine-update lane in this slice (11: the updater ships with packaging).
 * `YtDlpEngine.checkForUpdate` still answers — as the documented ENGINE_UNAVAILABLE — and
 * reset-to-bundled still resets the store record so a future lane starts clean.
 */
class NoLaneEngineUpdateHooks(
    private val store: EngineStore,
) : EngineUpdateHooks {
    override suspend fun runUpdate(trigger: EngineUpdateTrigger): EngineUpdateOutcome =
        EngineUpdateOutcome.Failed(TransientKind.ENGINE_UNAVAILABLE)

    override suspend fun resetToBundled() = store.resetToBundled()

    override fun requestImmediateCheck() = Unit
}
