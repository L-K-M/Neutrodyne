// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.impl.MassChangeGuard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `apply-held` phase: approve exactly one expected held batch through the public
 * `SyncController.resolveHeld` APPLY path, then wait for the row to resolve.
 *
 * The host proves the batch is HELD first. This helper refuses anything else: a missing or foreign
 * ownership marker is the runner's gate, and here a wrong count, a malformed summary, a batch that
 * names podcasts outside the fixture or no longer subscribed, or a non-HELD payload fails before
 * `resolveHeld` is called. No row is inserted and no merger logic runs in this helper.
 */
internal class CrossDeviceHeldApplier(
    private val controller: SyncController,
    private val db: NeutrodyneDatabase,
    private val fixture: CrossDeviceFixture,
    private val expectedPodcastCount: Int,
    private val timeoutMs: Long,
    private val pollMs: Long,
    private val nanoTime: () -> Long,
    private val delay: suspend (Long) -> Unit,
) {
    suspend fun apply(): JsonObject {
        if (expectedPodcastCount <= 0) {
            throw PhaseError("apply-held", "expectedHeldPodcastCount must be positive")
        }
        if (!awaitLinkedStatus(controller.status, timeoutMs, pollMs, nanoTime, delay)) {
            throw PhaseError("apply-held", "device is not linked")
        }
        val idleDeadline = nanoTime() + timeoutMs * 1_000_000
        while (controller.syncing.value) {
            if (nanoTime() >= idleDeadline) {
                throw PhaseError("apply-held", "a prior sync round is still running")
            }
            delay(pollMs)
        }

        val rows = db.syncHeldDao().all()
        if (rows.size != 1) {
            throw PhaseError("apply-held", "expected exactly one held batch, found ${rows.size}")
        }
        val row = rows.single()
        val batch =
            MassChangeGuard.heldBatch(row.batch)
                ?: throw PhaseError("apply-held", "held batch is malformed or not HELD")
        if (batch.podcasts.size != expectedPodcastCount) {
            throw PhaseError(
                "apply-held",
                "expected $expectedPodcastCount held podcasts, found ${batch.podcasts.size}",
            )
        }
        if (batch.groups.isNotEmpty()) {
            throw PhaseError("apply-held", "expected no held groups, found ${batch.groups.size}")
        }
        val rids = batch.podcasts.map { it.rid }
        val summary =
            parseSummary(row.summary) ?: throw PhaseError("apply-held", "held summary is malformed")
        if (summary.podcasts.size != expectedPodcastCount) {
            throw PhaseError(
                "apply-held",
                "held summary lists ${summary.podcasts.size} podcasts, expected $expectedPodcastCount",
            )
        }
        if (summary.groups.isNotEmpty()) {
            throw PhaseError(
                "apply-held",
                "held summary lists ${summary.groups.size} groups, expected none",
            )
        }
        if (rids.toSet().size != rids.size) {
            throw PhaseError("apply-held", "held batch repeats a podcast")
        }
        val fixtureSyncIds = fixture.podcasts.map { it.syncId }.toSet()
        if (rids.any { it !in fixtureSyncIds }) {
            throw PhaseError("apply-held", "held batch names a podcast outside the fixture")
        }
        val podcasts = db.podcastDao().allForExport()
        val subscribed = podcasts.map { it.syncId }.toSet()
        if (rids.any { it !in subscribed }) {
            throw PhaseError("apply-held", "held batch names a podcast that is not subscribed")
        }
        val titlesById = podcasts.associate { it.syncId to (it.customTitle ?: it.title) }
        if (summary.podcasts != rids.map { titlesById[it] }) {
            throw PhaseError("apply-held", "held summary does not match the held podcasts")
        }

        controller.resolveHeld(row.id, HeldResolution.APPLY)
        val deadline = nanoTime() + timeoutMs * 1_000_000
        while (db.syncHeldDao().byId(row.id) != null) {
            if (nanoTime() >= deadline) {
                throw PhaseError("apply-held", "held batch was not resolved within ${timeoutMs}ms")
            }
            delay(pollMs)
        }
        return buildJsonObject {
            put("applied", true)
            put("heldPodcasts", expectedPodcastCount)
            put("counts", counts(db))
        }
    }

    /** The prompt summary's `{podcasts, groups}` titles, or null when malformed. */
    private fun parseSummary(json: String): HeldSummary? =
        runCatching {
            val obj = crossDeviceJson.parseToJsonElement(json).jsonObject
            val podcasts = (obj["podcasts"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return null
            val groups = (obj["groups"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return null
            HeldSummary(podcasts, groups)
        }.getOrNull()

    private data class HeldSummary(
        val podcasts: List<String>,
        val groups: List<String>,
    )
}
