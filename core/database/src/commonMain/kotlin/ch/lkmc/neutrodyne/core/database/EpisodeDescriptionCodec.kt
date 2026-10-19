// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.DescriptionCompleteness

/**
 * What the stored `episode_description` text is — the parser's `descriptionIsHtml` kept through
 * storage (03 Show notes): markup the sanitiser parses, or plain text shown literally (Atom
 * `type="text"`, YouTube, `media:description`).
 */
enum class DescriptionFormat {
    HTML,
    PLAIN_TEXT,
}

/** Immutable decoded notes. Only producer-marked COMPLETE text can authorize complete chapters. */
data class DecodedDescription(
    val text: String,
    val format: DescriptionFormat,
    val completeness: DescriptionCompleteness = DescriptionCompleteness.UNKNOWN,
)

/**
 * The only way to read or write `episode_description.html` (02 episode_description): UTF-8 ≥ 512
 * bytes → `[0x01]`/`[0x03]` + raw DEFLATE (nowrap, level 6); else `[0x00]`/`[0x02]` + UTF-8. The
 * low bit marks compression, the next one the [DescriptionFormat] — `0x00`/`0x01` HTML,
 * `0x02`/`0x03` plain text. Decoding an unknown header byte treats the whole payload as UTF-8
 * HTML (corrupt or pre-codec data), never throws — that clause covers only the
 * legacy/unknown-header fallback. `[0x00]`–`[0x03]` are codec-owned: a corrupt or truncated
 * deflate body, or decoded output past the producer bound (03's 512 Ki text chars per element,
 * so at most 2 MiB of UTF-8), is a corruption signal and fails with `IllegalStateException` — a
 * stored blob can never exhaust the heap or yield partial notes. `encode` enforces the same
 * bound as an `IllegalArgumentException`, so a producer with a custom `ParseLimits` beyond it
 * fails before persistence instead of writing an unreadable blob — a successful encode always
 * round-trips. Known completeness forces the existing compressed representation, with a
 * codec-owned trailer after the DEFLATE end. Old readers ignore the trailer and render the exact
 * same text/format. Unmarked, legacy and unrecognised trailers decode as UNKNOWN; publisher text
 * inside the compressed stream cannot mark completeness. No existing blob is migrated.
 */
expect object EpisodeDescriptionCodec {
    fun encode(
        text: String,
        format: DescriptionFormat,
        completeness: DescriptionCompleteness = DescriptionCompleteness.UNKNOWN,
    ): ByteArray

    fun decode(bytes: ByteArray): DecodedDescription
}

// Outside the DEFLATE stream, never an in-band HTML comment or publisher-controlled string.
private val COMPLETENESS_PREFIX = "\u0000Neutrodyne.description\u0000".encodeToByteArray()

internal fun descriptionCompletenessTrailer(completeness: DescriptionCompleteness): ByteArray =
    when (completeness) {
        DescriptionCompleteness.COMPLETE -> COMPLETENESS_PREFIX + byteArrayOf(0x43)
        DescriptionCompleteness.TRUNCATED -> COMPLETENESS_PREFIX + byteArrayOf(0x54)
        DescriptionCompleteness.UNKNOWN -> byteArrayOf()
    }

internal fun storedDescriptionCompleteness(
    bytes: ByteArray,
    remaining: Int,
): DescriptionCompleteness {
    if (remaining != COMPLETENESS_PREFIX.size + 1) return DescriptionCompleteness.UNKNOWN
    val start = bytes.size - remaining
    if (COMPLETENESS_PREFIX.indices.any { bytes[start + it] != COMPLETENESS_PREFIX[it] }) {
        return DescriptionCompleteness.UNKNOWN
    }
    return when (bytes.last().toInt()) {
        0x43 -> DescriptionCompleteness.COMPLETE
        0x54 -> DescriptionCompleteness.TRUNCATED
        else -> DescriptionCompleteness.UNKNOWN
    }
}
