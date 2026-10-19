// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import okio.Source
import okio.buffer

/** A malformed document or a counted safety limit, mapped by the import application service. */
public class ImportParseException(
    public val reason: Reason,
) : Exception(reason.name) {
    public enum class Reason { MALFORMED, TOO_LARGE, TOO_MANY_ITEMS, TOO_DEEP }
}

internal const val JSON_BYTES = 10_000_000L
internal const val JSON_ITEMS = 10_000

// Match YouTubeIds.CHANNEL's padding bits without a feeds → youtube:api dependency.
internal val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{21}[AQgw]$")

internal fun readImportText(
    source: Source,
    maxBytes: Long,
): String =
    source.buffer().use {
        if (it.request(maxBytes + 1)) throw ImportParseException(ImportParseException.Reason.TOO_LARGE)
        try {
            it.readByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            throw ImportParseException(ImportParseException.Reason.MALFORMED)
        }
    }

/** Bound nesting before invoking the JSON codec, including ignored foreign backup fields. */
internal fun boundedJson(source: Source): String {
    val text = readImportText(source, JSON_BYTES)
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                quoted = false
            }
        } else {
            when (char) {
                '"' -> {
                    quoted = true
                }

                '{', '[' -> {
                    if (++depth > MAX_JSON_DEPTH) {
                        throw ImportParseException(ImportParseException.Reason.TOO_DEEP)
                    }
                }

                '}', ']' -> {
                    depth--
                }
            }
        }
    }
    return text
}

internal fun checkItemCount(
    count: Int,
    cap: Int = JSON_ITEMS,
) {
    if (count > cap) throw ImportParseException(ImportParseException.Reason.TOO_MANY_ITEMS)
}

private const val MAX_JSON_DEPTH = 64
