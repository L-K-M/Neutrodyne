// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain.update

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import kotlinx.coroutines.flow.StateFlow

/** Notify-only API shared by settings, badge and disclosure/help consumers (09 Modules and API). */
interface AppUpdateChecker {
    val state: StateFlow<UpdateCheckState>

    /** Last asynchronous skip write failure; cleared after a successful durable mutation. */
    val writeError: StateFlow<SettingsError?>

    /**
     * Manual check, even with automatic checks off. DEV refuses; requests coalesce and throttle
     * for 60 s. Offline sends/enqueues nothing; no retries; cancellation must leave Checking.
     * Completes within 30 s. Never downloads a package or posts a notification.
     */
    suspend fun checkNow(): UpdateCheckState

    /**
     * Skip only the currently available version. Publish Idle/cancel notification after durable
     * success; on persistence failure retain Available and publish writeError. A stale tap is inert.
     */
    fun skip(versionCode: Long)
}

interface UpdateNotices {
    val pending: StateFlow<UpdateNotice?>

    /** Dialog copy follows the timeline without a UI dependency on the data-layer constants. */
    val verificationPhase: StateFlow<VerificationPhase>
    val writeError: StateFlow<SettingsError?>

    /** Record durably before advancing pending; failure retains the notice and sets writeError. */
    fun dismiss(notice: UpdateNotice)
}

/** Shell reads Build.SUPPORTED_ABIS[0]/SDK_INT or os.version; never parses a User-Agent. */
interface UpdateRuntimeInputs {
    val target: UpdateRuntimeTarget
}
