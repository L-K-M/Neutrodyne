// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.DesktopAssetSelection
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget

internal const val MAX_UPDATE_MANIFEST_BYTES = 64 * 1024
internal const val UPDATE_NOTIFICATION_ID = 4200
internal const val UPDATE_CHANNEL_ID = "updates"
internal const val UPDATE_PERIODIC_WORK = "app-update-check"
internal const val UPDATE_MANUAL_WORK = "app-update-check-now"

internal enum class UpdateInvocation { MANUAL, SCHEDULED }

internal enum class UpdateScheduling { ENABLED, DISABLED }

enum class UpdatePostResult { ACCEPTED, UNAVAILABLE }

internal enum class UpdateStoreError { IO, CORRUPT }

/**
 * One logical manifest GET with redirects, existing API client/BuildInfo URL, no credentials.
 * Bound bytes while streaming, irrespective of Content-Length. 403/429 => RATE_LIMITED,
 * I/O/timeout => NETWORK, other HTTP/size/validation failures => MANIFEST_INVALID.
 * Cancellation propagates. Fetching assets, REST discovery and retries are outside this port.
 */
internal interface UpdateSource {
    suspend fun fetch(): Outcome<UpdateManifest, UpdateCheckError>
}

/**
 * Schema 1, checked Long version arithmetic; validate all recognized optional sections on both
 * platforms. Ignore unknown enums/fields. Validate raw URL segments before normalization, allow
 * only HTTPS github.com effective port 443, rebuild links. Malformed known data fails as a unit.
 */
internal interface UpdateParser {
    fun parse(bytes: ByteArray): Outcome<UpdateManifest, UpdateCheckError>
}

internal interface UpdateAssetSelector {
    fun select(
        manifest: UpdateManifest,
        build: BuildInfo.Desktop,
        runtime: UpdateRuntimeTarget.Desktop,
    ): DesktopAssetSelection
}

/**
 * App-scoped coordinator entry for workers/lanes. One serialized owner of source, cache, settings,
 * skip and notification writes; concurrent invocations coalesce. Switch-off invalidates in-flight
 * results. A manual caller's cancellation cannot leave state stuck in Checking.
 */
internal interface UpdateCheckExecutor {
    /** Load durable state without fetching; gate worker/lane execution on completion. */
    suspend fun initialize()

    suspend fun execute(origin: UpdateInvocation): UpdateCheckState
}

/** Android WorkManager and desktop application-scope manual execution, calling the executor. */
internal interface UpdateCheckRunner {
    suspend fun setAutomaticChecks(mode: UpdateScheduling)

    suspend fun runNow(): UpdateCheckState

    suspend fun cancelManual()
}

/** Atomic last-check.json replacement outside backup; cached manifests revalidated on read. */
internal interface UpdateStore {
    /** Missing file => Success(null); corrupt/truncated data is recoverable, never a fetch trigger. */
    suspend fun read(): Outcome<UpdateStoreSnapshot?, UpdateStoreError>

    suspend fun write(snapshot: UpdateStoreSnapshot): Outcome<Unit, UpdateStoreError>
}

/**
 * Schema-1 cache metadata; initialization persists once. Keep anchor/retry due time here, not in
 * Room or extra preferences. Cache/settings cannot transact: coordinator must reconcile restart.
 */
internal data class UpdateStoreSnapshot(
    val manifest: UpdateManifest?,
    val lastCheckAtMs: Long?,
    val firstStartedAtMs: Long,
    val nextDueAtMs: Long,
    val retryAttempt: Int,
)

/** Inject alongside Clock; choose/persist a due time once, never reroll on every lane tick. */
internal fun interface UpdateJitter {
    fun nextDelayMs(): Long // inclusive 0..3_600_000
}

/**
 * Scheduled-only dispatch. Android returns UNAVAILABLE when permission/channel/minSdk blocks;
 * desktop ACCEPTED means accepted by DesktopNotifier (banner until MD2), not OS delivery proof.
 * Write notified code only on ACCEPTED. Manual success records the code without calling post.
 * Stable IDs replace retries across the unavoidable crash window between dispatch and storage.
 */
interface UpdateNotificationAdapter {
    suspend fun post(info: UpdateInfo): UpdatePostResult

    suspend fun cancel()
}
