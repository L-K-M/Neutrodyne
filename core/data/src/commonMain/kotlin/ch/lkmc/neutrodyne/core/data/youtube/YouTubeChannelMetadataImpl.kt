// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataResult
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeThumbnails
import ch.lkmc.neutrodyne.youtube.api.YtRef
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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

/** Shared by detail and adapter; metadata failure never affects the channel's feed health. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class YouTubeChannelMetadataImpl(
    private val db: NeutrodyneDatabase,
    private val resolver: YouTubeChannelResolver,
    private val artwork: ArtworkStore,
    private val clock: Clock,
) : YouTubeChannelMetadata {
    private class Request(
        var reason: ChannelMetadataReason,
    ) {
        val done = CompletableDeferred<ChannelMetadataResult>()
    }

    private val mutex = Mutex()
    private val active = mutableMapOf<Long, Request>()
    private val permits = Semaphore(2)
    private val firstFetches = ArrayDeque<Long>()
    private val staleFetches = ArrayDeque<Long>()

    override suspend fun refresh(
        podcastId: Long,
        reason: ChannelMetadataReason,
    ): ChannelMetadataResult {
        var owner = false
        val request =
            mutex.withLock {
                active[podcastId]?.also {
                    if (reason == ChannelMetadataReason.DETAIL) it.reason = reason
                } ?: Request(reason).also {
                    active[podcastId] = it
                    owner = true
                }
            }
        if (!owner) return request.done.await()
        try {
            val result = permits.withPermit { fetch(podcastId, request) }
            request.done.complete(result)
            return result
        } catch (t: Throwable) {
            request.done.completeExceptionally(t)
            throw t
        } finally {
            withContext(NonCancellable) { mutex.withLock { active.remove(podcastId) } }
        }
    }

    private suspend fun fetch(
        id: Long,
        request: Request,
    ): ChannelMetadataResult {
        val row = db.podcastDao().byId(id) ?: return ChannelMetadataResult.FRESH
        val channelId = row.youtubeChannelId ?: return ChannelMetadataResult.FRESH
        val now = clock.now()
        if (row.channelMetadataAt?.let { now - it < STALE_MS } == true) return ChannelMetadataResult.FRESH
        val allowed =
            mutex.withLock {
                val attempts = if (row.channelMetadataAt == null) firstFetches else staleFetches
                while (attempts.firstOrNull()?.let { now - it >= WINDOW_MS } == true) attempts.removeFirst()
                if (attempts.size >= if (row.channelMetadataAt == null) FIRST_LIMIT else STALE_LIMIT) {
                    false
                } else {
                    attempts.addLast(now)
                    true
                }
            }
        if (!allowed) return ChannelMetadataResult.DEFERRED
        return suspendRunCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                delay(Random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS))
                var depth = mutex.withLock { request.reason.depth() }
                var result = resolver.resolve(YtRef.Channel(channelId), depth)
                // A detail open during AVATAR upgrades the same flight before completing it.
                if (depth == MetadataDepth.AVATAR &&
                    mutex.withLock { request.reason } == ChannelMetadataReason.DETAIL
                ) {
                    depth = MetadataDepth.FULL
                    result = resolver.resolve(YtRef.Channel(channelId), depth)
                }
                when (result) {
                    is ChannelResolution.Resolved -> {
                        // Static identity after a failed page read carries no successful metadata.
                        if (result.avatarUrl == null && result.title == null && result.description == null &&
                            result.bannerUrl == null
                        ) {
                            return@withTimeoutOrNull ChannelMetadataResult.FAILED
                        }
                        val avatar = result.avatarUrl?.let { YouTubeThumbnails.avatar(it, AVATAR_SIZE) }
                        val key = avatar?.let(ArtworkKeys::forUrl)
                        db.podcastDao().applyYouTubeChannelMetadata(
                            id,
                            avatar,
                            key,
                            result.bannerUrl,
                            result.description?.let(::escapeChannelDescription),
                            clock.now(),
                        )
                        if (key != null &&
                            key != row.artworkKey
                        ) {
                            artwork.pin(ArtworkRef(key, avatar, 0), PinReason.SUBSCRIPTION, id)
                        }
                        ChannelMetadataResult.UPDATED
                    }

                    ChannelResolution.NotFound -> {
                        db.podcastDao().applyYouTubeChannelMetadata(id, null, null, null, null, clock.now())
                        ChannelMetadataResult.UPDATED
                    }

                    else -> {
                        ChannelMetadataResult.FAILED
                    }
                }
            } ?: ChannelMetadataResult.FAILED
        }.getOrDefault(ChannelMetadataResult.FAILED)
    }

    private fun ChannelMetadataReason.depth(): MetadataDepth =
        if (this == ChannelMetadataReason.DETAIL) MetadataDepth.FULL else MetadataDepth.AVATAR

    private companion object {
        const val STALE_MS = 30L * 24 * 60 * 60 * 1000
        const val WINDOW_MS = 10L * 60 * 1000
        const val TIMEOUT_MS = 20_000L
        const val FIRST_LIMIT = 100
        const val STALE_LIMIT = 30
        const val AVATAR_SIZE = 900
        const val JITTER_MIN_MS = 500L
        const val JITTER_MAX_MS = 1_501L
    }
}
