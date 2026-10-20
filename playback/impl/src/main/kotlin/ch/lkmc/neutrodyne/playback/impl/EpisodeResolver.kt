// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.refusal
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.ResolvedAudio
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeRepresentationKey
import ch.lkmc.neutrodyne.youtube.api.YouTubeResolveException
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/** A pin is deliberately independent of later feed edits and completed downloads. */
internal sealed interface Pin {
    data class Local(
        val uri: Uri,
        val youtube: Boolean = false,
    ) : Pin

    class Remote(
        val originalUrl: String,
        val cacheKey: String,
    ) : Pin {
        @Volatile var finalUrl: String? = null

        @Volatile var totalLength: Long? = null

        // A changed response cannot become acceptable merely because the next request omits length.
        @Volatile var contentChanged = false
    }

    class YouTube(
        val videoId: String,
        val pref: AudioPref,
    ) : Pin {
        @Volatile var audio: ResolvedAudio? = null
        val key: String? get() = audio?.let { "yt:$videoId:${it.formatId}" }
    }
}

internal class YouTubeFormatChangedException(
    val videoId: String,
    val old: YouTubeRepresentationKey,
    val new: YouTubeRepresentationKey,
) : java.io.IOException("YouTube representation changed")

private fun ResolvedAudio.representation() = YouTubeRepresentationKey(formatId, contentLength, lastModifiedMicros)

@SingleIn(AppScope::class)
internal class EpisodeResolver internal constructor(
    private val index: EpisodeSourceIndex,
    private val localMedia: LocalMediaIndex,
    private val streamingCache: StreamingCache,
    private val streams: YouTubeStreamResolver,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: () -> YouTubeHealth,
    private val clock: Clock,
    private val audioPref: suspend () -> AudioPref,
) : ResolvingDataSource.Resolver {
    @Inject
    constructor(
        index: EpisodeSourceIndex,
        localMedia: LocalMediaIndex,
        streamingCache: StreamingCache,
        streams: YouTubeStreamResolver,
        capabilities: YouTubeCapabilitiesSource,
        health: Provider<YouTubeHealth>,
        clock: Clock,
        prefs: PlaybackPrefs,
        application: Application,
    ) : this(index, localMedia, streamingCache, streams, capabilities, { health() }, clock, {
        prefs.awaitReady()
        val snapshot = prefs.current.value
        AudioPref(
            snapshot.youtubeQuality,
            snapshot.youtubeLevelling,
            preferredLanguage =
                application.resources.configuration.locales[0]
                    .language,
        )
    })

    private class Retries {
        val invalidations = mutableListOf<Long>()
    }

    private class Slot(
        val retries: Retries = Retries(),
        val resetKey: String? = null,
        val pref: AudioPref? = null,
    ) {
        val job = Job()

        @Volatile var pin: Pin? = null

        @Volatile var released = false
    }

    /** Memory-only ownership survives CacheDataSource's internal hole opens; never serialized. */
    private class YouTubeOpen(
        val slot: Slot,
        val original: Any?,
    )

    private val pins = ConcurrentHashMap<Long, Slot>()
    private val creationLock = Any()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "neutrodyne") return dataSpec
        val id = episodeId(dataSpec.uri)
        val slot = synchronized(pins) { pins.getOrPut(id) { Slot() } }
        // Only loaders hold creationLock across IO. Pin release on main never waits for IO.
        // Serial creation also prevents a cancelled generation resetting a newer one's cache key.
        val pin =
            slot.pin ?: synchronized(creationLock) {
                if (slot.released) throw InterruptedIOException("Episode pin released")
                slot.pin ?: createPin(id, slot).also { created ->
                    synchronized(pins) {
                        if (slot.released) {
                            release(created)
                            throw InterruptedIOException("Episode pin released")
                        }
                        slot.pin = created
                    }
                }
            }
        if (pin is Pin.YouTube) {
            return blocking(slot) {
                val audio = resolve(pin, slot)
                preroll(audio)
                currentCoroutineContext().ensureActive()
                dataSpec
                    .buildUpon()
                    .setUri(audio.url)
                    .setKey(pin.key)
                    .setCustomData(YouTubeOpen(slot, dataSpec.customData))
                    .build()
            }
        }
        return when (pin) {
            is Pin.Local -> {
                if (pin.youtube) checkCapabilities()
                dataSpec
                    .buildUpon()
                    .setUri(pin.uri)
                    .setKey(null)
                    .build()
            }

            is Pin.YouTube -> {
                error("Handled above")
            }

            is Pin.Remote -> {
                if (pin.contentChanged) throw ContentChangedException()
                dataSpec
                    .buildUpon()
                    .setUri(pin.finalUrl ?: pin.originalUrl)
                    .setKey(pin.cacheKey)
                    .build()
            }
        }
    }

    private fun createPin(
        id: Long,
        slot: Slot,
    ): Pin {
        val source = index.getOrLoad(id)
        when (val refusal = source.refusal(capabilities.capabilities.value)) {
            is UnplayableReason.YouTubeExternal -> throw YouTubeResolveException(ResolveResult.Unsupported)

            is UnplayableReason.YouTube -> throw YouTubeResolveException(
                ResolveResult.Unavailable(refusal.availability),
            )

            null -> Unit

            else -> throw NoMediaException()
        }
        localMedia.localUriOrNull(id)?.let {
            val uri = it.toUri()
            if (uri.scheme !in LOCAL_SCHEMES) throw NoMediaException()
            return Pin.Local(uri, source.sourceType != SourceType.RSS)
        }
        if (source.sourceType == SourceType.YOUTUBE_CHANNEL) {
            return Pin.YouTube(checkNotNull(source.externalMediaId), blocking(slot) { slot.pref ?: audioPref() })
        }
        if (source.sourceType != SourceType.RSS) throw YouTubeResolveException(ResolveResult.Unsupported)
        val selected = source.audioSource() ?: throw NoMediaException()
        val url = selected.url
        val key = EnclosureFingerprint.cacheKey(id, url, selected.length)
        streamingCache.pinRemote(key)
        return Pin.Remote(url, key)
    }

    internal fun remotePin(cacheKey: String?): Pin.Remote? =
        pins.values.mapNotNull { it.pin as? Pin.Remote }.firstOrNull { it.cacheKey == cacheKey }

    fun needsYouTube(episodeId: Long): Boolean =
        index[episodeId]?.sourceType == SourceType.YOUTUBE_CHANNEL && localMedia.localUriOrNull(episodeId) == null

    fun streamKind(episodeId: Long): StreamKind? =
        when (pins[episodeId]?.pin) {
            is Pin.Local -> StreamKind.LOCAL
            is Pin.Remote -> StreamKind.STREAM
            is Pin.YouTube -> StreamKind.YOUTUBE
            null -> null
        }

    /** Automatic recovery retains the occurrence's invalidation budget. Never performs IO on main. */
    fun repin(
        episodeId: Long,
        change: YouTubeFormatChangedException? = null,
    ) {
        synchronized(pins) {
            val old = pins.remove(episodeId) ?: return
            old.released = true
            old.job.cancel()
            old.pin?.let(::release)
            val reset =
                change?.takeIf { it.old.formatId == it.new.formatId }?.let {
                    "yt:${it.videoId}:${it.old.formatId}"
                }
            pins[episodeId] = Slot(old.retries, reset, (old.pin as? Pin.YouTube)?.pref)
        }
    }

    fun unpin(episodeId: Long) {
        synchronized(pins) {
            pins.remove(episodeId)?.let { slot ->
                slot.released = true
                slot.job.cancel()
                slot.pin?.let(::release)
            }
        }
    }

    fun clearPins() {
        synchronized(pins) {
            pins.values.forEach { slot ->
                slot.released = true
                slot.job.cancel()
                slot.pin?.let(::release)
            }
            pins.clear()
        }
    }

    private fun release(pin: Pin) {
        if (pin is Pin.Remote) streamingCache.unpin(pin.cacheKey)
        if (pin is Pin.YouTube) pin.key?.let(streamingCache::unpin)
    }

    private fun checkCapabilities() {
        if (!capabilities.capabilities.value.inAppPlayback) throw YouTubeResolveException(ResolveResult.Unsupported)
    }

    private fun <T> blocking(
        slot: Slot,
        block: suspend () -> T,
    ): T =
        try {
            runBlocking(slot.job) { block() }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("YouTube load interrupted").also { it.initCause(interrupted) }
        } catch (cancelled: CancellationException) {
            throw InterruptedIOException("Episode pin released").also { it.initCause(cancelled) }
        }

    private suspend fun resolve(
        pin: Pin.YouTube,
        slot: Slot,
    ): ResolvedAudio {
        checkCapabilities()
        val result =
            withTimeoutOrNull(YOUTUBE_RESOLVE_WATCHDOG_MS) {
                streams.resolveAudio(pin.videoId, pin.pref.copy(pinnedItag = pin.audio?.itag))
            } ?: ResolveResult.Transient(java.io.IOException("YouTube resolve timed out"), TransientKind.TIMEOUT)
        val audio = (result as? ResolveResult.Ok)?.audio ?: throw YouTubeResolveException(result)
        if (audio.videoId != pin.videoId) {
            throw YouTubeResolveException(
                ResolveResult.Transient(java.io.IOException("YouTube identity mismatch"), TransientKind.EXTRACTION),
            )
        }
        currentCoroutineContext().ensureActive()
        synchronized(creationLock) {
            if (slot.released) throw InterruptedIOException("Episode pin released")
            val old = pin.audio
            if (old != null && (old.itag != audio.itag || old.representation() != audio.representation())) {
                throw YouTubeFormatChangedException(pin.videoId, old.representation(), audio.representation())
            }
            if (old == null) {
                streamingCache.pinYouTube("yt:${pin.videoId}:${audio.formatId}", audio.contentLength, slot.resetKey)
            }
            synchronized(pins) {
                if (slot.released) {
                    // Release already removed a published pin's reference; compensate only a new one.
                    if (old == null) streamingCache.unpin("yt:${pin.videoId}:${audio.formatId}")
                    throw InterruptedIOException("Episode pin released")
                }
                pin.audio = audio
            }
        }
        return audio
    }

    private suspend fun preroll(audio: ResolvedAudio) {
        val available = audio.availableAtMs ?: return
        val now = clock.now()
        if (available <= now) return
        if (available - now > MAX_PREROLL_MS) {
            throw YouTubeResolveException(ResolveResult.Transient(java.io.IOException("YouTube preroll deferred")))
        }
        delay(available - now)
    }

    /** CacheDataSource may open several holes without re-entering the outer resolver. */
    fun openYouTube(
        spec: DataSpec,
        upstream: HttpDataSource,
    ): Long? {
        val lease =
            spec.customData as? YouTubeOpen
                ?: if (spec.key?.startsWith("yt:") ==
                    true
                ) {
                    throw InterruptedIOException("Episode pin released")
                } else {
                    return null
                }
        val entry = lease.slot
        val pin = entry.pin as? Pin.YouTube ?: throw InterruptedIOException("Episode pin released")
        if (entry.released || pin.key != spec.key) throw InterruptedIOException("Episode pin released")
        return blocking(entry) {
            val audio = resolve(pin, entry)
            preroll(audio)
            runInterruptible {
                try {
                    val length =
                        upstream.open(
                            spec
                                .buildUpon()
                                .setUri(audio.url)
                                .setCustomData(lease.original)
                                .build(),
                        )
                    if (entry.released) throw InterruptedIOException("Episode pin released")
                    length
                } catch (http: HttpDataSource.InvalidResponseCodeException) {
                    if (http.responseCode == 429) {
                        health().reportRateLimited(clock.now())
                        throw YouTubeResolveException(
                            ResolveResult.Transient(
                                java.io.IOException("YouTube media rate limit"),
                                TransientKind.RATE_LIMITED,
                            ),
                        )
                    }
                    if (http.responseCode == 403 || http.responseCode == 410) {
                        val now = clock.now()
                        if ((audio.availableAtMs ?: Long.MIN_VALUE) <= now) {
                            if (http.responseCode == 403 && now >= audio.resolvedAtMs &&
                                now - audio.resolvedAtMs < 120_000
                            ) {
                                health().reportExtraction(ExtractionOutcome.ForbiddenFreshUrl(pin.videoId))
                            }
                            synchronized(entry.retries) {
                                val elapsed = clock.elapsedRealtime()
                                entry.retries.invalidations.removeAll { elapsed - it >= 60_000 }
                                if (entry.retries.invalidations.size < 2) {
                                    entry.retries.invalidations += elapsed
                                    streams.invalidate(pin.videoId)
                                }
                            }
                        }
                    }
                    throw http
                }
            }
        }
    }

    private companion object {
        const val YOUTUBE_RESOLVE_WATCHDOG_MS = 35_000L
        const val MAX_PREROLL_MS = 30_000L
        val LOCAL_SCHEMES = setOf("file", "content")
    }
}
