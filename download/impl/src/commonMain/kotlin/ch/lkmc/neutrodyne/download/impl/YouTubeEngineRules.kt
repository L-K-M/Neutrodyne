// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth

/** Caller holds the existing claimMutex; file journal IO stays outside DAO transactions. */
internal class YouTubeEngineRules(
    private val db: NeutrodyneDatabase,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: YouTubeHealth,
    private val deletes: DeferredDeletes,
    private val clock: Clock,
) {
    suspend fun syncLocked(): Boolean {
        val dao = db.downloadDao()
        val caps = capabilities.capabilities.value
        if (caps.downloads) {
            val cleared = dao.clearYouTubeEngineWaits()
            val gate = health.extractionGate(clock.now()) as? ExtractionGate.Deny
            if (gate != null) dao.markYouTubeGateWaits(clock.now(), minOf(gate.untilMs, clock.now() + 1_800_000))
            return cleared > 0
        }
        if (caps.externalReason != ExternalReason.NOT_IN_THIS_APK) return dao.markYouTubeEngineWaits() > 0
        val rows =
            dao.allRows().filter {
                it.sourceKind == SourceKind.YOUTUBE && it.state != DownloadState.COMPLETED && it.runnerToken == null
            }
        if (rows.isEmpty()) return false
        deletes.journal(rows)
        val changed = dao.failYouTubeRowsWithoutEngine()
        deletes.drain(db.playSessionDao().get()?.currentEpisodeId)
        return changed > 0
    }
}
