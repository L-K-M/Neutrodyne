// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * 10 Episode-state rules rule 4 ("pull before play"): the sync side of MS3. The visible-UI
 * start paths (`PlayStarter.beforeUiStart`, called by `PlaybackController.playEpisode`/
 * `playEpisodeAt`/`playFeed`/`playDownloads`/`play` on both platforms) await this before the
 * start is planned, so a newer remote position lands in `episode_position` before
 * `StartPositionRule` reads it.
 *
 * Contract: never throws; returns at once while sync is off or revoked, the device is offline,
 * or a round finished less than 15 s ago; otherwise it runs one round with a ≤ 1.5 s budget —
 * on timeout the caller proceeds and the round continues in the background. A cancellation of
 * the caller propagates (a cancelled start must not play); it never cancels the spawned round.
 */
fun interface PrePlaySync {
    suspend fun beforeStart()

    companion object {
        /** The unconfigured binding (10): sync off, revoked or offline costs no wait. */
        val None = PrePlaySync { }
    }
}
