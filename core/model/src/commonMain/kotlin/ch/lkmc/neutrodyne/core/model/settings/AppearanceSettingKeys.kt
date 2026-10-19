// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.SwipeAction
import kotlinx.collections.immutable.persistentListOf

/**
 * 08 "Keys owned here": portable appearance preferences follow backup policy but never sync
 * (PO-37). The `ui.*` selection and panel preferences live only in `device_settings`.
 */
public object AppearanceSettingKeys {
    /** Appearance › Theme. */
    public val THEME: SettingKey.Choice<ThemeMode> =
        SettingKey.Choice(
            name = "appearance.theme",
            default = ThemeMode.SYSTEM,
            values = persistentListOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
        )

    /** Appearance › Use wallpaper colours; only Android API 31+ shows the row. */
    public val DYNAMIC_COLOR: SettingKey.Bool =
        SettingKey.Bool(name = "appearance.dynamic_color", default = true)

    val ARTWORK_TINT = SettingKey.Bool("appearance.artwork_tint", true)
    val PURE_BLACK = SettingKey.Bool("appearance.pure_black", false)
    val LIBRARY_DENSITY =
        SettingKey.Choice(
            "appearance.library_density",
            LibraryDensity.MEDIUM,
            persistentListOf(LibraryDensity.SMALL, LibraryDensity.MEDIUM, LibraryDensity.LARGE),
        )

    /** Closing the panel persists across playback items; reopening explicitly clears it. */
    val PLAYER_PANEL_HIDDEN = SettingKey.Bool("ui.player_panel_hidden", false, SettingsFile.DEVICE)

    /** Library overflow › Show titles: title text under each grid tile (08 Library). */
    public val LIBRARY_TITLES: SettingKey.Bool =
        SettingKey.Bool(name = "appearance.library_titles", default = false)

    /** Library › Sort (08). */
    public val LIBRARY_SORT: SettingKey.Choice<LibrarySort> =
        SettingKey.Choice(
            name = "appearance.library_sort",
            default = LibrarySort.TITLE,
            values =
                persistentListOf(
                    LibrarySort.TITLE,
                    LibrarySort.RECENTLY_UPDATED,
                    LibrarySort.MOST_UNPLAYED,
                    LibrarySort.RECENTLY_ADDED,
                ),
        )

    /** Pager and row gestures are mutually exclusive (08 Group feed pager). */
    val FEEDS_ROW_SWIPE = SettingKey.Bool("appearance.feeds_row_swipe", false)

    /** Start-to-end defaults to Up next; undelivered actions stay inert until M4/M6. */
    public val SWIPE_START_ACTION: SettingKey.Choice<SwipeAction> =
        SettingKey.Choice(
            "appearance.swipe_start_action",
            SwipeAction.ADD_UP_NEXT,
            persistentListOf(SwipeAction.ADD_UP_NEXT, SwipeAction.MARK_PLAYED, SwipeAction.DOWNLOAD, SwipeAction.NONE),
        )

    public val SWIPE_END_ACTION: SettingKey.Choice<SwipeAction> =
        SettingKey.Choice(
            "appearance.swipe_end_action",
            SwipeAction.MARK_PLAYED,
            persistentListOf(SwipeAction.ADD_UP_NEXT, SwipeAction.MARK_PLAYED, SwipeAction.DOWNLOAD, SwipeAction.NONE),
        )

    /** Persisted selection identity (08 Group feed pager). */
    val FEEDS_SELECTED_SOURCE = SettingKey.Text("ui.feeds_selected_source", "all", SettingsFile.DEVICE)

    /** Library's segmented view (08 Library), device-local. */
    val LIBRARY_SEGMENT: SettingKey.Choice<LibrarySegment> =
        SettingKey.Choice(
            name = "ui.library_segment",
            default = LibrarySegment.PODCASTS,
            values = persistentListOf(LibrarySegment.PODCASTS, LibrarySegment.GROUPS),
            file = SettingsFile.DEVICE,
        )

    /** Empty means All; otherwise Ungrouped or a group UUID (08 Library). */
    val LIBRARY_GROUP_FILTER = SettingKey.Text("ui.library_group_filter", "", SettingsFile.DEVICE)

    /** Mini player › optional forward-skip control (08 Player sheet, M4). */
    public val MINI_PLAYER_SKIP = SettingKey.Bool("appearance.mini_player_skip", true)

    val YOUTUBE_ROW_ART =
        SettingKey.Choice(
            "appearance.youtube_row_art",
            YouTubeRowArt.VIDEO_THUMBNAIL,
            persistentListOf(YouTubeRowArt.VIDEO_THUMBNAIL, YouTubeRowArt.CHANNEL_AVATAR),
        )

    public val ALL: List<SettingKey<*>> =
        listOf(
            YOUTUBE_ROW_ART,
            THEME,
            DYNAMIC_COLOR,
            ARTWORK_TINT,
            PURE_BLACK,
            LIBRARY_DENSITY,
            PLAYER_PANEL_HIDDEN,
            LIBRARY_TITLES,
            LIBRARY_SORT,
            FEEDS_ROW_SWIPE,
            SWIPE_START_ACTION,
            SWIPE_END_ACTION,
            FEEDS_SELECTED_SOURCE,
            LIBRARY_GROUP_FILTER,
            LIBRARY_SEGMENT,
            MINI_PLAYER_SKIP,
        )
}
