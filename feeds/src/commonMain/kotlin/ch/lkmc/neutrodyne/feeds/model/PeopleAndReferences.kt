// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.model

import kotlinx.serialization.Serializable

/** `podcast:person` (Podcasting 2.0): a name with optional role, group and profile links. */
@Serializable
public data class Person(
    val name: String,
    val role: String = ROLE_HOST,
    val group: String = GROUP_CAST,
    val img: String? = null,
    val href: String? = null,
) {
    public companion object {
        public const val ROLE_HOST: String = "host"
        public const val GROUP_CAST: String = "cast"
    }
}

/** `podcast:funding`: a donation link whose label is capped at 128 chars (03 Field mapping). */
@Serializable
public data class Funding(
    val url: String,
    val title: String = "",
)

/** `podcast:transcript`; `application/srt` and `application/x-subrip` are the same type (03 Field mapping). */
@Serializable
public data class TranscriptRef(
    val url: String,
    val type: String? = null,
    val language: String? = null,
    val rel: String? = null,
)

/** One `psc:chapter` of Podlove Simple Chapters, `start` parsed to milliseconds (03 Field mapping). */
@Serializable
public data class InlineChapter(
    val startMs: Long,
    val title: String = "",
    val href: String? = null,
    val image: String? = null,
)
