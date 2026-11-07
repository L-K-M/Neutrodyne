// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.common.Nfc
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import okio.ByteString.Companion.encodeUtf8

/** Feed-independent union, window and content rules. Updated timestamps and views are absent. */
object YouTubeEntryRules {
    const val WINDOW_SIZE = 15

    fun merge(
        results: List<VariantResult>,
        enabledBits: Int,
    ): MergedChannel {
        require(YouTubeVariantBits.isValid(enabledBits))
        val selected = results.filter { it.bit and enabledBits != 0 }.sortedBy { it.bit }
        val ok = selected.filterIsInstance<VariantResult.Ok>()
        val floor =
            if (selected.any { it is VariantResult.Failed }) {
                Long.MAX_VALUE
            } else {
                ok.maxOfOrNull { result ->
                    if (result.entries.size == WINDOW_SIZE) result.entries.minOf { it.sortDate } else Long.MIN_VALUE
                } ?: Long.MIN_VALUE
            }
        val entries = linkedMapOf<String, MergedEntry>()
        for (result in ok) {
            for (entry in result.entries) {
                val short = isShort(entry.link) || (!result.fallback && result.bit == YouTubeVariantBits.SHORTS)
                val previous = entries[entry.videoId]
                entries[entry.videoId] = previous?.copy(isShort = previous.isShort || short)
                    ?: MergedEntry(entry, short, 0)
            }
        }
        val merged =
            entries.values
                .filter { enabledBits and YouTubeVariantBits.SHORTS != 0 || !it.isShort }
                .sortedWith(
                    compareByDescending<MergedEntry> {
                        it.entry.publishedAtMs ?: Long.MIN_VALUE
                    }.thenBy { it.entry.videoId },
                ).mapIndexed { index, entry -> entry.copy(feedOrder = index) }
        val author = normalizeAuthor(ok.firstOrNull()?.authorName)
        return MergedChannel(merged, author, digest(YouTubeDigestInput(author, merged)), floor)
    }

    private fun normalizeAuthor(author: String?): String? =
        author?.let {
            Nfc
                .normalize(it)
                .trim()
                .replace(WHITESPACE, " ")
                .takeIf(String::isNotEmpty)
        }

    fun digest(input: YouTubeDigestInput): String =
        buildString {
            // Length framing keeps publisher text containing pipes/newlines from colliding.
            appendField(normalizeAuthor(input.authorName))
            for (entry in input.entries) {
                appendField(entry.entry.videoId)
                append(
                    contentLine(
                        entry.entry.title,
                        entry.entry.publishedAtMs,
                        entry.entry.rawPublished,
                        entry.entry.description,
                        entry.isShort,
                    ),
                )
            }
        }.encodeUtf8().sha256().hex()

    /** A separate hash path leaves every RSS content hash unchanged. Shorts is ORed before writes. */
    fun contentHash(
        title: String?,
        publishedAtMs: Long?,
        rawPublished: String?,
        description: String?,
        isShort: Boolean,
    ): Long {
        val bytes = contentLine(title, publishedAtMs, rawPublished, description, isShort).encodeUtf8().sha256()
        var hash = 0L
        repeat(Long.SIZE_BYTES) { hash = (hash shl Byte.SIZE_BITS) or (bytes[it].toLong() and 0xffL) }
        return hash
    }

    fun thumbnail(videoId: String): String {
        require(YouTubeIds.VIDEO.matches(videoId))
        return "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
    }

    private fun contentLine(
        title: String?,
        published: Long?,
        raw: String?,
        description: String?,
        short: Boolean,
    ): String =
        buildString {
            appendField(title)
            appendField(published?.toString() ?: raw)
            appendField(short.toString())
            appendField(
                description
                    .orEmpty()
                    .encodeUtf8()
                    .sha1()
                    .hex(),
            )
        }

    private fun StringBuilder.appendField(value: String?) {
        val text = value.orEmpty()
        append(text.length).append(':').append(text).append('\n')
    }

    private fun isShort(link: String?): Boolean =
        link
            ?.substringAfter("://")
            ?.substringAfter('/', "")
            ?.substringBefore('?')
            ?.startsWith("shorts/") == true

    private val WHITESPACE = Regex("\\s+")
}
