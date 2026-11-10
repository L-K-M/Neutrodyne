// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

/**
 * The five predefined entities plus numeric references, one left-to-right pass so a decoded
 * `&` never re-decodes. An unterminated or malformed reference stays literal.
 *
 * The value is a [CharSequence] so a test can bill every character the decoder reads: a
 * counting CharSequence makes the `;` window's linear bound observable without a wall
 * clock. `String` callers keep their JDK fast paths (`'&' in value`, `subSequence`,
 * `StringBuilder.append`) — nothing routes around them.
 */
internal fun unescapeEntities(value: CharSequence): String {
    if ('&' !in value) return value.toString()
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        // The `;` search stays inside the entity window: an unbounded indexOf would
        // rescan the tail per `&`, quadratic CPU on a `&`-dense value inside the cap.
        val end = minOf(i + 1 + MAX_ENTITY_CHARS, value.length)
        var semi = i + 1
        while (semi < end && value[semi] != ';') semi++
        if (semi == end) {
            out.append(c)
            i++
            continue
        }
        val decoded = decodeEntity(value.subSequence(i + 1, semi).toString())
        if (decoded == null) {
            out.append(value, i, semi + 1)
        } else {
            out.append(decoded)
        }
        i = semi + 1
    }
    return out.toString()
}

/** Longest spelled-out entity name the unescape scan looks back at (`&…;` is short). */
internal const val MAX_ENTITY_CHARS = 16

private fun decodeEntity(token: String): String? {
    when (token) {
        "amp" -> return "&"
        "lt" -> return "<"
        "gt" -> return ">"
        "quot" -> return "\""
        "apos" -> return "'"
    }
    if (!token.startsWith('#')) return null
    val hex = token.length > 1 && (token[1] == 'x' || token[1] == 'X')
    val digits = token.substring(if (hex) 2 else 1)
    if (digits.isEmpty()) return null
    if (digits.any { it !in '0'..'9' && (!hex || (it !in 'a'..'f' && it !in 'A'..'F')) }) return null

    val codePoint = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
    if (!isXmlChar(codePoint)) return null
    return String(Character.toChars(codePoint))
}

/** A strict parser rejects a reference to anything else, so salvage keeps it literal. */
private fun isXmlChar(codePoint: Int): Boolean =
    codePoint in XML_CHAR_CONTROLS || XML_CHAR_RANGES.any { codePoint in it }

/** XML 1.0 `Char` production (§2.2): tab, LF and CR, then three ranges. */
private val XML_CHAR_CONTROLS = setOf('\t'.code, '\n'.code, '\r'.code)
private val XML_CHAR_RANGES = listOf(0x20..0xD7FF, 0xE000..0xFFFD, 0x10000..0x10FFFF)
