// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

internal fun radioFixture(playing: Boolean = false): RadioState =
    RadioState(
        playlists = listOf(RadioPlaylist(1, "Morning rotation", 3), RadioPlaylist(2, "After hours", 2)),
        playlistId = 1,
        episodes =
            listOf(
                RadioEpisode(1, "The art of paying attention", "Signal & Sound", 38 * 60_000L),
                RadioEpisode(2, "The city after midnight", "Side Streets", 32 * 60_000L),
                RadioEpisode(3, "A slower kind of internet", "Open Circuit", 45 * 60_000L),
                RadioEpisode(4, "Small rituals, big ideas", "Signal & Sound", 23 * 60_000L),
            ),
        selectedId = 1,
        elapsedMs = if (playing) 642_000 else 0,
        playing = playing,
        hasPlayed = playing,
        podcasts =
            listOf(
                RadioPodcast(1, "Signal & Sound"),
                RadioPodcast(2, "Side Streets"),
                RadioPodcast(3, "Open Circuit"),
            ),
    )
