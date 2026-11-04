// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.core.WindowEntry
import ch.lkmc.neutrodyne.playback.engine.DesktopSourceResolver
import ch.lkmc.neutrodyne.playback.engine.EngineItem
import ch.lkmc.neutrodyne.playback.engine.ResolvedSource
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Immutable projection inputs keep a playing enclosure independent of subsequent feed edits. */
class DesktopEpisodeSourceResolver(
    private val localMedia: LocalMediaIndex,
    private val capabilities: YouTubeCapabilitiesSource,
    private val youtube: YouTubeStreamResolver,
    private val clock: Clock,
) : DesktopSourceResolver {
    private data class Source(
        val media: PlaybackMediaInfo,
        val localUri: String?,
        var pin: ResolvedSource? = null,
    )

    private val sources = ConcurrentHashMap<EngineItem, Source>()
    private val kinds = ConcurrentHashMap<Long, StreamKind>()

    internal fun project(entry: WindowEntry): EngineItem {
        val media = requireNotNull(entry.media)
        val audio = media.audioSource()
        val fingerprint =
            MessageDigest
                .getInstance("SHA-1")
                .digest("${audio?.first}|${audio?.second ?: -1}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(FINGERPRINT_CHARS)
        val key =
            if (media.sourceType == SourceType.RSS) {
                "ep:${media.episodeId}:$fingerprint"
            } else {
                "yt:${media.externalMediaId}:pending"
            }
        return EngineItem(media.episodeId, key, entry.localUri != null).also {
            sources[it] = Source(media, entry.localUri)
        }
    }

    internal fun retain(items: Collection<EngineItem>) {
        sources.keys.retainAll(items.toSet())
        kinds.keys.retainAll(items.map { it.episodeId }.toSet())
    }

    internal fun streamKind(id: Long): StreamKind? = kinds[id]

    override suspend fun resolve(
        item: EngineItem,
        attempt: Int,
    ): ResolvedSource {
        val source = sources[item] ?: throw IOException("Episode source is no longer projected")
        // Reconnects keep the pin's source kind even if a download completes meanwhile.
        source.pin?.takeUnless { it is ResolvedSource.YouTube }?.let { return it }
        // A file already missing at first open can fall back before any bytes are consumed.
        val local = if (source.pin == null) source.localUri ?: localMedia.localUriOrNull(item.episodeId) else null
        local?.let { uri ->
            val path = localPath(uri)
            if (Files.isRegularFile(path)) {
                kinds[item.episodeId] = StreamKind.LOCAL
                return ResolvedSource.File(path).also { source.pin = it }
            }
        }
        val media = source.media
        if (media.sourceType == SourceType.RSS) {
            val audio = media.audioSource() ?: throw IOException("Episode has no usable enclosure")
            kinds[item.episodeId] = StreamKind.STREAM
            return ResolvedSource.Http(audio.first, emptyMap(), item.cacheKey).also { source.pin = it }
        }
        if (!capabilities.capabilities.value.inAppPlayback) throw IOException("YouTube playback is unavailable")
        val videoId = media.externalMediaId ?: throw IOException("Episode has no video ID")
        if (attempt > 0) youtube.invalidate(videoId)
        val result = youtube.resolveAudio(videoId, AudioPref())
        val audio = (result as? ResolveResult.Ok)?.audio ?: throw IOException("YouTube audio resolution failed")
        val wait = ((audio.availableAtMs ?: 0) - clock.now()).coerceAtLeast(0)
        if (wait > MAX_PREROLL_MS) throw IOException("YouTube audio is not available yet")
        delay(wait)
        kinds[item.episodeId] = StreamKind.YOUTUBE
        return ResolvedSource
            .YouTube(
                audio.url,
                audio.formatId,
                "yt:$videoId:${audio.formatId}",
                audio.contentLength,
                audio.availableAtMs,
            ).also { source.pin = it }
    }

    private fun localPath(value: String): Path {
        val uri = URI(value)
        require(uri.scheme == "file") { "Desktop downloads require a file URI" }
        return Path.of(uri)
    }

    private fun PlaybackMediaInfo.audioSource(): Pair<String, Long?>? {
        if (isVideo) {
            audioAlternate?.takeIf { it.type.startsWith("audio/", ignoreCase = true) && usableUrl(it.url) }?.let {
                return it.url to it.length
            }
        }
        return enclosureUrl?.takeIf(::usableUrl)?.let { it to enclosureLength }
    }

    private fun usableUrl(value: String): Boolean =
        try {
            val uri = URI(value)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                (uri.port == -1 || uri.port in 1..MAX_PORT)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.net.URISyntaxException) {
            false
        }

    private companion object {
        const val FINGERPRINT_CHARS = 16
        const val MAX_PREROLL_MS = 30_000L
        const val MAX_PORT = 65_535
    }
}
