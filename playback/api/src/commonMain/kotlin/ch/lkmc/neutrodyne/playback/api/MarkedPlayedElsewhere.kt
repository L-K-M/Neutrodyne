// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A remote "marked played" retained on the loaded item (10 Remote played marks 2, 08 Sync
 * notices): [episodeId] guards the attribution and [deviceName] is the already-scrubbed remote
 * name the player's "Marked played on {device}" line shows. [loadId] is the [NowPlaying.loadId]
 * that was current when the notice posted — it pins the mark to that load occurrence, so a
 * reloaded or switched item can never inherit a stale line; null means "posted while nothing
 * was loaded" and never matches a real item.
 */
data class MarkedPlayedElsewhere(
    val episodeId: Long,
    val deviceName: String,
    val loadId: Long?,
)

/**
 * The app-scoped latch behind the player's "Marked played on {device}" line (08 Sync notices):
 * the sync implementation retains `SyncNotice.MarkedPlayedElsewhere` — which the applier only
 * posts for the item loaded here — while that item stays loaded, so a player opening after the
 * replay-0 notice still shows the line. The latch clears when the loaded item changes or
 * unloads; a notice that outlived its item never re-arms on a later reload.
 */
interface MarkedPlayedElsewhereSource {
    val markedPlayedElsewhere: StateFlow<MarkedPlayedElsewhere?>
}

/** Graphs and tests without sync: nothing was ever marked elsewhere. */
object NoMarkedPlayedElsewhere : MarkedPlayedElsewhereSource {
    override val markedPlayedElsewhere =
        MutableStateFlow<MarkedPlayedElsewhere?>(null).asStateFlow()
}
