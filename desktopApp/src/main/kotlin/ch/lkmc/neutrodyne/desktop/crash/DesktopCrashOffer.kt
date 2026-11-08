// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.RevealInFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The next-start crash offer's wiring (11 Crash files and the email dialog, D62/PO-10): the
 * state scan inputs and the [CrashReporter] whose `isAvailable` gates the offer — DEV builds
 * and an empty mailbox never ask. [urls] and [reveal] are the OS seams: the app leaves them
 * null and the window binds its `PlatformActions`; tests inject recording fakes.
 */
class DesktopCrashOffer(
    val dirs: AppDirs,
    val buildInfo: BuildInfo,
    val clock: Clock,
    val crashReporter: CrashReporter,
    val urls: ExternalUrlOpener? = null,
    val reveal: RevealInFolder? = null,
)

/**
 * Shows [DesktopCrashDialog] for the newest unsent crash file; the caller decides when (the
 * window composes this only after the database gate has opened, so startup and the first
 * frame are never delayed). "Send by email" opens the bounded `mailto:` through [urls] and
 * marks the file `.sent`; "Show report" reveals it through [reveal] and leaves it pending;
 * "Don't send" marks it `.dismissed`. The next file follows only after the current one is
 * handled, up to the state's bound — nothing is sent without the click.
 */
@Composable
internal fun DesktopCrashOfferDialog(
    offer: DesktopCrashOffer,
    urls: ExternalUrlOpener,
    reveal: RevealInFolder?,
) {
    val state =
        remember(offer, urls, reveal) {
            DesktopCrashDialogState(
                dirs = offer.dirs,
                buildInfo = offer.buildInfo,
                clock = offer.clock,
                crashReporter = offer.crashReporter,
                showInFolder = { path -> reveal?.reveal(path.toString()) == true },
                openMailto = { mailto -> urls.open(mailto) == OpenResult.OPENED },
            )
        }
    var pending by remember { mutableStateOf<List<PendingCrash>>(emptyList()) }
    LaunchedEffect(state) {
        pending = withContext(Dispatchers.IO) { state.pendingCrashes() }
    }
    val current = pending.firstOrNull() ?: return
    DesktopCrashDialog(
        onShowReport = { state.showReport(current) },
        onSendEmail = { if (state.sendByEmail(current)) pending = pending.drop(1) },
        onDismiss = { if (state.dismiss(current)) pending = pending.drop(1) },
    )
}
