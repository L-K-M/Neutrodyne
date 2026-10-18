// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.parse.HostileEntityDeclaration
import ch.lkmc.neutrodyne.feeds.opml.OpmlDocument
import ch.lkmc.neutrodyne.feeds.opml.OpmlEntry
import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlWarning
import ch.lkmc.neutrodyne.feeds.opml.OpmlWarningCode
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import okio.Source
import okio.buffer
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.Locale

/**
 * The cascade's last pass (05 Sniff and parse step 3): a streaming scanner over the decoded text,
 * read in 64 KiB chunks, that extracts each `<outline …>` tag of at most 64 KiB and parses its
 * attributes with `(\w[\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')` — quoted forms only, exactly as the
 * document spells it — unescaping the five predefined entities and numeric character references.
 *
 * Folders are lost by construction (nothing is nested), `category` survives because it is an
 * attribute, and `nd:` attributes survive the same way. The feed and outline caps, the attribute
 * bounds and [MarkupGapGuard] all apply exactly as in the pull passes; a cap violation is terminal
 * here too. Emits `ParseMode.SALVAGE` with a `SALVAGED` warning.
 *
 * An `<outline` inside a comment, CDATA or a foreign tag's attribute still scans — the document is
 * already beyond the parser's help and a false positive is priced in (Pocket Casts' own importer
 * scans for `xmlUrl=` the same way).
 */
internal class SalvageOpmlScanner(
    private val limits: OpmlLimits,
) {
    fun scan(open: () -> Source): OpmlReadResult {
        val first = attempt(open, charsetOverride = null)
        val firstDoc = first.result as? OpmlReadResult.Ok ?: return first.result
        if (!first.meter.mojibake() || !redecodable(first.charset)) return firstDoc

        val second = attempt(open, charsetOverride = REPARSE_CHARSET)
        val secondDoc = second.result as? OpmlReadResult.Ok ?: return firstDoc
        if (second.meter.replacementChars >= first.meter.replacementChars) return firstDoc
        return secondDoc
    }

    /** One salvage attempt over [open]'s stream, decoded with [charsetOverride] or the sniffed charset. */
    private fun attempt(
        open: () -> Source,
        charsetOverride: Charset?,
    ): ScanOutcome =
        open().buffer().use { source ->
            val charset = charsetOverride ?: sniffedCharset(source)
            val session = ScanSession()
            try {
                val stream =
                    MarkupGapGuard(
                        LeadingWhitespaceSkipper(source.inputStream()),
                        limits.maxMarkupGapBytes,
                        limits.maxTagAttributes,
                    )
                session.scan(InputStreamReader(stream, charset))
                ScanOutcome(session.result(), session.meter, charset)
            } catch (e: HostileEntityDeclaration) {
                ScanOutcome(OpmlReadResult.Failed(OpmlFailure.ENTITY_DECLARED), charset = charset)
            } catch (e: MarkupBoundExceeded) {
                ScanOutcome(OpmlReadResult.Failed(OpmlFailure.TAG_TOO_LONG), charset = charset)
            } catch (e: OpmlCapExceeded) {
                ScanOutcome(OpmlReadResult.Failed(e.failure), charset = charset)
            } catch (e: StackOverflowError) {
                ScanOutcome(OpmlReadResult.Failed(OpmlFailure.TOO_DEEP), charset = charset)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                ScanOutcome(OpmlReadResult.Failed(OpmlFailure.MALFORMED), charset = charset)
            }
        }

    private class ScanOutcome(
        val result: OpmlReadResult,
        val meter: ReplacementMeter = ReplacementMeter(),
        val charset: Charset,
    )

    /**
     * One scan over the decoded stream: the sliding window, the tag machinery and the counters the
     * caps feed. The window drops consumed text only between tags — inside a tag scan fills append
     * without compacting, so positions stay valid — and is bounded by one chunk, one compaction
     * threshold of dead text and one capped tag.
     */
    private inner class ScanSession {
        val entries = ArrayList<OpmlEntry>()
        var neutrodyne = false
        var rootSeen = false
        var totalOutlines = 0
        var feedCount = 0
        var ignoredOutlines = 0
        var ignoredEpisodes = 0
        var ignoredLists = 0
        var droppedAttr = 0
        val meter = ReplacementMeter()

        private val window = StringBuilder()
        private var pos = 0
        private var eof = false
        private val chunk = CharArray(CHUNK_CHARS)

        fun scan(reader: Reader) {
            while (true) {
                // The cursor marks consumed text; dropping it here is safe because every index a
                // tag scan holds is created and used below, past the cursor.
                if (pos > COMPACT_AT) {
                    window.delete(0, pos)
                    pos = 0
                }
                val lt = window.indexOf('<', pos)
                if (lt < 0) {
                    window.setLength(0)
                    pos = 0
                    if (!fill(reader)) return
                    continue
                }
                pos = lt
                if (lt + 1 >= window.length && !fill(reader)) return
                val afterLt = window[lt + 1]
                if (!isNameStart(afterLt)) {
                    pos = lt + 1
                    continue
                }

                var nameEnd = lt + 1
                while (nameEnd < window.length && isNameChar(window[nameEnd])) nameEnd++
                if (nameEnd == window.length && !eof) {
                    // The tag name may continue into the next chunk; the '<' stays at pos.
                    fill(reader)
                    continue
                }
                val name = window.substring(lt + 1, nameEnd)

                when {
                    name.equals(OUTLINE, ignoreCase = true) -> {
                        val end = findTagEnd(reader, lt)
                        onOutline(window.substring(nameEnd, if (end >= 0) end else window.length))
                        pos = advance(end)
                    }

                    name.equals(ROOT_ELEMENT, ignoreCase = true) && !rootSeen -> {
                        rootSeen = true
                        val end = findTagEnd(reader, lt)
                        val slice = window.substring(nameEnd, if (end >= 0) end else window.length)
                        neutrodyne = tagAttrs(slice)["xmlns:nd"] == NEUTRODYNE_NS
                        pos = advance(end)
                    }

                    else -> {
                        // Other tags contribute nothing; scanning resumes inside them, so an
                        // `<outline` written into a foreign tag's attribute still surfaces.
                        pos = nameEnd
                    }
                }
            }
        }

        /** End of the tag starting at [tagStart]: the first `>` or `<` outside quoted values, -1 at EOF. */
        private fun findTagEnd(
            reader: Reader,
            tagStart: Int,
        ): Int {
            var i = tagStart + 1
            var quote = '\u0000'
            var awaitingValue = false
            while (true) {
                if (i - tagStart > SALVAGE_TAG_MAX_CHARS) {
                    throw OpmlCapExceeded(
                        OpmlFailure.TAG_TOO_LONG,
                        "salvage tag over $SALVAGE_TAG_MAX_CHARS chars",
                    )
                }
                if (i >= window.length) {
                    if (!fill(reader)) return -1
                    continue
                }
                val c = window[i]
                if (quote == '\u0000') {
                    when (c) {
                        '"', '\'' -> if (awaitingValue) quote = c
                        '>', '<' -> return i
                    }
                    // Match TAG_ATTR: a quote opens a value only after '=' and optional whitespace.
                    if (!c.isWhitespace()) awaitingValue = c == '='
                } else if (c == quote) {
                    quote = '\u0000'
                }
                i++
            }
        }

        /** The scan cursor after a tag: past `>`, on the `<` that closed it, or at EOF's tail. */
        private fun advance(end: Int): Int =
            when {
                end < 0 -> window.length
                window[end] == '>' -> end + 1
                else -> end
            }

        private fun onOutline(content: String) {
            countOutline()
            val attrs = tagAttrs(content)
            if (hasOversizedAttribute(attrs, limits)) {
                droppedAttr++
                return
            }
            when (val kind = classifyOutline(attrs)) {
                OutlineKind.FEED -> {
                    feedCount++
                    if (feedCount > limits.maxFeeds) {
                        throw OpmlCapExceeded(
                            OpmlFailure.TOO_MANY_FEEDS,
                            "over ${limits.maxFeeds} feeds",
                        )
                    }
                    entries += outlineEntry(entries.size, attrs, folderId = null, commented = false)
                }

                OutlineKind.IGNORED_EPISODES -> {
                    ignoredOutlines++
                    ignoredEpisodes++
                }

                OutlineKind.IGNORED_LISTS -> {
                    ignoredOutlines++
                    ignoredLists++
                }

                // A folder tag produces no entry in salvage — its former children are scanned
                // independently — so it counts toward `ignoredOutlines` without a warning bucket.
                OutlineKind.FOLDER -> {
                    ignoredOutlines++
                }
            }
        }

        private fun tagAttrs(content: String): Map<String, String> {
            val map = HashMap<String, String>(16)
            var count = 0
            for (match in TAG_ATTR.findAll(content)) {
                if (++count > limits.maxTagAttributes) {
                    throw OpmlCapExceeded(
                        OpmlFailure.TAG_TOO_LONG,
                        "tag over ${limits.maxTagAttributes} attributes",
                    )
                }
                val name = match.groupValues[1].lowercase(Locale.ROOT)
                val raw = match.groups[3]?.value ?: match.groups[4]?.value ?: continue
                val value = unescapeEntities(raw)
                meter.add(value)
                map.putIfAbsent(name, value)
            }
            return map
        }

        /**
         * The five predefined entities plus numeric references, one left-to-right pass so a decoded
         * `&` never re-decodes. An unterminated or malformed reference stays literal.
         */
        private fun unescapeEntities(value: String): String {
            if ('&' !in value) return value
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
                val decoded = decodeEntity(value.substring(i + 1, semi))
                if (decoded == null) {
                    out.append(value, i, semi + 1)
                } else {
                    out.append(decoded)
                }
                i = semi + 1
            }
            return out.toString()
        }

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
            if (codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
            return String(Character.toChars(codePoint))
        }

        private fun fill(reader: Reader): Boolean {
            if (eof) return false
            val n = reader.read(chunk)
            if (n < 0) {
                eof = true
                return false
            }
            window.append(chunk, 0, n)
            return true
        }

        private fun countOutline() {
            totalOutlines++
            if (totalOutlines > limits.maxOutlines) {
                throw OpmlCapExceeded(
                    OpmlFailure.TOO_MANY_OUTLINES,
                    "over ${limits.maxOutlines} outlines",
                )
            }
        }

        fun result(): OpmlReadResult {
            if (totalOutlines == 0) return OpmlReadResult.Failed(OpmlFailure.NO_OUTLINES)
            return OpmlReadResult.Ok(
                OpmlDocument(
                    entries = entries,
                    folders = emptyList(),
                    headTitle = null,
                    neutrodyne = neutrodyne,
                    mode = ParseMode.SALVAGE,
                    ignoredOutlines = ignoredOutlines,
                    warnings =
                        buildList {
                            add(OpmlWarning(OpmlWarningCode.SALVAGED))
                            if (droppedAttr > 0) {
                                add(OpmlWarning(OpmlWarningCode.ATTRIBUTE_TOO_LONG, droppedAttr))
                            }
                            if (ignoredEpisodes > 0) {
                                add(OpmlWarning(OpmlWarningCode.IGNORED_OUTLINES, ignoredEpisodes))
                            }
                            if (ignoredLists > 0) {
                                add(OpmlWarning(OpmlWarningCode.LINKED_LISTS, ignoredLists))
                            }
                        },
                ),
            )
        }

        private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':' || c.code >= 0x80

        private fun isNameChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.' || c.code >= 0x80
    }

    private companion object {
        const val OUTLINE = "outline"
        const val ROOT_ELEMENT = "opml"
        const val NEUTRODYNE_NS = "urn:neutrodyne:opml:1"

        /** 05 Sniff and parse step 3: decode in 64 KiB chunks, extract tags of at most 64 KiB. */
        const val CHUNK_CHARS = 64 * 1024
        const val SALVAGE_TAG_MAX_CHARS = 64 * 1024

        /** Dead text allowed to linger ahead of the cursor before it is dropped. */
        const val COMPACT_AT = 64 * 1024

        /** Longest spelled-out entity name the unescape scan looks back at (`&…;` is short). */
        const val MAX_ENTITY_CHARS = 16

        val TAG_ATTR = Regex("""(\w[\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')""")
    }
}
