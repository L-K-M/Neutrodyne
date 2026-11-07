// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/**
 * The "Continue on this device" offer (10 Now playing and handoff, 08 Continue on this device).
 * MS2 carries the API and the state; the card itself and session adoption's full behaviour are
 * MS3.
 */
data class RemoteSessionOffer(
    val episodeId: Long,
    val title: String,
    val podcastTitle: String,
    val positionMs: Long,
    val deviceName: String,
)
