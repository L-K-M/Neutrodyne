// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.text.nfkc
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.ByteString.Companion.encodeUtf8

/**
 * Episode identity-key computation (03 Episode keys and matching helpers; storage format: 02 Identity
 * keys). The algorithm is owned here and runs identically in both apps and on the sync server, so keys
 * never depend on the runtime. Key grammar: `key := [version] kind ":" payload`, version absent for 1.
 */
public object EpisodeKeys {
    /** Key version; a bump is a sync event (02 Key versions). */
    public const val VERSION: Int = 1

    private const val DAY_SUFFIX = "T00:00:00Z"
    private val versionPrefix = Regex("""^(\d+)[gutlh]:""")

    /** The primary key of [e]: `g:` → `u:` → `t:` → `l:` → `h:` (the last always applies). */
    public fun primary(e: ParsedEpisode): String =
        guidKey(e.guid) ?: enclosureKey(e.primaryEnclosure?.url)
            ?: titleDayKey(e.title, e.pubDate) ?: linkKey(e.link)
            ?: headKey(e.title, e.descriptionHtml)

    /** `[u:, t:]` minus the primary, used when the primary repeats within one document. */
    public fun fallbacks(e: ParsedEpisode): List<String> {
        val primary = primary(e)
        return listOfNotNull(enclosureKey(e.primaryEnclosure?.url), titleDayKey(e.title, e.pubDate))
            .filter { it != primary }
    }

    /** Current-version primary first, then the keys of every older supported version (v1: `[primary]`). */
    public fun candidates(e: ParsedEpisode): List<String> = listOf(primary(e))

    /**
     * The key of a stored episode for [version] (restore and sync matching). Only v1 exists today.
     * Invariant: [KeyInput.descriptionHead] must be exactly the head of the same stored description —
     * the first 500 UTF-16 code units, extended by one when the cut splits a surrogate pair —
     * or `h:`-fallback matching diverges between ingest and restore.
     */
    public fun keyFor(
        e: KeyInput,
        version: Int,
    ): String {
        require(version == VERSION) { "unsupported identity-key version $version" }
        return guidKey(e.guid) ?: enclosureKey(e.enclosureUrl) ?: titleDayKey(e.title, e.pubDate)
            ?: linkKey(e.link) ?: headKey(e.title, e.descriptionHead)
    }

    /** The optional numeric version prefix of [key]; absent means 1 (02 grammar). */
    public fun versionOf(key: String): Int =
        versionPrefix
            .find(key)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 1

    private fun guidKey(guid: String?): String? = guid?.trim()?.takeIf { it.isNotEmpty() }?.let { "g:$it" }

    private fun enclosureKey(url: String?): String? = url?.let { UrlNormalizer.forIdentity(it) }?.let { "u:$it" }

    /** sha1hex(`title.trim().lowercase()` + `"|"` + the UTC day of [pubDate] as `yyyy-MM-ddT00:00:00Z`). */
    internal fun titleDayKey(
        title: String?,
        pubDate: Long?,
    ): String? {
        val trimmed = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (pubDate == null) return null
        val dayText =
            kotlin.time.Instant
                .fromEpochMilliseconds(pubDate)
                .toLocalDateTime(TimeZone.UTC)
                .date
                .toString() + DAY_SUFFIX
        return "t:" + (trimmed.lowercase() + "|" + dayText).encodeUtf8().sha1().hex()
    }

    private fun linkKey(link: String?): String? =
        link?.trim()?.takeIf { it.isNotEmpty() }?.let { "l:" + it.encodeUtf8().sha1().hex() }

    private fun headKey(
        title: String?,
        description: String?,
    ): String =
        // Lenient parsers can emit U+001F for &#x1F; despite XML 1.0, so field content alone
        // could forge the boundary. fieldEsc leaves no separator byte in the title; the
        // description is the last field and needs no escaping.
        "h:" +
            (title.orEmpty().fieldEsc() + "\u001F" + description.orEmpty().headUnits())
                .encodeUtf8()
                .sha1()
                .hex()
}

/**
 * The first [DESC_HEAD_UNITS] UTF-16 code units, extended by one when the cut would leave a lone
 * high surrogate: a surrogate half encodes as `?` in UTF-8, so descriptions differing only past
 * the cut would otherwise hash identically. `KeyInput.descriptionHead` must be stored under the
 * same rule (idempotent — applying it to an already-truncated head is a no-op).
 */
private const val DESC_HEAD_UNITS = 500

private fun String.headUnits(): String {
    val head = take(DESC_HEAD_UNITS)
    return if (head.lastOrNull()?.isHighSurrogate() == true && length > head.length) {
        head + this[head.length]
    } else {
        head
    }
}

/**
 * Escapes a hash field so its content can't forge a join boundary: `\` -> `\\`,
 * U+001F -> `\u001F`, U+001E -> `\u001E`. Escaped output contains no separator byte, so
 * every separator in the joined preimage is structural. Lenient parsers can emit either
 * control character for a numeric reference despite XML 1.0.
 */
private fun String.fieldEsc(): String = replace("\\", "\\\\").replace("\u001F", "\\u001F").replace("\u001E", "\\u001E")

/** A stored episode's key inputs (02's columns); the restore and sync paths build this. */
public data class KeyInput(
    val guid: String? = null,
    val enclosureUrl: String? = null,
    val title: String? = null,
    val pubDate: Long? = null,
    val link: String? = null,
    val descriptionHead: String? = null,
)

/**
 * Hash over episode content (03 Episode keys and matching helpers): first 8 bytes (big-endian) of
 * SHA-256. `feedOrder` is excluded, so re-ordering alone causes no content update. `guid` is also
 * excluded because it is an identity column. Ingestion must persist matched GUID changes before
 * consulting this hash, including when a duplicate GUID's fallback key stays unchanged.
 */
public object EpisodeContentHash {
    private const val FIELD_SEPARATOR = "\u001F"
    private const val LIST_SEPARATOR = "\u001E"
    private const val HASH_BYTES = 8

    public fun of(e: ParsedEpisode): Long {
        val fields =
            listOf(
                e.title.orEmpty().fieldEsc(),
                e.pubDate
                    ?.toString()
                    .orEmpty()
                    .fieldEsc(),
                e.rawPubDate.orEmpty().fieldEsc(),
                enclosureFields(e.primaryEnclosure),
                (e.primaryEnclosure?.effectiveType?.startsWith("video/") == true).toString().fieldEsc(),
                e.durationMs
                    ?.toString()
                    .orEmpty()
                    .fieldEsc(),
                e.season
                    ?.toString()
                    .orEmpty()
                    .fieldEsc(),
                e.seasonName.orEmpty().fieldEsc(),
                e.episodeNumber.orEmpty().fieldEsc(),
                e.episodeDisplay.orEmpty().fieldEsc(),
                e.episodeType.orEmpty().fieldEsc(),
                e.explicit
                    ?.toString()
                    .orEmpty()
                    .fieldEsc(),
                e.artwork
                    .firstOrNull()
                    ?.url
                    .orEmpty()
                    .fieldEsc(),
                e.link.orEmpty().fieldEsc(),
                e.chaptersUrl.orEmpty().fieldEsc() + FIELD_SEPARATOR + e.chaptersType.orEmpty().fieldEsc(),
                e.externalMediaId.orEmpty().fieldEsc(),
                e.descriptionHtml
                    .orEmpty()
                    .encodeUtf8()
                    .sha256()
                    .hex(),
                // The interpretation is a stored column too: text→HTML with identical bytes must
                // still flip the hash or the update gate keeps the stale flag (03 Ingestion diff).
                e.descriptionIsHtml.toString().fieldEsc(),
                list(e.transcripts) { listOf(it.url, it.type.orEmpty(), it.language.orEmpty(), it.rel.orEmpty()) },
                list(e.alternateEnclosures) {
                    listOf(
                        it.type.orEmpty(),
                        it.length?.toString().orEmpty(),
                        it.bitrate?.toString().orEmpty(),
                        it.height?.toString().orEmpty(),
                        it.lang.orEmpty(),
                        it.title.orEmpty(),
                        it.rel.orEmpty(),
                        it.codecs.orEmpty(),
                        it.isDefault.toString(),
                        list(it.sources) { s -> listOf(s.uri, s.contentType.orEmpty()) },
                        list(it.integrity) { i -> listOf(i.type, i.value) },
                    )
                },
                list(e.persons.orEmpty()) {
                    listOf(it.name, it.role, it.group, it.img.orEmpty(), it.href.orEmpty())
                },
                list(e.funding) { listOf(it.url, it.title) },
                list(e.inlineChapters) {
                    listOf(it.startMs.toString(), it.title, it.href.orEmpty(), it.image.orEmpty())
                },
            )
        val canonical = fields.joinToString(FIELD_SEPARATOR)
        val bytes = canonical.encodeUtf8().sha256().toByteArray()

        var value = 0L
        for (i in 0 until HASH_BYTES) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return value
    }

    private fun enclosureFields(enclosure: ch.lkmc.neutrodyne.feeds.model.Enclosure?): String =
        if (enclosure == null) {
            ""
        } else {
            listOf(
                enclosure.url,
                enclosure.type.orEmpty(),
                enclosure.length?.toString().orEmpty(),
            ).joinToString(FIELD_SEPARATOR) { it.fieldEsc() }
        }

    private fun <T> list(
        items: List<T>,
        serialize: (T) -> List<String>,
    ): String =
        items.joinToString(LIST_SEPARATOR) {
            serialize(it).joinToString(FIELD_SEPARATOR) { leaf -> leaf.fieldEsc() }
        }
}

/**
 * Normalised title form for fallback matching (03 Episode keys and matching helpers): NFKC, lowercase,
 * quotes and dashes folded, spaces collapsed.
 */
public object TitleMatch {
    private val quotesAndApostrophes =
        mapOf(
            '\u2018' to '\'',
            '\u2019' to '\'',
            '\u201A' to '\'',
            '\u201B' to '\'',
            '\u2032' to '\'',
            '\u201C' to '"',
            '\u201D' to '"',
            '\u201E' to '"',
            '\u2033' to '"',
        )
    private val dashes =
        setOf('\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212', '\uFE58', '\uFE63', '\uFF0D')
    private val foldedDash = '-'

    public fun normalise(title: String): String {
        val normalised = title.nfkc()
        val folded = StringBuilder(normalised.length)
        for (c in normalised) {
            when {
                c in quotesAndApostrophes -> folded.append(quotesAndApostrophes[c])
                c in dashes -> folded.append(foldedDash)
                else -> folded.append(c)
            }
        }
        return folded
            .toString()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
