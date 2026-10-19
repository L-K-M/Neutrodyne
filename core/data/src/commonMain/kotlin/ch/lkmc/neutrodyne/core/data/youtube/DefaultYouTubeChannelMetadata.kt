// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataResult
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeThumbnails
import ch.lkmc.neutrodyne.youtube.api.YtRef
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Shared by refresh and detail: one budget and coalescing owner, never one per caller. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class DefaultYouTubeChannelMetadata(
    private val db: NeutrodyneDatabase,
    private val resolver: YouTubeChannelResolver,
    private val artwork: ArtworkStore,
    private val clock: Clock,
    private val random: Random,
) : YouTubeChannelMetadata {
    private val lock = Mutex()
    private val permits = Semaphore(CONCURRENCY)
    private val inFlight = mutableMapOf<Long, Pending>()
    private val newLookups = ArrayDeque<Long>()
    private val staleLookups = ArrayDeque<Long>()

    override suspend fun refresh(
        podcastId: Long,
        reason: ChannelMetadataReason,
    ): ChannelMetadataResult {
        val pending = Pending(reason)
        val previous =
            lock.withLock {
                inFlight[podcastId]?.also { if (reason == ChannelMetadataReason.DETAIL) it.reason = reason }
                    ?: run {
                        inFlight[podcastId] = pending
                        null
                    }
            }
        if (previous != null) return previous.result.await()
        try {
            val result = refreshOwned(podcastId, pending)
            pending.result.complete(result)
            return result
        } catch (e: CancellationException) {
            pending.result.cancel(e)
            throw e
        } catch (e: Exception) {
            pending.result.complete(ChannelMetadataResult.FAILED)
            return ChannelMetadataResult.FAILED
        } finally {
            withContext(NonCancellable) { lock.withLock { inFlight.remove(podcastId) } }
        }
    }

    private suspend fun refreshOwned(
        id: Long,
        pending: Pending,
    ): ChannelMetadataResult {
        val row = db.podcastDao().byId(id) ?: return ChannelMetadataResult.FAILED
        if (row.sourceType != SourceType.YOUTUBE_CHANNEL) return ChannelMetadataResult.FAILED
        val channelId = row.youtubeChannelId ?: return ChannelMetadataResult.FAILED
        val now = clock.now()
        if (row.channelMetadataAt?.let { it > now - STALE_MS } == true) return ChannelMetadataResult.FRESH
        val admitted =
            lock.withLock {
                val budget = if (row.channelMetadataAt == null) newLookups else staleLookups
                val limit = if (row.channelMetadataAt == null) NEW_LIMIT else STALE_LIMIT
                while (budget.firstOrNull()?.let { it <= now - WINDOW_MS } == true) budget.removeFirst()
                if (budget.size >= limit) {
                    false
                } else {
                    budget.addLast(now)
                    true
                }
            }
        if (!admitted) return ChannelMetadataResult.DEFERRED
        return withTimeoutOrNull(TIMEOUT_MS) {
            permits.withPermit {
                delay(random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS + 1))
                val depth = lock.withLock { pending.depth() }
                var resolved = resolver.resolve(YtRef.Channel(channelId), depth)
                // A detail request arriving during AVATAR lookup still receives a FULL result.
                if (depth != MetadataDepth.FULL && lock.withLock { pending.depth() } == MetadataDepth.FULL) {
                    resolved = resolver.resolve(YtRef.Channel(channelId), MetadataDepth.FULL)
                }
                when (val result = resolved) {
                    is ChannelResolution.Resolved -> {
                        if (result.channelId != channelId) return@withPermit ChannelMetadataResult.FAILED
                        val avatar = result.avatarUrl?.let { YouTubeThumbnails.avatar(it) }
                        if (avatar == null && result.description == null && result.bannerUrl == null) {
                            return@withPermit ChannelMetadataResult.FAILED
                        }
                        apply(id, avatar, result.bannerUrl, result.description?.let(::escapeChannelDescription))
                        ChannelMetadataResult.UPDATED
                    }

                    ChannelResolution.NotFound -> {
                        apply(id, null, null, null)
                        ChannelMetadataResult.UPDATED
                    }

                    else -> {
                        ChannelMetadataResult.FAILED
                    }
                }
            }
        } ?: ChannelMetadataResult.FAILED
    }

    private suspend fun apply(
        id: Long,
        avatar: String?,
        banner: String?,
        description: String?,
    ) {
        val key = avatar?.let(ArtworkKeys::forUrl)
        // The pin belongs to this commit. Cancellation after a successful write must not lose it.
        withContext(NonCancellable) {
            var changed = false
            db.withWriteTransaction {
                val stored = db.podcastDao().byId(id) ?: return@withWriteTransaction
                changed = key != null && key != stored.artworkKey
                db.podcastDao().applyYouTubeChannelMetadata(id, avatar, key, banner, description, clock.now())
            }
            if (changed) artwork.pin(ArtworkRef(requireNotNull(key), avatar, 0), PinReason.SUBSCRIPTION, id)
        }
    }

    private class Pending(
        var reason: ChannelMetadataReason,
    ) {
        val result = CompletableDeferred<ChannelMetadataResult>()

        fun depth() = if (reason == ChannelMetadataReason.DETAIL) MetadataDepth.FULL else MetadataDepth.AVATAR
    }

    private companion object {
        const val CONCURRENCY = 2
        const val NEW_LIMIT = 100
        const val STALE_LIMIT = 30
        const val WINDOW_MS = 10L * 60_000
        const val STALE_MS = 30L * 86_400_000
        const val TIMEOUT_MS = 20_000L
        const val JITTER_MIN_MS = 500L
        const val JITTER_MAX_MS = 1500L
    }
}
