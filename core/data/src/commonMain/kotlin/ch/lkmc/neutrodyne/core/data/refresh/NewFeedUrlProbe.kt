// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bounded, post-commit tag chain; fetches never hold the engine's commit lock. */
@SingleIn(AppScope::class)
@Inject
internal class NewFeedUrlProbe(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
) {
    private val rejectionLock = Mutex()
    private val rejected = LinkedHashMap<String, Long>()

    suspend fun run(
        podcastId: Long,
        current: ParsedFeed,
        deadlineElapsedMs: Long,
        fetch: suspend (DueFeed) -> AdapterResult,
        adopt: suspend (DueFeed, String, AdapterResult.Parsed) -> FeedOutcome,
        claimSurvivor: suspend (Long) -> Boolean = { true },
    ): FeedOutcome? {
        var id = podcastId
        var evidence = current
        var outcome: FeedOutcome? = null
        val visited = mutableSetOf<String>()
        repeat(MAX_HOPS) {
            currentCoroutineContext().ensureActive()
            if (deadlineElapsedMs != RefreshRequest.NO_DEADLINE &&
                clock.elapsedRealtime() >= deadlineElapsedMs - DEADLINE_MARGIN_MS
            ) {
                return outcome
            }

            val stored = db.podcastDao().byId(id) ?: return outcome
            visited += stored.feedKey
            val url = stored.pendingNewFeedUrl ?: return outcome
            val key = UrlNormalizer.forIdentity(url) ?: return outcome
            if (!visited.add(key) || isRejected(key)) return outcome
            val feed = db.podcastDao().dueFeedById(id) ?: return outcome
            // No source validators or explicitly typed credentials accompany a new origin.
            val target = feed.copy(feedUrl = url, etag = null, lastModified = null, contentSha256 = null)
            val result = suspendRunCatching { fetch(target) }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (deadlineElapsedMs != RefreshRequest.NO_DEADLINE && clock.elapsedRealtime() >= deadlineElapsedMs) {
                return outcome
            }
            if (result is AdapterResult.Deferred) return outcome

            val realEvidence = evidence.copy(podcastGuid = stored.podcastGuid.takeUnless { stored.podcastGuidDerived })
            if (result !is AdapterResult.Parsed ||
                result.feed.items.none { it.primaryEnclosure != null || it.externalMediaId != null } ||
                !FeedMovePlausibility.isPlausibleMove(realEvidence, result.feed)
            ) {
                reject(key)
                return outcome
            }

            val committed = suspendRunCatching { adopt(feed, url, result) }.getOrNull()
            if (committed !is FeedOutcome.Ingested && committed !is FeedOutcome.Merged) {
                reject(key)
                return outcome
            }
            outcome = committed
            val survivor = (committed as? FeedOutcome.Merged)?.intoPodcastId ?: id
            if (survivor != id && !claimSurvivor(survivor)) return outcome
            id = survivor
            evidence = result.feed
        }
        return outcome
    }

    private suspend fun isRejected(key: String): Boolean =
        rejectionLock.withLock {
            val at = rejected.remove(key) ?: return@withLock false
            if (clock.now() - at >= REJECTION_TTL_MS) return@withLock false

            // Reads update recency, not expiry: a frequently encountered tag still expires.
            rejected[key] = at
            true
        }

    private suspend fun reject(key: String) =
        rejectionLock.withLock {
            rejected.remove(key)
            rejected[key] = clock.now()
            if (rejected.size > REJECTION_CAPACITY) rejected.remove(rejected.keys.first())
        }

    private companion object {
        const val MAX_HOPS = 5
        const val REJECTION_CAPACITY = 100
        const val REJECTION_TTL_MS = 24 * 60 * 60 * 1000L
        const val DEADLINE_MARGIN_MS = 30_000L
    }
}
