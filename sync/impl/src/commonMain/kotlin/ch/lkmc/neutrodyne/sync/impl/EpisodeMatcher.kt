// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.BackupDao
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.KeyInput
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints

/**
 * The restore matching ladder of 02 Reuse by sync, used by the applier and the parked-state
 * applier: identity key (version-tolerant), then normalised enclosure URL, then guid. A match whose
 * local key differs from the record's `k` is an alias the caller records in `sync_clock`.
 */
class EpisodeMatcher(
    private val backupDao: BackupDao,
) {
    /** The matched local episode id, or `null` when the record must be stubbed or parked. */
    suspend fun match(
        podcastId: Long,
        key: String,
        hints: MatchHints?,
    ): Long? {
        val candidates = backupDao.restoreEpisodeCandidates(podcastId)
        if (candidates.isEmpty()) return null

        val kv = hints?.kv ?: EpisodeKeys.VERSION
        // A newer key version skips key matching only (02): the app cannot compute it, but the
        // enclosure and guid rungs below still apply.
        if (kv == EpisodeKeys.VERSION) {
            candidates.firstOrNull { it.identityKey == key }?.let { return it.id }
        } else if (kv < EpisodeKeys.VERSION) {
            val versioned =
                candidates.firstOrNull { candidate ->
                    EpisodeKeys.keyFor(candidate.toKeyInput(), kv) == key
                }
            if (versioned != null) return versioned.id
        }

        val enclosureKey = hints?.enc?.let(UrlNormalizer::forIdentity)
        if (enclosureKey != null) {
            candidates
                .firstOrNull { candidate ->
                    candidate.enclosureUrl?.let(UrlNormalizer::forIdentity) == enclosureKey
                }?.let { return it.id }
        }

        val guid = hints?.guid
        if (!guid.isNullOrEmpty()) {
            candidates.firstOrNull { it.guid == guid }?.let { return it.id }
        }
        return null
    }
}

private fun ch.lkmc.neutrodyne.core.database.RestoreEpisodeCandidate.toKeyInput(): KeyInput =
    KeyInput(
        guid = guid,
        enclosureUrl = enclosureUrl,
        title = title,
        pubDate = pubDate,
        link = link,
    )
