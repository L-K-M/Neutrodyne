// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

/** A bounded head tokenizer, deliberately limited to the metadata tags Layer A consumes. */
internal object HeadTagScanner {
    data class Tag(
        val name: String,
        val attributes: Map<String, String>,
        val text: String = "",
    )

    fun scan(html: String): List<Tag> {
        val tags = mutableListOf<Tag>()
        var offset = 0
        while (offset < html.length) {
            val start = html.indexOf('<', offset)
            if (start < 0) break
            if (html.startsWith("<!--", start)) {
                offset = html.indexOf("-->", start + 4).takeIf { it >= 0 }?.plus(3) ?: break
                continue
            }
            val end = tagEnd(html, start + 1) ?: break
            val raw = html.substring(start + 1, end)
            val name = raw.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
            if (raw.trim().equals("/head", ignoreCase = true)) break
            offset = end + 1
            if (name == "script" || name == "style") {
                val close = html.indexOf("</$name", offset, ignoreCase = true)
                offset = if (close >= 0) (tagEnd(html, close + 2) ?: break) + 1 else break
                continue
            }
            if (name == "title") {
                val close = html.indexOf("</title", offset, ignoreCase = true)
                if (close < 0) break
                tags += Tag(name, emptyMap(), decode(html.substring(offset, close)))
                offset = (tagEnd(html, close + 2) ?: break) + 1
                continue
            }
            if (name == "link" || name == "meta") tags += Tag(name, attributes(raw, name.length))
        }
        return tags
    }

    private fun tagEnd(
        text: String,
        start: Int,
    ): Int? {
        var quote: Char? = null
        for (i in start until text.length) {
            val char = text[i]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == '>') {
                return i
            }
        }
        return null
    }

    private fun attributes(
        raw: String,
        start: Int,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = start
        while (i < raw.length) {
            while (i < raw.length && (raw[i].isWhitespace() || raw[i] == '/')) i++
            val begin = i
            while (i < raw.length && !raw[i].isWhitespace() && raw[i] != '=' && raw[i] != '/') i++
            if (begin == i) break
            val name = raw.substring(begin, i).lowercase()
            while (i < raw.length && raw[i].isWhitespace()) i++
            var value = ""
            if (i < raw.length && raw[i] == '=') {
                i++
                while (i < raw.length && raw[i].isWhitespace()) i++
                val quote = raw.getOrNull(i)?.takeIf { it == '\'' || it == '"' }
                if (quote != null) i++
                val valueStart = i
                while (i < raw.length && if (quote != null) raw[i] != quote else !raw[i].isWhitespace()) i++
                value = decode(raw.substring(valueStart, i))
                if (quote != null && i < raw.length) i++
            }
            // HTML keeps the first attribute when a malformed tag repeats its name.
            if (name !in result) result[name] = value
        }
        return result
    }

    fun decode(text: String): String =
        ENTITY.replace(text) { match ->
            val token = match.groupValues[1]
            when (token) {
                "amp" -> {
                    "&"
                }

                "lt" -> {
                    "<"
                }

                "gt" -> {
                    ">"
                }

                "quot" -> {
                    "\""
                }

                "apos", "#39" -> {
                    "'"
                }

                else -> {
                    val point =
                        when {
                            token.startsWith("#x", true) -> token.drop(2).toIntOrNull(16)
                            token.startsWith('#') -> token.drop(1).toIntOrNull()
                            else -> null
                        }
                    when {
                        point == null || point !in 1..0x10FFFF || point in 0xD800..0xDFFF -> {
                            match.value
                        }

                        point <= 0xFFFF -> {
                            point.toChar().toString()
                        }

                        else -> {
                            val pair = point - 0x10000
                            "${(0xD800 + (pair shr 10)).toChar()}${(0xDC00 + (pair and 0x3FF)).toChar()}"
                        }
                    }
                }
            }
        }

    private val ENTITY = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z]+);")
}

/** Incremental ASCII markup boundary detection; script strings and comments cannot end the head. */
internal class HeadBoundary {
    private var tag: StringBuilder? = null
    private var quote: Char? = null
    private var comment = false
    private var raw: String? = null
    private var tail = ""

    fun accept(byte: Byte): Boolean {
        val char = (byte.toInt() and 0xff).toChar()
        tail = (tail + char).takeLast(4)
        if (comment) {
            if (tail.endsWith("-->")) comment = false
            return false
        }
        val active = tag
        if (active == null) {
            if (char == '<') tag = StringBuilder()
            return false
        }
        // In raw text, '<' does not start a nested HTML tag.
        if (raw != null && char == '<') {
            tag = StringBuilder()
            return false
        }
        active.append(char)
        if (active.length == 3 && active.toString() == "!--") {
            comment = true
            tag = null
            return false
        }
        if (raw == null && quote != null) {
            if (char == quote) quote = null
            return false
        }
        if (raw == null && (char == '\'' || char == '"')) {
            quote = char
            return false
        }
        if (char != '>') {
            // Only the name matters once a raw-text candidate cannot be its closing tag.
            if (raw != null && active.length > 16) tag = null
            return false
        }
        val content = active.toString().dropLast(1).trim()
        val name = content.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
        tag = null
        if (raw != null) {
            if (content.equals("/$raw", true)) raw = null
            return false
        }
        if (name in listOf("script", "style", "title")) raw = name
        return content.equals("/head", true)
    }
}
