// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.youtube.api.AudioCandidate
import ch.lkmc.neutrodyne.youtube.api.AudioDelivery
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.AudioStreamSelector
import ch.lkmc.neutrodyne.youtube.api.AudioTrackType
import ch.lkmc.neutrodyne.youtube.api.ResolvedAudio
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import java.net.URI
import java.net.URLDecoder

/** W3 already removes video/muxed formats. No URL is exposed through candidate metadata or logs. */
object YtDlpAudioMapper {
    private class Mapped(
        val wire: YtxFormat,
        val candidate: AudioCandidate,
        val query: Map<String, String>,
    )

    fun map(
        result: YtxResolve,
        expectedVideoId: String,
        pref: AudioPref,
        nowMs: Long,
    ): ResolvedAudio {
        if (!YouTubeIds.VIDEO.matches(expectedVideoId) || result.videoId != expectedVideoId ||
            result.formats.size > 256
        ) {
            invalid()
        }
        val duration = result.duration?.let { milliseconds(it) }
        val mapped = result.formats.mapNotNull(::candidate)
        val selected = AudioStreamSelector.select(mapped.map { it.candidate }, pref) ?: invalid()
        val chosen = mapped.first { it.candidate === selected }
        val query = chosen.query
        val wire = chosen.wire
        val expiry = query["expire"]?.let { seconds(it) } ?: Math.addExact(nowMs, ResolvedUrlCache.MAX_AGE_MS)
        if (expiry <= nowMs) invalid()
        val tracks = mapped.filter { it.candidate.itag == selected.itag }.map { it.candidate.audioTrackId }.distinct()
        val formatId =
            buildString {
                append(selected.itag)
                if (selected.isDrc) append("-drc")
                if (tracks.size > 1) {
                    // A bare '~' is reserved for absent track metadata. Typed IDs are nonempty.
                    append('~')
                    append(selected.audioTrackId.orEmpty())
                }
            }
        return ResolvedAudio(
            videoId = expectedVideoId,
            url = wire.url,
            itag = selected.itag,
            formatId = formatId,
            mimeType = selected.mimeType,
            codecs = selected.codecs,
            averageBitrate = selected.averageBitrate,
            contentLength = query["clen"]?.let { positiveLong(it) } ?: wire.filesize?.also { if (it <= 0) invalid() },
            durationMs = duration,
            expiresAtMs = expiry,
            lastModifiedMicros = query["lmt"]?.let { positiveLong(it) },
            isDrc = selected.isDrc,
            audioTrackId = selected.audioTrackId,
            trackLabel = wire.formatNote?.take(256),
            ipFamily = query["ip"]?.let(::ipFamily),
            availableAtMs = wire.availableAt?.let(::milliseconds),
            resolvedAtMs = nowMs,
        )
    }

    private fun candidate(wire: YtxFormat): Mapped? {
        if (wire.protocol != "http" && wire.protocol != "https") return null
        val uri =
            try {
                URI(wire.url)
            } catch (_: Exception) {
                invalid()
            }
        if (uri.scheme != wire.protocol || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
            uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535) ||
            wire.url.any { it.isISOControl() }
        ) {
            invalid()
        }
        val query = parameters(uri.rawQuery ?: "")
        val itag = query["itag"]?.toIntOrNull()?.takeIf { it > 0 } ?: invalid()
        val wireItag = wire.formatId.takeWhile { it.isDigit() }.toIntOrNull() ?: invalid()
        if (itag != wireItag || wire.formatId.length > 128) invalid()
        // process=False in the pinned extractor emits only these HTTP audio identities. A new
        // suffix without a known byte identity must fail closed, rather than alias an existing pin.
        if (wire.formatId != "$itag" && wire.formatId != "$itag-drc") invalid()
        val codec =
            wire.acodec?.takeIf {
                it.length <= 128 && (it.matches(Regex("mp4a(?:[.][A-Za-z0-9]+)*")) || it == "opus" || it == "vorbis")
            }
                ?: return null
        val mime =
            query["mime"]?.substringBefore(';')
                ?: when (wire.ext) {
                    "m4a", "mp4" -> "audio/mp4"
                    "webm" -> "audio/webm"
                    else -> return null
                }
        if (mime != "audio/mp4" && mime != "audio/webm") return null
        val tags = parameters(wire.xtags ?: query["xtags"] ?: "", ':')
        val language =
            (tags["lang"] ?: wire.language)?.also {
                if (!it.matches(
                        Regex("[A-Za-z0-9_-]{1,40}"),
                    )
                ) {
                    invalid()
                }
            }
        val content = tags["acont"]?.also { if (!it.matches(Regex("[a-z-]{1,32}"))) invalid() }
        val track =
            when (content) {
                "original" -> {
                    AudioTrackType.ORIGINAL
                }

                "dubbed", "dubbed-auto" -> {
                    AudioTrackType.DUBBED
                }

                "descriptive" -> {
                    AudioTrackType.DESCRIPTIVE
                }

                "secondary" -> {
                    AudioTrackType.SECONDARY
                }

                else -> {
                    when (wire.languagePreference) {
                        10 -> AudioTrackType.ORIGINAL
                        5 -> AudioTrackType.DEFAULT
                        -10 -> AudioTrackType.DESCRIPTIVE
                        else -> null
                    }
                }
            }
        val kbps = wire.abr ?: wire.tbr
        val bitrate =
            kbps?.let {
                if (!it.isFinite() || it < 0 ||
                    it > Int.MAX_VALUE / 1000.0
                ) {
                    invalid()
                }
                (it * 1000).toInt()
            }
        return Mapped(
            wire,
            AudioCandidate(
                itag,
                mime,
                codec,
                bitrate,
                AudioDelivery.HTTP,
                true,
                track,
                language,
                if (language != null && content != null) "$language.$content" else language,
                wire.formatId
                    .substringAfter(itag.toString())
                    .split('-', '~')
                    .contains("drc"),
            ),
            query,
        )
    }

    private fun parameters(
        text: String,
        separator: Char = '&',
    ): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        try {
            for (part in text.split(separator)) {
                if (separator == ':' && !part.contains('=')) invalid()
                // The Charset overload is newer than the Android API 26 baseline.
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                if (result.put(key, value) != null) invalid()
            }
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        return result
    }

    private fun positiveLong(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 } ?: invalid()

    private fun seconds(value: String): Long =
        positiveLong(value).takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000) ?: invalid()

    private fun milliseconds(value: Double): Long {
        if (!value.isFinite() || value < 0 || value >= Long.MAX_VALUE / 1000.0) invalid()
        return (value * 1000).toLong()
    }

    private fun ipFamily(value: String): IpFamily {
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-fA-F:.]{2,45}"))) invalid()
            // URI validates the numeric IPv6 grammar without performing a DNS lookup.
            try {
                URI("http://[$value]/")
            } catch (_: Exception) {
                invalid()
            }
            return IpFamily.V6
        }
        val parts = value.split('.')
        if (parts.size != 4 ||
            parts.any { part ->
                !part.matches(Regex("[0-9]{1,3}")) || (part.toIntOrNull() ?: 256) !in 0..255
            }
        ) {
            invalid()
        }
        return IpFamily.V4
    }

    private fun invalid(): Nothing = throw YtxCallException("EXTRACTION")
}
