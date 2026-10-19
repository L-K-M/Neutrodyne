// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * Bits of `podcast.youtubeVariants` (02 JSON columns and bitmasks; meaningful only for
 * `YOUTUBE_CHANNEL`, semantics owned by 04). Default 1 ([LONG_FORM]).
 */
object YouTubeVariantBits {
    const val LONG_FORM = 1
    const val SHORTS = 2
    const val LIVE = 4
    const val ALL = LONG_FORM or SHORTS or LIVE

    fun isValid(variants: Int): Boolean = variants != 0 && variants and ALL == variants

    /** Imported Shorts-only/live-only values are valid; interactive settings keep LONG_FORM. */
    fun isValidForSettings(variants: Int): Boolean = isValid(variants) && variants and LONG_FORM != 0
}

/**
 * Bits of `podcast_group.filterFlags` and `play_session.contextFilterFlags` (02 bitmasks). Media
 * filter and minimum date are separate columns, never bits.
 */
object FilterFlagBits {
    const val UNPLAYED = 1
    const val DOWNLOADED = 2
    const val IN_PROGRESS = 4
}
