// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.SwipeAction
import kotlinx.collections.immutable.persistentListOf

/**
 * 08 "Keys owned here": the `appearance.*` keys M0a writes. Both live in the portable `settings`
 * file but none syncs (PO-37: appearance is device-local). `ThemeMode` and the later keys
 * (`artwork_tint`, `pure_black`, the M1+/M2+ rows) are added by their milestones.
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

    /** Library overflow › Show titles: title text under each grid tile (08 Library). */
    public val LIBRARY_TITLES: SettingKey.Bool =
        SettingKey.Bool(name = "appearance.library_titles", default = false)

    /** Library › Sort (08; M1 fixed 100 dp cells, density arrives with M10). */
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

    public val PLAYER_TIME: SettingKey.Choice<PlayerTimeDisplay> =
        SettingKey.Choice(
            name = "appearance.player_time",
            default = PlayerTimeDisplay.REMAINING,
            values = persistentListOf(PlayerTimeDisplay.REMAINING, PlayerTimeDisplay.TOTAL),
        )

    public val ALL: List<SettingKey<*>> =
        listOf(
            THEME,
            DYNAMIC_COLOR,
            LIBRARY_TITLES,
            LIBRARY_SORT,
            FEEDS_ROW_SWIPE,
            SWIPE_START_ACTION,
            SWIPE_END_ACTION,
            FEEDS_SELECTED_SOURCE,
            LIBRARY_GROUP_FILTER,
            LIBRARY_SEGMENT,
            MINI_PLAYER_SKIP,
            PLAYER_TIME,
        )
}
