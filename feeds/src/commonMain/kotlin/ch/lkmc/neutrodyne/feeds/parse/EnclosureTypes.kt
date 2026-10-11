// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

import ch.lkmc.neutrodyne.feeds.model.Enclosure

/**
 * MIME-type normalisation and media acceptance for enclosures (03 Enclosure types and media acceptance).
 */
public object EnclosureTypes {
    /** Alias table applied after lowercasing and stripping parameters. */
    private val aliases: Map<String, String> =
        mapOf(
            "audio/mp3" to "audio/mpeg",
            "audio/x-mp3" to "audio/mpeg",
            "audio/mpeg3" to "audio/mpeg",
            "audio/x-mpeg" to "audio/mpeg",
            "audio/x-m4a" to "audio/mp4",
            "audio/m4a" to "audio/mp4",
            "audio/x-m4b" to "audio/mp4",
            "audio/x-aac" to "audio/aac",
            "application/ogg" to "audio/ogg",
            "audio/x-wav" to "audio/wav",
            "video/x-m4v" to "video/mp4",
            // "application.x-mpegurl" (sic) is seen in the Podcasting 2.0 reference feed.
            "application.x-mpegurl" to "application/x-mpegurl",
            "application/vnd.apple.mpegurl" to "application/x-mpegurl",
            // Legacy m3u MIME types are playlists (HLS fallback), not playable audio.
            "audio/x-mpegurl" to "application/x-mpegurl",
            "audio/mpegurl" to "application/x-mpegurl",
        )

    /** URL path extension → effective type, used when the declared type is not audio, video or HLS. */
    private val extensionTypes: Map<String, String> =
        mapOf(
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "m4b" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "oga" to "audio/ogg",
            "opus" to "audio/opus",
            "flac" to "audio/flac",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "m4v" to "video/mp4",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "m3u" to "application/x-mpegurl",
            "m3u8" to "application/x-mpegurl",
        )

    /**
     * The effective media type: lowercase and parameter-stripped, aliases mapped; when the result is not
     * of the audio or video families or `application/x-mpegurl`, the URL path extension decides; an
     * unplayable declared type survives when the extension is unknown (it is stored verbatim and is
     * never chosen as the primary enclosure — [primary] re-checks the playable prefixes). Null only
     * when neither a declared type nor a known extension exists.
     */
    public fun effective(
        type: String?,
        url: String,
    ): String? {
        val declared =
            type
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        // A declared type with an empty subtype ("audio/") is garbage: dropping it keeps the
        // prefix checks in [primary] and [isVideo] sound.
        val mapped = declared?.let { aliases[it] ?: it }?.takeUnless { it.endsWith('/') }
        if (mapped != null &&
            (mapped.startsWith("audio/") || mapped.startsWith("video/") || mapped == "application/x-mpegurl")
        ) {
            return mapped
        }

        // Sniff the raw path first: a decoded `?`/`#` can sit inside a real path segment
        // (`ep%3Fa.m4a?token=1`), and cutting there would hide `.m4a`. Decoded fallback covers
        // CDNs that percent-encode the whole query (`ep.m4a%3Ft=1`).
        fun extOf(u: String): String? {
            val path = u.substringBefore('?').substringBefore('#')
            return extensionTypes[path.substringAfterLast('.', "").lowercase()]
        }
        return extOf(url) ?: extOf(percentDecode(url)) ?: mapped
    }

    /** Percent-decodes for extension sniffing (`%3F` → `?`, `%2E` → `.`); bad escapes pass through. */
    private fun percentDecode(raw: String): String {
        if ('%' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val hexChars = if (raw[i] == '%' && i + 2 < raw.length) raw.substring(i + 1, i + 3) else null
            val hex =
                if (hexChars != null && hexChars.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    hexChars.toInt(16)
                } else {
                    null
                }
            if (hex != null) {
                sb.append(hex.toChar())
                i += 3
            } else {
                sb.append(raw[i])
                i++
            }
        }
        return sb.toString()
    }

    /** Whether this effective type is video (03 Enclosure types and media acceptance). */
    public fun isVideo(effectiveType: String?): Boolean = effectiveType?.startsWith("video/") == true

    /**
     * The primary enclosure: the first with an effective type of the audio family; else the first of the
     * video family; else the first `application/x-mpegurl` (HLS-only items are accepted and stored);
     * else none.
     */
    public fun primary(enclosures: List<Enclosure>): Enclosure? =
        enclosures.firstOrNull { it.effectiveType?.startsWith("audio/") == true }
            ?: enclosures.firstOrNull { it.effectiveType?.startsWith("video/") == true }
            ?: enclosures.firstOrNull { it.effectiveType == "application/x-mpegurl" }
}
