// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The five top-level destinations, in suite order: Feeds, Library, Up next, Downloads, Discover.
 * Each has its own back stack, owned by the shared navigation host in `:core:ui`.
 */
@Serializable
public sealed interface TopLevelKey : NavKey

/** The All feed and one page per group. The start tab: back from another tab's root returns here. */
@Serializable
public data object FeedsKey : TopLevelKey

/** The cover grid of subscriptions, the Groups view and selection mode. */
@Serializable
public data object LibraryKey : TopLevelKey

/** The user's queue and play context. */
@Serializable
public data object UpNextKey : TopLevelKey

/** In-progress, completed and failed downloads with storage. */
@Serializable
public data object DownloadsKey : TopLevelKey

/** Search, charts, add by URL, YouTube channels and import. */
@Serializable
public data object DiscoverKey : TopLevelKey

/**
 * Settings pages rendered by `SettingsKey`. There is deliberately no `SYNC` value: Settings › Sync
 * lives in `:feature:sync`, which `:feature:settings` cannot reach, so it has its own
 * [SyncSettingsKey].
 */
@Serializable
public enum class SettingsPage {
    APPEARANCE,
    FEEDS,
    DISCOVER,
    PLAYBACK,
    DOWNLOADS,
    YOUTUBE,
    BACKUP,
    UPDATES,
    PRIVACY,
    DESKTOP,
    ABOUT,
}

/** A subscribed podcast's detail screen. */
@Serializable
public data class PodcastKey(
    val podcastId: Long,
) : NavKey

/** The same screen in preview mode for a feed URL that is not subscribed yet. */
@Serializable
public data class PodcastPreviewKey(
    val feedUrl: String,
) : NavKey

/** Per-podcast settings: refresh, notifications, playback overrides, auto-download. */
@Serializable
public data class PodcastSettingsKey(
    val podcastId: Long,
) : NavKey

/** An episode's detail screen. An extra pane on wide layouts. */
@Serializable
public data class EpisodeKey(
    val episodeId: Long,
) : NavKey

/** The group editor. A null [groupId] creates a new group. */
@Serializable
public data class GroupEditKey(
    val groupId: Long?,
) : NavKey

/** The list of groups for renaming, reordering and deletion. */
@Serializable
public data object GroupsManageKey : NavKey

/** Per-group settings: refresh, notifications, playback defaults, auto-download. */
@Serializable
public data class GroupSettingsKey(
    val groupId: Long,
) : NavKey

/** The sheet that adds the given podcasts to groups. */
@Serializable
public data class AddToGroupsKey(
    val podcastIds: List<Long>,
) : NavKey

/** The sheet that lists every group, recent first, then A-Z. */
@Serializable
public data object AllGroupsKey : NavKey

/** Directory search results for a query, optionally restricted to a genre. */
@Serializable
public data class DirectoryKey(
    val query: String,
    val genreId: String?,
) : NavKey

/** The add-podcast sheet. A null [input] opens it empty; otherwise the input is pre-filled. */
@Serializable
public data class AddPodcastKey(
    val input: String?,
) : NavKey

/** An import session: preview, progress, report or restore preview. */
@Serializable
public data class ImportKey(
    val sessionId: String,
) : NavKey

/** Backup and restore. */
@Serializable
public data object BackupKey : NavKey

/** The export dialog. A null [groupId] exports every subscription. */
@Serializable
public data class ExportKey(
    val groupId: Long?,
) : NavKey

/** The Settings home list, the gear's target. */
@Serializable
public data object SettingsHomeKey : NavKey

/**
 * One Settings page. [openRelease] opens the validated release page once, when the Updates page's
 * state is `Available` (the update notification's "Open on GitHub" action).
 */
@Serializable
public data class SettingsKey(
    val page: SettingsPage,
    val openRelease: Boolean = false,
) : NavKey

/** The licences screen: library licences plus the bundled-components entries. */
@Serializable
public data object LicencesKey : NavKey

/** The Install & updates help page. [section] is an `InstallHelpSection` name, or "" for all collapsed. */
@Serializable
public data class InstallHelpKey(
    val section: String = "",
) : NavKey

/** The one-time developer-verification notice dialog (Android only). Never a route. */
@Serializable
public data object VerificationNoticeKey : NavKey

/** The keyboard-shortcuts dialog (desktop only, from the Help menu). */
@Serializable
public data object KeyboardShortcutsKey : NavKey

/** Settings › Sync, served by `:feature:sync`. */
@Serializable
public data object SyncSettingsKey : NavKey

/** The link flow. An empty [serverUrl] asks for the address first. */
@Serializable
public data class SyncSetupKey(
    val serverUrl: String = "",
) : NavKey

/** The "Link another device" approval sheet. */
@Serializable
public data class SyncApproveKey(
    val userCode: String = "",
) : NavKey

/** The linked-devices list. */
@Serializable
public data object SyncDevicesKey : NavKey

/** The mass-change guard dialog for one held change. */
@Serializable
public data class SyncHeldChangesKey(
    val id: Long,
) : NavKey

/** Sync diagnostics: server, account, rounds, outbox, clock offset, connection state. */
@Serializable
public data object SyncDiagnosticsKey : NavKey

/** App diagnostics (crash reporting, logs, storage). */
@Serializable
public data object DiagnosticsKey : NavKey

/** The playback-speed sheet, opened from the player. */
@Serializable
public data object SpeedKey : NavKey

/** The sleep-timer sheet, opened from the player. */
@Serializable
public data object SleepTimerKey : NavKey
