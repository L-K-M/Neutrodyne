// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

internal object MediaSniffer {
    const val HEAD_BYTES = 512

    fun youtubeAudio(
        bytes: ByteArray,
        mime: String?,
    ): Boolean =
        when (mime) {
            "audio/mp4" -> {
                bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp"
            }

            "audio/webm" -> {
                val magic = bytes.take(4).map { it.toInt() and 0xff }
                bytes.size >= 4 && magic == listOf(0x1a, 0x45, 0xdf, 0xa3)
            }

            else -> {
                false
            }
        }

    fun classify(
        bytes: ByteArray,
        contentType: String?,
    ): MediaKind {
        fun at(
            offset: Int,
            text: String,
        ): Boolean =
            bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt() == text[it].code }

        val first = bytes.getOrNull(0)?.toInt()?.and(0xff)
        val second = bytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
        if (first == 0xff && second and 0xf6 == 0xf0) return MediaKind.AAC
        if (at(0, "ID3") || (first == 0xff && second and 0xe0 == 0xe0 && second and 0x06 != 0)) return MediaKind.MP3
        if (at(4, "ftyp")) return if (at(8, "M4B ")) MediaKind.M4B else MediaKind.MP4
        if (at(0, "OggS")) return MediaKind.OGG
        if (at(0, "fLaC")) return MediaKind.FLAC
        if (at(0, "RIFF") && at(8, "WAVE")) return MediaKind.WAV
        if (bytes.take(4).map { it.toInt() and 0xff } == listOf(0x1a, 0x45, 0xdf, 0xa3)) return MediaKind.WEBM

        val text = bytes.decodeToString().removePrefix("\uFEFF").trimStart()
        if (text.startsWith("#EXTM3U")) return MediaKind.HLS
        val mime =
            contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
        if (bytes.isEmpty() || text.startsWith('<') ||
            ((text.startsWith('{') || text.startsWith('[')) && (mime.endsWith("/json") || mime.endsWith("+json"))) ||
            mime.startsWith("text/") || mime == "application/xhtml+xml"
        ) {
            return MediaKind.NOT_MEDIA
        }

        return MediaKind.UNKNOWN_BINARY
    }

    fun extension(
        media: MediaKind,
        head: ByteArray,
        mimeType: String?,
        url: String,
        isVideo: Boolean,
    ): String =
        when (media) {
            MediaKind.MP3 -> {
                "mp3"
            }

            MediaKind.M4B -> {
                "m4b"
            }

            MediaKind.MP4 -> {
                if (isVideo || mimeType?.startsWith("video/") == true) "mp4" else "m4a"
            }

            MediaKind.OGG -> {
                if (head.size >= 36 &&
                    head.copyOfRange(28, 36).decodeToString() == "OpusHead"
                ) {
                    "opus"
                } else {
                    "ogg"
                }
            }

            MediaKind.FLAC -> {
                "flac"
            }

            MediaKind.WAV -> {
                "wav"
            }

            MediaKind.WEBM -> {
                "webm"
            }

            MediaKind.AAC -> {
                "aac"
            }

            else -> {
                MIME_EXTENSIONS[mimeType?.substringBefore(';')?.trim()?.lowercase()]
                    ?: url
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringAfterLast('/')
                        .substringAfterLast('.', "")
                        .lowercase()
                        .takeIf { it in URL_EXTENSIONS } ?: "bin"
            }
        }

    private val MIME_EXTENSIONS =
        mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/m4a" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/webm" to "webm",
            "audio/flac" to "flac",
            "audio/wav" to "wav",
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
            "video/webm" to "webm",
        )
    private val URL_EXTENSIONS = MIME_EXTENSIONS.values.toSet() + setOf("m4b", "oga", "bin")
}
