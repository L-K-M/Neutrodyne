// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** RSS `<itunes:episodeType>` (02 `episode.episodeType`; unknown stored names read back as null). */
enum class EpisodeType { FULL, TRAILER, BONUS }

/**
 * Whether an episode can be played (02 `episode.availability`; the 04 availability vocabulary).
 * Unknown values read back as [UNAVAILABLE] — fail unplayable, not silently playable.
 */
enum class Availability {
    AVAILABLE,
    UPCOMING,
    LIVE,
    MEMBERS_ONLY,
    AGE_RESTRICTED,
    REGION_BLOCKED,
    PRIVATE,
    KIDS_ONLY,
    UNAVAILABLE,
}

/** Who supplies a `person` row's data (02 `person.ownerType`). */
enum class OwnerType { PODCAST, EPISODE }

/** Where `chapter` rows came from (02 `chapter.source`). */
enum class ChapterSource { PODCASTING20_JSON, PSC, ID3, MP4, YOUTUBE_DESC }
