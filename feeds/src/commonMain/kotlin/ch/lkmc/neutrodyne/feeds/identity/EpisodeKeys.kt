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
     * Invariant: [KeyInput.descriptionHead] must be exactly `descriptionHtml.take(500)` of the same
     * stored description, or `h:`-fallback matching diverges between ingest and restore.
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
        // Lenient parsers can emit U+001F for &#x1F; despite XML 1.0, so the separator is
        // doubled inside the title: the first unpaired U+001F is always the boundary and no
        // field content can forge it. The description is last and needs no escaping.
        "h:" +
            (
                title.orEmpty().replace(
                    "\u001F",
                    "\u001F\u001F",
                ) + "\u001F" + description.orEmpty().take(500)
            ).encodeUtf8().sha1().hex()
}

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
 * Content hash over everything ingestion writes for an episode row (03 Episode keys and matching
 * helpers): first 8 bytes (big-endian) of SHA-256. `feedOrder` is excluded, so re-ordering alone causes
 * no write.
 */
public object EpisodeContentHash {
    private const val FIELD_SEPARATOR = "\u001F"
    private const val LIST_SEPARATOR = "\u001E"
    private const val HASH_BYTES = 8

    public fun of(e: ParsedEpisode): Long {
        val fields =
            listOf(
                e.title.orEmpty(),
                e.pubDate?.toString().orEmpty(),
                e.rawPubDate.orEmpty(),
                enclosureFields(e.primaryEnclosure),
                (e.primaryEnclosure?.effectiveType?.startsWith("video/") == true).toString(),
                e.durationMs?.toString().orEmpty(),
                e.season?.toString().orEmpty(),
                e.seasonName.orEmpty(),
                e.episodeNumber.orEmpty(),
                e.episodeDisplay.orEmpty(),
                e.episodeType.orEmpty(),
                e.explicit?.toString().orEmpty(),
                e.artwork
                    .firstOrNull()
                    ?.url
                    .orEmpty(),
                e.link.orEmpty(),
                e.chaptersUrl.orEmpty() + FIELD_SEPARATOR + e.chaptersType.orEmpty(),
                e.externalMediaId.orEmpty(),
                e.descriptionHtml
                    .orEmpty()
                    .encodeUtf8()
                    .sha256()
                    .hex(),
                // The interpretation is a stored column too: text→HTML with identical bytes must
                // still flip the hash or the update gate keeps the stale flag (03 Ingestion diff).
                e.descriptionIsHtml.toString(),
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
            ).joinToString(FIELD_SEPARATOR)
        }

    private fun <T> list(
        items: List<T>,
        serialize: (T) -> List<String>,
    ): String = items.joinToString(LIST_SEPARATOR) { serialize(it).joinToString(FIELD_SEPARATOR) }
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
