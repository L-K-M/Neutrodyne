// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.navigation3.runtime.NavKey

/**
 * A platform-neutral navigation input. Android's `MainActivity` and the desktop's
 * `DesktopOpenHandler` convert intents, links, files and notification clicks into these; the shell
 * applies the resulting [Route] to the navigation state.
 */
public data class RouteInput(
    val action: Action,
    val uri: String? = null,
    val text: String? = null,
    val internal: Boolean = false,
) {
    public enum class Action {
        LAUNCH,
        VIEW,
        SEND,

        /** A file the OS or the user handed the desktop app. */
        OPEN_FILE,
    }
}

/** Where a [RouteInput] goes. Routes only navigate; every write needs a tap on the destination. */
public sealed interface Route {
    /** No navigation: launcher starts, unknown inputs, malformed IDs, unknown pages. */
    public data object None : Route

    /** Selects [tab] and replaces its stack above the root with [stack]. */
    public data class Navigate(
        val tab: TopLevelKey,
        val stack: List<NavKey>,
    ) : Route

    /** Pushes [key] onto the current tab (sheets, settings pages, notices). */
    public data class Push(
        val key: NavKey,
    ) : Route

    /** Selects the Feeds tab and its pager source. A null [groupUuid] selects All. */
    public data class SelectFeed(
        val groupUuid: String?,
    ) : Route

    /** Expands the player sheet (or reveals the side panel). */
    public data object ExpandPlayer : Route
}

/**
 * Turns a [RouteInput] into a [Route]. External links and shared text only open the add sheet;
 * files need an import session created by the shell before routing its internal preview URI.
 * Inputs that are not `internal` can never reach the `open/…` routes, so a
 * foreign program cannot jump into Settings or open a release page.
 */
public object IntentRouter {
    private const val SCHEME: String = "neutrodyne"
    private const val SCHEME_SEPARATOR: String = ":"
    private const val HOST_PREFIX: String = "//"
    private const val QUERY_SEPARATOR: String = "?"
    private const val FRAGMENT_SEPARATOR: String = "#"
    private const val PATH_SEPARATOR: String = "/"

    private const val OPEN_SEGMENT: String = "open"
    private const val EPISODE_SEGMENT: String = "episode"
    private const val PODCAST_SEGMENT: String = "podcast"
    private const val GROUP_SEGMENT: String = "group"
    private const val DOWNLOADS_SEGMENT: String = "downloads"
    private const val PLAYER_SEGMENT: String = "player"
    private const val IMPORT_SEGMENT: String = "import"
    private const val SETTINGS_SEGMENT: String = "settings"
    private const val HELP_SEGMENT: String = "help"
    private const val INSTALL_SEGMENT: String = "install"
    private const val DIAGNOSTICS_SEGMENT: String = "diagnostics"
    private const val SYNC_SEGMENT: String = "sync"
    private const val UPDATES_SEGMENT: String = "updates"
    private const val RELEASE_SEGMENT: String = "release"

    private const val SINGLE_SEGMENT_ROUTE_SIZE: Int = 1
    private const val DOUBLE_SEGMENT_ROUTE_SIZE: Int = 2

    private val updatesReleaseSegments: List<String> =
        listOf(SETTINGS_SEGMENT, UPDATES_SEGMENT, RELEASE_SEGMENT)

    /** Maps [input] to its route, or [Route.None] when nothing matches. */
    public fun route(input: RouteInput): Route {
        if (listOfNotNull(input.text, input.uri).any {
                it.length > MAX_INPUT_BYTES || it.encodeToByteArray().size > MAX_INPUT_BYTES
            }
        ) {
            return Route.None
        }
        if (!input.internal) return externalRoute(input)

        val segments = openSegments(input.uri)

        if (segments.isEmpty()) {
            return Route.None
        }

        return match(segments)
    }

    private const val MAX_INPUT_BYTES = 4 * 1024
    private val podcastSchemes = setOf("feed", "podcast", "pcast", "itpc")

    private fun externalRoute(input: RouteInput): Route {
        if (input.action == RouteInput.Action.SEND) {
            val text = input.text?.trim()?.takeIf { it.isNotEmpty() } ?: return Route.None
            return Route.Push(AddPodcastKey(text))
        }
        if (input.action != RouteInput.Action.VIEW) return Route.None

        val uri = input.uri?.trim() ?: return Route.None
        val scheme = uri.substringBefore(':').lowercase()
        if (scheme in podcastSchemes && uri.substringAfter(':', "").isNotBlank()) {
            return Route.Push(AddPodcastKey(uri))
        }
        val authorityAndPath = uri.substringAfter("://", "")
        if (scheme == SCHEME && authorityAndPath.startsWith("subscribe?")) {
            return Route.Push(AddPodcastKey(uri))
        }
        val authority = authorityAndPath.substringBefore('/').substringBefore('?').substringBefore('#')
        if (scheme == "https" && authority.lowercase() in setOf("podcasts.apple.com", "podcasts.apple.com:443")) {
            return Route.Push(AddPodcastKey(uri))
        }
        return Route.None
    }

    // Splits a `neutrodyne://open/…` URI into its path segments, or an empty list when the input is
    // not such a URI. The scheme is lowercased before matching; queries and fragments are ignored.
    private fun openSegments(rawUri: String?): List<String> {
        if (rawUri == null) {
            return emptyList()
        }

        val scheme = rawUri.substringBefore(SCHEME_SEPARATOR)

        if (!scheme.equals(SCHEME, ignoreCase = true)) {
            return emptyList()
        }

        val afterScheme = rawUri.substring(scheme.length + SCHEME_SEPARATOR.length)

        if (!afterScheme.startsWith(HOST_PREFIX)) {
            return emptyList()
        }

        val path =
            afterScheme
                .removePrefix(HOST_PREFIX)
                .substringBefore(QUERY_SEPARATOR)
                .substringBefore(FRAGMENT_SEPARATOR)

        return path.split(PATH_SEPARATOR).filter { it.isNotEmpty() }
    }

    // Matches the segments after `neutrodyne://`. Anything else is None (logged redacted by the shell).
    private fun match(segments: List<String>): Route {
        if (segments.first() != OPEN_SEGMENT) {
            return Route.None
        }

        val rest = segments.drop(1)

        if (rest.size == SINGLE_SEGMENT_ROUTE_SIZE) {
            return singleSegment(rest.first())
        }

        if (rest.size == DOUBLE_SEGMENT_ROUTE_SIZE) {
            return doubleSegment(rest[0], rest[1])
        }

        if (rest == updatesReleaseSegments) {
            return Route.Push(SettingsKey(SettingsPage.UPDATES, openRelease = true))
        }

        return Route.None
    }

    private fun singleSegment(name: String): Route =
        when (name) {
            DOWNLOADS_SEGMENT -> Route.Navigate(DownloadsKey, emptyList())
            PLAYER_SEGMENT -> Route.ExpandPlayer
            DIAGNOSTICS_SEGMENT -> Route.Push(DiagnosticsKey)
            else -> Route.None
        }

    private fun doubleSegment(
        section: String,
        argument: String,
    ): Route =
        when (section) {
            EPISODE_SEGMENT -> {
                val id = argument.toLongOrNull() ?: return Route.None
                Route.Push(EpisodeKey(id))
            }

            PODCAST_SEGMENT -> {
                val id = argument.toLongOrNull() ?: return Route.None
                Route.Navigate(LibraryKey, listOf(PodcastKey(id)))
            }

            GROUP_SEGMENT -> {
                Route.SelectFeed(argument)
            }

            IMPORT_SEGMENT -> {
                Route.Navigate(LibraryKey, listOf(ImportKey(argument)))
            }

            SETTINGS_SEGMENT -> {
                settingsRoute(argument)
            }

            HELP_SEGMENT -> {
                if (argument == INSTALL_SEGMENT) Route.Push(InstallHelpKey()) else Route.None
            }

            else -> {
                Route.None
            }
        }

    private fun settingsRoute(page: String): Route {
        if (page.lowercase() == SYNC_SEGMENT) {
            return Route.Push(SyncSettingsKey)
        }
        if (page.lowercase() == "backup") return Route.Push(BackupKey)

        val match = SettingsPage.entries.firstOrNull { it.name == page.uppercase() } ?: return Route.None

        return Route.Push(SettingsKey(match))
    }
}
