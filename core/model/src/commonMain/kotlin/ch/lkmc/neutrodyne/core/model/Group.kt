// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Canonical group view (05 Group model and lifecycle); uuid survives ordinary edits. */
data class Group(
    val id: Long,
    val uuid: String,
    val name: String,
    val orderKey: String,
    val colorArgb: Int?,
    val iconKey: String?,
    val feedOrder: FeedOrder,
    val playOrder: FeedOrder,
    val filterFlags: Int,
    val mediaFilter: MediaFilter,
    val hideOlderThanDays: Int?,
    val showAsTab: Boolean,
    val lastViewedAt: Long?,
    val createdAt: Long,
    val memberCount: Int,
)

data class GroupDraft(
    val name: String,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    val showAsTab: Boolean = true,
)

sealed interface GroupEdit {
    data class Rename(
        val name: String,
    ) : GroupEdit

    data class Look(
        val colorArgb: Int?,
        val iconKey: String?,
    ) : GroupEdit

    data class Orders(
        val feedOrder: FeedOrder,
        val playOrder: FeedOrder,
    ) : GroupEdit

    data class View(
        val filterFlags: Int,
        val mediaFilter: MediaFilter,
        val hideOlderThanDays: Int?,
    ) : GroupEdit {
        fun hasSupportedAgeLimit(): Boolean = hideOlderThanDays == null || hideOlderThanDays in SUPPORTED_AGE_LIMITS

        private companion object {
            val SUPPORTED_AGE_LIMITS = setOf(1, 3, 7, 14, 30, 90, 365)
        }
    }

    data class ShowAsTab(
        val show: Boolean,
    ) : GroupEdit
}

/** Stable palette order and seed values from 05 Palette. */
object GroupPalette {
    val COLORS: List<Int> =
        listOf(
            0xFFF44336.toInt(),
            0xFFFF5722.toInt(),
            0xFFFFC107.toInt(),
            0xFF4CAF50.toInt(),
            0xFF009688.toInt(),
            0xFF00BCD4.toInt(),
            0xFF2196F3.toInt(),
            0xFF3F51B5.toInt(),
            0xFF673AB7.toInt(),
            0xFFE91E63.toInt(),
            0xFF795548.toInt(),
            0xFF607D8B.toInt(),
        )
    val KEYS: List<String> =
        listOf(
            "red",
            "deep_orange",
            "amber",
            "green",
            "teal",
            "cyan",
            "blue",
            "indigo",
            "deep_purple",
            "pink",
            "brown",
            "blue_grey",
        )

    fun nextColor(usedColors: List<Int?>): Int =
        COLORS.firstOrNull { it !in usedColors } ?: COLORS[usedColors.size % COLORS.size]

    /** Imported/custom colours are retained, with opaque alpha (05 Palette). */
    fun opaque(colorArgb: Int): Int = colorArgb or 0xFF000000.toInt()
}

/** Unknown well-formed keys survive storage and export (05 Icons). */
object GroupIcons {
    val KEYS: List<String> =
        listOf(
            "newspaper",
            "public",
            "account_balance",
            "gavel",
            "memory",
            "computer",
            "science",
            "rocket_launch",
            "psychology",
            "auto_stories",
            "menu_book",
            "history_edu",
            "school",
            "theater_comedy",
            "music_note",
            "movie",
            "palette",
            "sports_esports",
            "sports_soccer",
            "fitness_center",
            "restaurant",
            "travel_explore",
            "child_care",
            "self_improvement",
            "bedtime",
            "directions_car",
            "work",
            "podcasts",
            "smart_display",
            "favorite",
            "star",
        )
    private val keyPattern = Regex("^[a-z0-9_]{1,40}$")

    fun isValid(key: String?): Boolean = key == null || keyPattern.matches(key)
}
