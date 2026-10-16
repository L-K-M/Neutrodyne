// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The 05 icon catalogue's stable keys mapped to Material Symbols. Unknown but well-formed keys
 * (stored before a downgrade, or imported) return `null`, as do malformed ones — callers draw the
 * documented fallback (group name grapheme) instead of inventing an icon.
 */
public fun groupIcon(key: String?): ImageVector? =
    when (key) {
        "newspaper" -> NdIcons.Newspaper
        "public" -> NdIcons.Public
        "account_balance" -> NdIcons.AccountBalance
        "gavel" -> NdIcons.Gavel
        "memory" -> NdIcons.Memory
        "computer" -> NdIcons.Computer
        "science" -> NdIcons.Science
        "rocket_launch" -> NdIcons.RocketLaunch
        "psychology" -> NdIcons.Psychology
        "auto_stories" -> NdIcons.AutoStories
        "menu_book" -> NdIcons.MenuBook
        "history_edu" -> NdIcons.HistoryEdu
        "school" -> NdIcons.School
        "theater_comedy" -> NdIcons.TheaterComedy
        "music_note" -> NdIcons.MusicNote
        "movie" -> NdIcons.Movie
        "palette" -> NdIcons.Palette
        "sports_esports" -> NdIcons.SportsEsports
        "sports_soccer" -> NdIcons.SportsSoccer
        "fitness_center" -> NdIcons.FitnessCenter
        "restaurant" -> NdIcons.Restaurant
        "travel_explore" -> NdIcons.TravelExplore
        "child_care" -> NdIcons.ChildCare
        "self_improvement" -> NdIcons.SelfImprovement
        "bedtime" -> NdIcons.Bedtime
        "directions_car" -> NdIcons.DirectionsCar
        "work" -> NdIcons.Work
        "podcasts" -> NdIcons.Podcasts
        "smart_display" -> NdIcons.SmartDisplay
        "favorite" -> NdIcons.Favorite
        "star" -> NdIcons.Star
        else -> null
    }
