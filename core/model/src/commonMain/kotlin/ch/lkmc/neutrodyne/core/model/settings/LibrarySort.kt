// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * The Library grid's sort order (`appearance.library_sort`, 08 Settings keys). `TITLE` uses
 * `TitleCollator`; the other three order by `LibraryTile` fields (02/03).
 */
public enum class LibrarySort { TITLE, RECENTLY_UPDATED, MOST_UNPLAYED, RECENTLY_ADDED }
