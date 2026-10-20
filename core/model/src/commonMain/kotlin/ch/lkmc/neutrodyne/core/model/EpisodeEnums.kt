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
enum class ChapterSource {
    PODCASTING20_JSON,
    PSC,
    ID3,
    MP4,
    YOUTUBE_DESC,
    ;

    companion object {
        /** Never merge sources. A hidden-only or invalid source cannot mask a visible fallback. */
        fun winning(rows: List<EpisodeChapter>): List<EpisodeChapter> =
            entries.firstNotNullOfOrNull { source ->
                normalize(rows.filter { it.source == source }).filterNot { it.hidden }.takeIf { it.isNotEmpty() }
            } ?: emptyList()

        /**
         * One source at a time: stable first duplicate wins, negative starts are discarded.
         * Missing/invalid ends use the next start; valid ends are capped there. Explicit shorter
         * ends leave gaps; the last unknown end stays null. Hidden rows retain their boundaries.
         */
        fun normalize(rows: List<EpisodeChapter>): List<EpisodeChapter> {
            require(rows.map { it.source }.distinct().size <= 1) { "Normalize one chapter source at a time" }
            val sorted = rows.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }
            return sorted.mapIndexed { index, chapter ->
                val next = sorted.getOrNull(index + 1)?.startMs
                val validEnd = chapter.endMs?.takeIf { it > chapter.startMs }
                chapter.copy(endMs = validEnd?.let { end -> next?.let { end.coerceAtMost(it) } ?: end } ?: next)
            }
        }
    }
}

/**
 * What the feed-scoped `episode_guid_provenance` table knows about one canonical GUID (02, D98;
 * authority rules: 03 Identity keys). Recorded values are append-only in authority:
 * [KNOWN_AMBIGUOUS] is sticky and is never demoted; [KNOWN_INDEPENDENT] upgrades to ambiguous on
 * any ambiguity observation. [OBSERVED] carries no authority at all: it is the durable record of
 * a first sighting that was not a qualifying introduction (a paging window, a truncated document,
 * older pages or mere stored carriage), so a later complete singleton cannot promote a GUID that
 * was never safely introduced. **Absence of a row is the fourth state**, never seen, and never
 * proves independence: podcasts without a coverage marker and migrated V1 libraries bootstrap
 * there. Constants are append-only (02 Conventions).
 */
enum class GuidKnowledge { KNOWN_AMBIGUOUS, KNOWN_INDEPENDENT, OBSERVED }
