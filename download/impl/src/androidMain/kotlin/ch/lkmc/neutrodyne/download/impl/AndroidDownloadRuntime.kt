// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.download.api.DownloadController

/** Composition seam for W4/W5. No engine or reconciler is fabricated by the Android hosts.
 * The integration provider supplies these callbacks from the common engine and reconciler. */
internal class AndroidDownloadRuntime(
    val drain: suspend (DrainRequest) -> DrainOutcome,
    val reconcile: suspend () -> Unit,
    val stop: (RunnerToken, StopIntent) -> Unit,
    val wake: (DownloadLane) -> Unit,
    val controller: () -> DownloadController,
    val startup: suspend () -> Unit = reconcile,
    val daily: suspend () -> Unit = reconcile,
)

/** The app resolves shared UI wording without adding a download:impl dependency on core:ui. */
@dev.zacsweers.metro.Qualifier
annotation class DownloadErrorText
