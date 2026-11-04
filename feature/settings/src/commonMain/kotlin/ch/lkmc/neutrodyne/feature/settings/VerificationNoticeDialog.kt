// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import ch.lkmc.neutrodyne.core.navigation.InstallHelpKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.notice_how
import ch.lkmc.neutrodyne.feature.settings.resources.notice_ok
import ch.lkmc.neutrodyne.feature.settings.resources.notice_post_body
import ch.lkmc.neutrodyne.feature.settings.resources.notice_post_title
import ch.lkmc.neutrodyne.feature.settings.resources.notice_pre_body
import ch.lkmc.neutrodyne.feature.settings.resources.notice_pre_title
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun VerificationNoticeDialog(notices: UpdateNotices) {
    val navigator = LocalAppNavigator.current
    val phase by notices.verificationPhase.collectAsStateWithLifecycle()
    val pending by notices.pending.collectAsStateWithLifecycle()
    val error by notices.writeError.collectAsStateWithLifecycle()
    var closing by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val dismiss = {
        closing = true
        notices.dismiss(UpdateNotice.VERIFICATION_ENFORCEMENT)
    }
    LaunchedEffect(pending, closing) {
        if (closing && pending != UpdateNotice.VERIFICATION_ENFORCEMENT) {
            navigator.pop()
            if (showHelp) navigator.push(InstallHelpKey("GOOGLE_PLAY"))
        }
    }
    NdDialog(
        onDismissRequest = dismiss,
        title =
            stringResource(
                if (phase ==
                    VerificationPhase.PRE_ENFORCEMENT
                ) {
                    Res.string.notice_pre_title
                } else {
                    Res.string.notice_post_title
                },
            ),
        text =
            stringResource(
                if (phase ==
                    VerificationPhase.PRE_ENFORCEMENT
                ) {
                    Res.string.notice_pre_body
                } else {
                    Res.string.notice_post_body
                },
            ) +
                if (error != null) "\n\n" + stringResource(Res.string.settings_save_failed) else "",
        confirm = NdDialogAction(stringResource(Res.string.notice_ok), onClick = dismiss),
        dismiss =
            NdDialogAction(stringResource(Res.string.notice_how), onClick = {
                showHelp = true
                dismiss()
            }),
    )
}
