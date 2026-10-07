// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.add

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.fetch.RedirectHop
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One cached preview (03 Preview and dedupe): never persisted; credentials die with the entry. */
internal class PreviewEntry(
    val previewId: String,
    /** The normalised input URL the user typed/pasted (alias `SUBSCRIBE_INPUT`). */
    val inputUrl: String,
    val feed: ParsedFeed,
    val meta: FetchMeta,
    /** Every followed redirect hop of the preview fetch (aliases `REDIRECT`). */
    val hops: List<RedirectHop>,
    /** Pending credentials (typed or userinfo); dropped when the entry evicts. */
    val credentials: BasicCredentials?,
    val createdAt: Long,
)

/**
 * The in-memory preview cache of 03 (at most 2 entries, 15-min TTL, keyed by `previewId`). Parsed
 * feeds are never persisted; an evicted entry drops its pending credentials.
 */
@SingleIn(AppScope::class)
@Inject
internal class PreviewCache(
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, PreviewEntry>(2)

    /**
     * [previewId] is the resolved feed URL (`permanentUrl ?: finalUrl`): `preview(feedUrl)` reuse
     * and the subscribe re-fetch of an evicted entry both key on it (03 Preview and dedupe).
     */
    suspend fun put(
        previewId: String,
        inputUrl: String,
        feed: ParsedFeed,
        meta: FetchMeta,
        hops: List<RedirectHop>,
        credentials: BasicCredentials?,
    ): String =
        mutex.withLock {
            entries[previewId] = PreviewEntry(previewId, inputUrl, feed, meta, hops, credentials, clock.now())
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            previewId
        }

    /** The entry's `previewId`, or null when missing/expired (an expired entry is dropped). */
    suspend fun get(previewId: String): PreviewEntry? =
        mutex.withLock {
            val entry = entries[previewId] ?: return null
            if (clock.now() - entry.createdAt > TTL_MS) {
                entries.remove(previewId)
                return null
            }
            entry
        }

    /** Subscribe's "drop the preview entry" (03 step 4); also clears its credentials reference. */
    suspend fun remove(previewId: String) {
        mutex.withLock { entries.remove(previewId) }
    }

    /**
     * `preview(feedUrl)`'s reuse rule (03): the live entry for an already-fetched URL, if any.
     * Only the entry's identity URLs count — the subscription identity (`previewId`) and the typed
     * `inputUrl`. A differing `meta.finalUrl` reached through a temporary redirect is NOT an
     * identity: reusing on it would subscribe the wrong URL for the target feed.
     */
    suspend fun findByUrl(feedUrl: String): PreviewEntry? =
        mutex.withLock {
            entries.values.firstOrNull {
                (it.previewId == feedUrl || it.inputUrl == feedUrl) &&
                    clock.now() - it.createdAt <= TTL_MS
            }
        }

    private companion object {
        const val MAX_ENTRIES = 2
        const val TTL_MS = 15 * 60 * 1_000L
    }
}
