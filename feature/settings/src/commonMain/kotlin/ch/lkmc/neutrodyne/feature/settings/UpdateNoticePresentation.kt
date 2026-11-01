// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.root.RootNotice
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.notice_off
import ch.lkmc.neutrodyne.feature.settings.resources.notice_undo
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI-only shell bridge; scheduling and durable state remain owned by the update services. */
public class UpdateRootPresentation internal constructor(
    public val badge: Boolean,
    public val notice: RootNotice?,
    public val dismiss: (RootNotice) -> Unit,
    public val disable: () -> Unit,
    public val feedback: @Composable () -> Unit,
)

@Composable
public fun rememberUpdateRootPresentation(
    checker: AppUpdateChecker,
    notices: UpdateNotices,
    settings: SettingsRepository,
    requester: NotificationPermissionRequester? = null,
): UpdateRootPresentation {
    val state by checker.state.collectAsStateWithLifecycle()
    val pending by notices.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actions = remember(settings, notices, scope) { UpdateNoticeActions(settings, notices, scope) }
    return UpdateRootPresentation(
        badge = state is UpdateCheckState.Available,
        notice =
            when (pending) {
                UpdateNotice.FIRST_RUN_CHOICE -> RootNotice.FIRST_RUN_CHOICE
                UpdateNotice.VERIFICATION_ENFORCEMENT -> RootNotice.VERIFICATION_ENFORCEMENT
                null -> null
            },
        dismiss = { notice -> if (notice == RootNotice.FIRST_RUN_CHOICE) actions.acknowledge(requester) },
        disable = actions::disable,
        feedback = {
            val messages by actions.messages.flow.collectAsStateWithLifecycle()
            val error by notices.writeError.collectAsStateWithLifecycle()
            LaunchedEffect(error) { if (error != null) actions.reportFailure() }
            ShowUserMessages(messages, actions.messages::shown, actions::undo)
        },
    )
}

internal class UpdateNoticeActions(
    private val settings: SettingsRepository,
    private val notices: UpdateNotices,
    private val scope: CoroutineScope,
) {
    val messages = UserMessages()
    private var busy = false
    private var undoId: Long? = null

    fun acknowledge(requester: NotificationPermissionRequester?) {
        if (busy || notices.pending.value != UpdateNotice.FIRST_RUN_CHOICE) return

        busy = true
        scope.launch {
            try {
                notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
                val success =
                    combine(notices.pending, notices.writeError) { pending, error ->
                        when {
                            pending != UpdateNotice.FIRST_RUN_CHOICE -> true
                            error != null -> false
                            else -> null
                        }
                    }.first { it != null } == true
                if (success && requester?.granted == false) requester.request {}
            } finally {
                busy = false
            }
        }
    }

    fun disable() {
        if (busy) return

        busy = true
        scope.launch {
            try {
                if (!save(false)) return@launch

                notices.dismiss(UpdateNotice.FIRST_RUN_CHOICE)
                undoId = messages.post(UiText.Res(Res.string.notice_off), UiText.Res(Res.string.notice_undo)).id
            } finally {
                busy = false
            }
        }
    }

    fun undo(id: Long) {
        if (id != undoId) return

        undoId = null
        scope.launch { save(true) }
    }

    fun reportFailure() {
        messages.post(UiText.Res(Res.string.settings_save_failed))
    }

    private suspend fun save(enabled: Boolean): Boolean {
        val outcome = suspendRunCatching { settings.set(UpdateSettingKeys.CHECK_ENABLED, enabled) }.getOrNull()
        if (outcome is Outcome.Success) return true

        reportFailure()
        return false
    }
}
