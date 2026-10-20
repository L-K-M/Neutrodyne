// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.discovery

import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer

/** Exact authorities prevent publisher URLs containing directory names from triggering lookups. */
object HostRecognizer {
    fun recognize(url: String): HostAction {
        val host = UrlNormalizer.origin(url)?.substringAfter("://") ?: return HostAction.Fetch
        val path =
            url
                .substringAfter("://")
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
        val appleId =
            when (host) {
                "podcasts.apple.com", "itunes.apple.com" -> {
                    Regex(
                        "(?:^|/)id([0-9]+)(?:/|$)",
                    ).find(path)?.groupValues?.get(1)
                }

                "pod.link" -> {
                    Regex("^([0-9]+)/?$").find(path)?.groupValues?.get(1)
                }

                "overcast.fm" -> {
                    Regex("^itunes([0-9]+)/?$").find(path)?.groupValues?.get(1)
                }

                else -> {
                    null
                }
            }
        if (appleId != null) return HostAction.AppleLookup(appleId)
        if (host ==
            "podcastindex.org"
        ) {
            Regex(
                "^podcast/([0-9]+)/?$",
            ).find(path)?.let { return HostAction.PodcastIndexLookup(it.groupValues[1]) }
        }
        if (host == "open.spotify.com" && path.startsWith("show/")) return HostAction.SpotifyShow
        if (host == "fyyd.de" && path.startsWith("podcast/")) return HostAction.FyydPage
        return HostAction.Fetch
    }
}
