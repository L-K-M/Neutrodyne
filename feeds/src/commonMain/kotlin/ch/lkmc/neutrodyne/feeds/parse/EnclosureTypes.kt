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
            "m3u8" to "application/x-mpegurl",
        )

    /**
     * The effective media type: lowercase and parameter-stripped, aliases mapped; when the result is not
     * of the audio or video families or `application/x-mpegurl`, the URL path extension decides. Null
     * when neither yields a playable type.
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
        val mapped = declared?.let { aliases[it] ?: it }
        if (mapped != null &&
            (mapped.startsWith("audio/") || mapped.startsWith("video/") || mapped == "application/x-mpegurl")
        ) {
            return mapped
        }

        val path = url.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()
        return extensionTypes[extension] ?: mapped
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
