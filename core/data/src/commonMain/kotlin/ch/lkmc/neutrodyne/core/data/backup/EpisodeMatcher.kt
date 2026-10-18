// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.RestoreEpisodeCandidate
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.KeyInput
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject

/**
 * 02 Restore matching's shared episode ladder — one implementation for restore (05's
 * transactions B) and for the sync appliers that resolve `episode`/`upnext`/`session` record
 * references the same way (MS2): identity key (version-aware, `EpisodeKeys.keyFor` when the
 * hint's `kv` differs from the stored version; a `kv` newer than `EpisodeKeys.VERSION` skips
 * the step), then the normalised enclosure URL, then the canonical GUID. Every step requires
 * unique evidence — a shared enclosure or GUID picks no row, so the line falls through to a
 * stub rather than claim the wrong episode (D98's "prefer a visible duplicate over an N1
 * state loss").
 *
 * [index] is the suspend half: it loads one podcast's candidates inside the caller's
 * transaction and decodes `episode_description` heads only on demand. [EpisodeMatchIndex] is
 * the pure half: matching over the loaded rows.
 */
@Inject
internal class EpisodeMatcher(
    private val db: NeutrodyneDatabase,
) {
    /**
     * The podcast a record's `syncId` addresses (02 Reuse by sync). Redirects rewrite the
     * column in place, so even a stale `syncId` resolves here.
     */
    suspend fun podcastIdBySyncId(syncId: String): Long? = db.backupDao().podcastIdBySyncId(syncId)

    /**
     * One podcast's match index inside the caller's transaction: the candidates load once and
     * [EpisodeMatchIndex.match] stays pure. [keyVersions] is the `kv` set of the lines to
     * match — `h:`-needing rows decode their `episode_description` only when some version
     * differs from the row's stored one (02: the head is needed only to recompute `keyFor`).
     */
    suspend fun index(
        podcastId: Long,
        keyVersions: Set<Int>,
    ): EpisodeMatchIndex {
        val dao = db.backupDao()
        val rows = dao.restoreEpisodeCandidates(podcastId)

        val headIds =
            rows.mapNotNull { row ->
                val needsComputed =
                    keyVersions.any { kv ->
                        kv in 1..EpisodeKeys.VERSION && kv != EpisodeKeys.versionOf(row.identityKey)
                    }
                if (needsComputed && row.fallsThroughToHead()) row.id else null
            }
        val heads =
            if (headIds.isEmpty()) {
                emptyMap()
            } else {
                dao.descriptionBlobs(headIds).associate {
                    it.episodeId to EpisodeDescriptionCodec.decode(it.html).text
                }
            }
        return EpisodeMatchIndex(rows, heads)
    }
}

/**
 * The pure half of restore matching (02): one podcast's stored candidates indexed by identity
 * key (per line version), normalised enclosure and canonical GUID. `match` answers which
 * stored row a line claims — never what a GUID means to the feed; the D98 provenance writes
 * are a separate restore primitive.
 */
internal class EpisodeMatchIndex(
    private val rows: List<RestoreEpisodeCandidate>,
    private val heads: Map<Long, String>,
) {
    private val byEnclosure = rows.groupedBy { it.enclosureUrl?.let(UrlNormalizer::forIdentity) }
    private val byGuid = rows.groupedBy { EpisodeKeys.canonicalGuid(it.guid) }
    private val keyMaps = mutableMapOf<Int, Map<String, RestoreEpisodeCandidate>>()

    /**
     * Key → enclosure → GUID, in that order (02). Returns the claimed row's `id`, or null when
     * no step finds unique evidence — the caller then decides whether the line stubs.
     */
    fun match(hints: EpisodeMatchHints): Long? {
        // A `kv` the app cannot compute keys for is unmatchable by key (02), so the step is
        // skipped entirely — even a byte-identical stored key cannot be trusted across a
        // version the writer used but this build does not know.
        if (hints.kv in 1..EpisodeKeys.VERSION) {
            keyMap(hints.kv)[hints.identityKey]?.let { return it.id }
        }
        hints.enclosureUrl
            ?.let(UrlNormalizer::forIdentity)
            ?.let { byEnclosure[it] }
            ?.singleOrNull()
            ?.let { return it.id }
        return EpisodeKeys
            .canonicalGuid(hints.guid)
            ?.let { byGuid[it] }
            ?.singleOrNull()
            ?.id
    }

    /**
     * The candidates' keys at one line's version: a row stored at [kv] contributes its stored
     * key; every other row's key is `EpisodeKeys.keyFor` recomputed from its own columns
     * (`h:` rows need their prefetched head — [EpisodeMatcher.index] only loads the ones this
     * set of versions can reach). A computed key that repeats collapses to no claim, like the
     * enclosure/GUID steps.
     */
    private fun keyMap(kv: Int): Map<String, RestoreEpisodeCandidate> =
        keyMaps.getOrPut(kv) {
            rows
                .groupedBy { row ->
                    if (EpisodeKeys.versionOf(row.identityKey) == kv) row.identityKey else keyAtVersion(row, kv)
                }.mapValues { it.value.singleOrNull() }
                .mapNotNull { (key, row) -> row?.let { key to it } }
                .toMap()
        }

    private fun keyAtVersion(
        row: RestoreEpisodeCandidate,
        kv: Int,
    ): String =
        EpisodeKeys.keyFor(
            KeyInput(
                guid = row.guid,
                enclosureUrl = row.enclosureUrl,
                title = row.title,
                pubDate = row.pubDate,
                link = row.link,
                // `headKey` applies the head-truncation itself (idempotent), so the whole
                // decoded description is a valid `descriptionHead`.
                descriptionHead = heads[row.id],
            ),
            kv,
        )
}

/**
 * One restore line's match hints (05 `EpisodeLineV1`'s `k`/`kv`/`g`/`u`/`yt`; a sync record's
 * match hints stand in identically, 02 Reuse by sync). [externalMediaId] is never a match
 * step — it only qualifies an unmatched line for a stub. Callers matching an
 * `identityKey`-only reference (an Up next ref's `k`) pass `kv = EpisodeKeys.versionOf(k)`.
 */
internal data class EpisodeMatchHints(
    val identityKey: String,
    val kv: Int = EpisodeKeys.VERSION,
    val guid: String? = null,
    val enclosureUrl: String? = null,
    val externalMediaId: String? = null,
)

/** The row's own columns fall through to the `h:` recipe — only then is a head fetched. */
private fun RestoreEpisodeCandidate.fallsThroughToHead(): Boolean =
    EpisodeKeys.canonicalGuid(guid) == null &&
        enclosureUrl?.let(UrlNormalizer::forIdentity) == null &&
        !(title.trim().isNotEmpty() && pubDate != null) &&
        link?.trim()?.isNotEmpty() != true

private fun List<RestoreEpisodeCandidate>.groupedBy(
    keyOf: (RestoreEpisodeCandidate) -> String?,
): Map<String, List<RestoreEpisodeCandidate>> =
    mapNotNull { row -> keyOf(row)?.let { it to row } }.groupBy({ it.first }, { it.second })
