// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 11's Busy rule as the window's application-level service (the close rule reads it, never the
 * DAOs): `nowPlaying.isPlaying` or an active/runnable transfer — `DownloadController.observeBusy`
 * already covers `DOWNLOADING`, a runnable `QUEUED` and a move in progress. Paused playback,
 * refresh, sync and engine updates do not count.
 *
 * The download side is gated on the database open like the shell's `failedDownloads` flow:
 * resolving `DownloadController` before the open would block (or throw on the EDT), and a
 * failed open reports `false` — no database can run no transfer. The open state drives the
 * download flow through `flatMapLatest`, so a reopen after 01's recovery gate follows the
 * fresh controller instead of staying on the closed one. The shell constructs the one
 * instance next to `WindowActivator`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AppBusySignal(
    nowPlaying: Flow<NowPlaying?>,
    openState: Flow<DatabaseOpenState>,
    private val downloads: () -> DownloadController,
) {
    val busy: Flow<Boolean> =
        combine(
            nowPlaying.map { it?.isPlaying == true }.distinctUntilChanged(),
            transferBusy(openState),
        ) { playing, transfers -> playing || transfers }
            .distinctUntilChanged()

    private fun transferBusy(openState: Flow<DatabaseOpenState>): Flow<Boolean> =
        openState.flatMapLatest { state ->
            if (state is DatabaseOpenState.Opened) downloads().observeBusy() else flowOf(false)
        }
}
