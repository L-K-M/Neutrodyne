// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.importing

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import okio.Path
import okio.Source
import okio.buffer

/** What [ImportSourceSniffer.sniff] found in a payload (05 Sniffing). */
public sealed interface SniffResult {
    /** An importable format was identified; [format] names it. */
    public data class Recognized(
        val format: SniffedFormat,
    ) : SniffResult

    /** Readable input that is not an importable list; [reason] is the table's tag. */
    public data class Unsupported(
        val reason: String,
    ) : SniffResult

    /** The payload trips an archive guard; [reason] is the guard's tag (`zip_path`, …). */
    public data class Hostile(
        val reason: String,
    ) : SniffResult
}

/**
 * The formats sniffing can recognise (05 Sniffing). Names match `:core:model`'s persisted
 * `ImportFormat` so callers map without a table; [URL_LIST] has no pipeline before M8 and a
 * caller that does not know it yet treats it like any other unmapped format.
 */
public enum class SniffedFormat {
    OPML,
    NEWPIPE_JSON,
    LIBRETUBE_JSON,
    TAKEOUT_CSV,
    NEUTRODYNE_BACKUP,
    URL_LIST,
}

/**
 * Identifies an import payload from its first bytes (05 Sniffing), never by file name or MIME:
 * magic bytes first (`PK\x03\x04` ZIP, `1F 8B` gzip), then at most the first 64 KiB decoded as
 * text — UTF-8 BOM skipped, UTF-16 BOM or a leading `3C 00`/`00 3C` decode the window as
 * UTF-16 (only XML is accepted in UTF-16) — and the first non-whitespace character routes into
 * the JSON, XML or plain-text checks.
 *
 * [archivePath] carries the file's location because [ArchiveReaderFactory] opens paths (ZIP
 * needs random access); it is consulted only when the magic says ZIP. [archives] may be null
 * for callers without a bound factory — ZIP payloads then report `Unsupported("zip")` rather
 * than being opened unsafely.
 */
public object ImportSourceSniffer {
    /** Text sniffing never reads past this many leading bytes (05 Sniffing). */
    private const val SNIFF_BYTES: Long = 64L * 1024
    private const val CSV_SUFFIX = ".csv"
    private const val TAKEOUT_COLUMNS = 3

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val UTF16_LE_LT = byteArrayOf(0x3C, 0x00)
    private val UTF16_BE_LT = byteArrayOf(0x00, 0x3C)

    /** A URL-list line (04): an `http(s)` URL, a `UC…` channel ID or an `@handle`. */
    private val LIST_TOKEN = Regex("^(https?://\\S+|UC[\\w-]{22}|@[\\w.-]+)$")

    private val codec = BackupCodec()

    public fun sniff(
        open: () -> Source,
        archivePath: Path,
        archives: ArchiveReaderFactory? = null,
    ): SniffResult {
        val head = readHead(open)
        if (head.hasPrefix(ZIP_MAGIC)) return sniffZip(archivePath, archives)
        if (head.hasPrefix(GZIP_MAGIC)) return SniffResult.Unsupported("tgz")

        val probe = decodeHead(head)
        val first = probe.text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return SniffResult.Unsupported("unknown")

        return when (probe.text[first]) {
            '<' -> classifyXml(probe.text)
            '{' -> if (probe.utf16) SniffResult.Unsupported("unknown") else classifyJson(probe.text, first)
            else -> if (probe.utf16) SniffResult.Unsupported("unknown") else classifyText(probe.text)
        }
    }

    /**
     * ZIPs are classified by entry names and the manifest's `format`, never by extension
     * (05 Sniffing). [ArchiveCaps.TAKEOUT] tolerates Takeout's many entries; the tighter BACKUP
     * caps are the restore's job, not the sniffer's. Guard failures map hostile → [Hostile],
     * corrupt → `Unsupported("zip")` — a damaged ZIP is no worse than a foreign one.
     */
    private fun sniffZip(
        archivePath: Path,
        archives: ArchiveReaderFactory?,
    ): SniffResult {
        if (archives == null) return SniffResult.Unsupported("zip")
        return try {
            archives.open(archivePath, ArchiveCaps.TAKEOUT).use { reader ->
                val isBackup =
                    BackupCodec.ENTRY_MANIFEST in reader.entryNames && manifestSaysBackup(reader)
                when {
                    isBackup -> {
                        SniffResult.Recognized(SniffedFormat.NEUTRODYNE_BACKUP)
                    }

                    reader.entryNames.any { it.endsWith(CSV_SUFFIX, ignoreCase = true) } -> {
                        SniffResult.Recognized(SniffedFormat.TAKEOUT_CSV)
                    }

                    else -> {
                        SniffResult.Unsupported("zip")
                    }
                }
            }
        } catch (e: ArchiveException) {
            when (e.kind) {
                ArchiveException.Kind.HOSTILE -> SniffResult.Hostile(e.reason)
                ArchiveException.Kind.CORRUPT -> SniffResult.Unsupported("zip")
            }
        }
    }

    /**
     * The manifest probe of the ZIP row. A damaged entry only means the backup row does
     * not apply, so CORRUPT falls through to the `.csv` check; a tripped guard
     * (HOSTILE) rethrows into the outer catch and stays fatal.
     */
    private fun manifestSaysBackup(reader: ArchiveReader): Boolean =
        try {
            reader.open(BackupCodec.ENTRY_MANIFEST).use {
                codec.manifestFormat(it) == BackupCodec.FORMAT
            }
        } catch (e: ArchiveException) {
            if (e.kind == ArchiveException.Kind.HOSTILE) throw e
            false
        }

    private fun classifyXml(text: String): SniffResult {
        val root = firstElement(text)?.substringAfterLast(':')?.lowercase()
        return when {
            root == "opml" -> SniffResult.Recognized(SniffedFormat.OPML)

            root == "rss" || root == "feed" || root == "rdf" -> SniffResult.Unsupported("single_feed")

            // Salvage: any <outline anywhere still routes into the OPML reader, which decides.
            text.contains("<outline", ignoreCase = true) -> SniffResult.Recognized(SniffedFormat.OPML)

            else -> SniffResult.Unsupported("unknown")
        }
    }

    /**
     * JSON is identified by top-level member keys only, found by a structural scan — quoted
     * text is skipped, so a `"service_id"` inside a value never counts. Scanning stops at the
     * end of the 64 KiB window, so a truncated tail only ever means "unseen".
     */
    private fun classifyJson(
        text: String,
        start: Int,
    ): SniffResult {
        // text[start] is the root '{'.
        var newPipe = false
        var libreTube = false
        var i = skipWs(text, start + 1)
        while (i < text.length && text[i] != '}') {
            if (text[i] != '"') break
            val keyEnd = stringEnd(text, i) ?: break
            val key = text.substring(i + 1, keyEnd)
            i = skipWs(text, keyEnd + 1)
            if (i >= text.length || text[i] != ':') break
            i = skipWs(text, i + 1)
            when (key) {
                "subscriptions" -> {
                    if (i < text.length && text[i] == '[' && subscriptionsLookNewPipe(text, i)) {
                        newPipe = true
                    }
                }

                "localSubscriptions", "channelGroups", "groups" -> {
                    libreTube = true
                }

                "format" -> {
                    if (i < text.length && text[i] == '"' && stringValueEquals(text, i, "Piped")) {
                        libreTube = true
                    }
                }
            }
            i = skipValue(text, i) ?: break
            i = skipWs(text, i)
            if (i < text.length && text[i] == ',') {
                i = skipWs(text, i + 1)
            } else {
                break
            }
        }
        return when {
            newPipe -> SniffResult.Recognized(SniffedFormat.NEWPIPE_JSON)
            libreTube -> SniffResult.Recognized(SniffedFormat.LIBRETUBE_JSON)
            else -> SniffResult.Unsupported("json")
        }
    }

    private fun classifyText(text: String): SniffResult =
        when {
            isTakeoutCsv(text) -> SniffResult.Recognized(SniffedFormat.TAKEOUT_CSV)
            isUrlList(text) -> SniffResult.Recognized(SniffedFormat.URL_LIST)
            else -> SniffResult.Unsupported("unknown")
        }

    /** Takeout's `subscriptions.csv`: a 3-column header, then a line starting with `UC` (04). */
    private fun isTakeoutCsv(text: String): Boolean {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) return false
        val header = lines.next()
        if (!lines.hasNext()) return false
        return header.split(',').size == TAKEOUT_COLUMNS && lines.next().startsWith("UC")
    }

    /** ≥ 80% of the non-empty, non-`#` lines are URLs, `UC…` IDs or `@handles` (04). */
    private fun isUrlList(text: String): Boolean {
        val lines =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .toList()
        if (lines.isEmpty()) return false
        val matching = lines.count { LIST_TOKEN.matches(it) }
        return matching * 5 >= lines.size * 4
    }

    private fun readHead(open: () -> Source): ByteArray =
        open().use { source ->
            val buffered = source.buffer()
            buffered.request(SNIFF_BYTES)
            buffered.buffer.readByteArray()
        }

    private fun decodeHead(head: ByteArray): Probe =
        when {
            head.hasPrefix(UTF8_BOM) -> {
                Probe(head.decodeToString(UTF8_BOM.size, head.size), utf16 = false)
            }

            head.hasPrefix(UTF16_LE_BOM) -> {
                Probe(String(head.copyOfRange(UTF16_LE_BOM.size, head.size), Charsets.UTF_16LE), utf16 = true)
            }

            head.hasPrefix(UTF16_BE_BOM) -> {
                Probe(String(head.copyOfRange(UTF16_BE_BOM.size, head.size), Charsets.UTF_16BE), utf16 = true)
            }

            head.hasPrefix(UTF16_LE_LT) -> {
                Probe(String(head, Charsets.UTF_16LE), utf16 = true)
            }

            head.hasPrefix(UTF16_BE_LT) -> {
                Probe(String(head, Charsets.UTF_16BE), utf16 = true)
            }

            else -> {
                Probe(head.decodeToString(), utf16 = false)
            }
        }

    /**
     * The name of the first real element in [text], skipping the `<?xml…?>` prolog, `<!-- -->`
     * comments and `<!DOCTYPE …>` (its `[…]` internal subset skipped whole). Null when no
     * element shows within the window.
     */
    private fun firstElement(text: String): String? {
        var i = 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) return null
            when {
                text.startsWith("<?", lt) -> {
                    val end = text.indexOf("?>", lt + 2)
                    if (end < 0) return null
                    i = end + 2
                }

                text.startsWith("<!--", lt) -> {
                    val end = text.indexOf("-->", lt + 4)
                    if (end < 0) return null
                    i = end + 3
                }

                text.startsWith("<!", lt) -> {
                    var j = lt + 2
                    var end = -1
                    scan@ while (j < text.length) {
                        when (text[j]) {
                            '>' -> {
                                end = j
                                break@scan
                            }

                            '[' -> {
                                val close = text.indexOf(']', j)
                                if (close < 0) return null
                                j = close
                            }
                        }
                        j++
                    }
                    if (end < 0) return null
                    i = end + 1
                }

                else -> {
                    var j = lt + 1
                    while (j < text.length && isNameChar(text[j])) j++
                    return text.substring(lt + 1, j).ifEmpty { null }
                }
            }
        }
        return null
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == ':' || c == '_' || c == '-' || c == '.'

    /**
     * Every object seen inside the `[`-array at [start] must carry `service_id` — the NewPipe
     * shape (04). A window-truncated tail counts as unseen: what was scanned decides.
     */
    private fun subscriptionsLookNewPipe(
        text: String,
        start: Int,
    ): Boolean {
        var objects = 0
        var i = skipWs(text, start + 1)
        while (i < text.length && text[i] != ']') {
            if (text[i] != '{') return false
            val (keys, end) = objectKeys(text, i)
            if ("service_id" !in keys) return false
            objects++
            if (end < 0) break
            i = skipWs(text, end)
            if (i < text.length && text[i] == ',') {
                i = skipWs(text, i + 1)
            } else {
                break
            }
        }
        return objects > 0
    }

    /**
     * The member keys of the `{`-object at [start], and the index just past its `}` — or -1
     * when the window ends or the structure derails first (the keys seen still count).
     */
    private fun objectKeys(
        text: String,
        start: Int,
    ): Pair<Set<String>, Int> {
        val keys = mutableSetOf<String>()
        var i = skipWs(text, start + 1)
        while (i < text.length && text[i] != '}') {
            if (text[i] != '"') return keys to -1
            val keyEnd = stringEnd(text, i) ?: return keys to -1
            keys += text.substring(i + 1, keyEnd)
            i = skipWs(text, keyEnd + 1)
            if (i >= text.length || text[i] != ':') return keys to -1
            i = skipValue(text, i + 1) ?: return keys to -1
            i = skipWs(text, i)
            if (i < text.length && text[i] == ',') {
                i = skipWs(text, i + 1)
            } else {
                break
            }
        }
        return keys to (if (i < text.length && text[i] == '}') i + 1 else -1)
    }

    /**
     * The index just past the value starting at [start] — string, object, array, number or
     * literal — or null when the window ends inside it. Strings inside containers are skipped
     * structurally so `]`/`}` in quoted text never ends the value early.
     */
    private fun skipValue(
        text: String,
        start: Int,
    ): Int? {
        var i = skipWs(text, start)
        if (i >= text.length) return null
        when (text[i]) {
            '"' -> {
                return stringEnd(text, i)?.plus(1)
            }

            '{', '[' -> {
                var depth = 0
                while (i < text.length) {
                    when (text[i]) {
                        '{', '[' -> {
                            depth++
                        }

                        '}', ']' -> {
                            if (--depth == 0) return i + 1
                        }

                        '"' -> {
                            val end = stringEnd(text, i) ?: return null
                            i = end
                        }
                    }
                    i++
                }
                return null
            }

            else -> {
                while (i < text.length && text[i] !in ",}] \t\r\n") i++
                return i
            }
        }
    }

    /** The index of the closing `"` of the string starting at [start], or null if truncated. */
    private fun stringEnd(
        text: String,
        start: Int,
    ): Int? {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return null
    }

    private fun stringValueEquals(
        text: String,
        start: Int,
        value: String,
    ): Boolean {
        val end = stringEnd(text, start) ?: return false
        return text.substring(start + 1, end) == value
    }

    private fun skipWs(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private class Probe(
        val text: String,
        val utf16: Boolean,
    )
}
