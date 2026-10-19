// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeResolveException
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/** A pin is deliberately independent of later feed edits and completed downloads. */
internal sealed interface Pin {
    data class Local(
        val uri: Uri,
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
}

@SingleIn(AppScope::class)
@Inject
internal class EpisodeResolver(
    private val index: EpisodeSourceIndex,
    private val localMedia: LocalMediaIndex,
    private val streamingCache: StreamingCache,
) : ResolvingDataSource.Resolver {
    private class Slot {
        @Volatile var pin: Pin? = null

        @Volatile var released = false
    }

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
                slot.pin ?: createPin(id).also { created ->
                    synchronized(pins) {
                        if (slot.released) {
                            release(created)
                            throw InterruptedIOException("Episode pin released")
                        }
                        slot.pin = created
                    }
                }
            }
        return when (pin) {
            is Pin.Local -> {
                dataSpec
                    .buildUpon()
                    .setUri(pin.uri)
                    .setKey(null)
                    .build()
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

    private fun createPin(id: Long): Pin {
        val source = index.getOrLoad(id)
        localMedia.localUriOrNull(id)?.let {
            val uri = it.toUri()
            if (uri.scheme !in LOCAL_SCHEMES) throw NoMediaException()
            return Pin.Local(uri)
        }
        // 04 delivers stream extraction in M9a. Never fall through to a YouTube watch/enclosure URL.
        if (source.sourceType != SourceType.RSS) throw YouTubeResolveException(ResolveResult.Unsupported)
        val selected = source.audioSource() ?: throw NoMediaException()
        val url = selected.url
        val key = EnclosureFingerprint.cacheKey(id, url, selected.length)
        streamingCache.pinRemote(key)
        return Pin.Remote(url, key)
    }

    internal fun remotePin(cacheKey: String?): Pin.Remote? =
        pins.values.mapNotNull { it.pin as? Pin.Remote }.firstOrNull { it.cacheKey == cacheKey }

    fun streamKind(episodeId: Long): StreamKind? =
        when (pins[episodeId]?.pin) {
            is Pin.Local -> StreamKind.LOCAL
            is Pin.Remote -> StreamKind.STREAM
            null -> null
        }

    /** Call after cancelling the removed item's loads; service owns pin lifetime (06). */
    fun unpin(episodeId: Long) {
        synchronized(pins) {
            pins.remove(episodeId)?.let { slot ->
                slot.released = true
                slot.pin?.let(::release)
            }
        }
    }

    fun clearPins() {
        synchronized(pins) {
            pins.values.forEach { slot ->
                slot.released = true
                slot.pin?.let(::release)
            }
            pins.clear()
        }
    }

    private fun release(pin: Pin) {
        if (pin is Pin.Remote) streamingCache.unpin(pin.cacheKey)
    }

    private companion object {
        val LOCAL_SCHEMES = setOf("file", "content")
    }
}
