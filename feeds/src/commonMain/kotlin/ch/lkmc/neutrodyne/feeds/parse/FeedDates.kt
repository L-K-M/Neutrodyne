// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.DateTimeComponents.Formats
import kotlinx.datetime.format.parse
import kotlinx.datetime.toInstant

/**
 * Date parsing for feed dates, shared by the apps and the sync server (03 Dates). No JVM date/time
 * API: a hand-written tokenizer for the RFC 822 family plus kotlinx-datetime for ISO forms.
 *
 * The tokenizer (steps 1–4) runs first; ISO fallbacks (step 5) follow in order; step 6 yields null and
 * the caller records an `UNKNOWN_DATE` warning.
 */
public object FeedDates {
    private const val MAX_MINUTE_OR_SECOND = 59

    // RFC 5322 §4.3 obsolete two-digit years: 00–49 → 20xx, 50–99 → 19xx.
    private const val TWO_DIGIT_YEAR_CENTURY_SPLIT = 50
    private const val TWO_DIGIT_YEAR_RECENT_PREFIX = 2000
    private const val TWO_DIGIT_YEAR_OLD_PREFIX = 1900

    private val weekdayPrefix = Regex("""^\p{L}{2,}\.?,?\s+""")

    /** Localised month abbreviations → English (German, French, Spanish, Italian, Dutch, Portuguese; heuristic). */
    private val localisedMonths: Map<String, String> =
        buildMap {
            val tables =
                listOf(
                    // German
                    listOf("jan", "feb", "mär", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "dez"),
                    // French
                    listOf(
                        "janv",
                        "févr",
                        "mars",
                        "avr",
                        "mai",
                        "juin",
                        "juil",
                        "août",
                        "sept",
                        "oct",
                        "nov",
                        "déc",
                    ),
                    // Spanish
                    listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic"),
                    // Italian
                    listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic"),
                    // Dutch
                    listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"),
                    // Portuguese
                    listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"),
                )
            val english = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            for (table in tables) {
                for (i in table.indices) putIfAbsent(table[i].lowercase(), english[i])
            }
        }

    /** Trailing named zones → numeric offsets (03 Dates step 3). */
    private val namedZones: Map<String, String> =
        mapOf(
            "ut" to "+0000",
            "utc" to "+0000",
            "gmt" to "+0000",
            "z" to "+0000",
            "est" to "-0500",
            "edt" to "-0400",
            "cst" to "-0600",
            "cdt" to "-0500",
            "mst" to "-0700",
            "mdt" to "-0600",
            "pst" to "-0800",
            "pdt" to "-0700",
        )

    private val englishMonths: Map<String, Int> =
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .mapIndexed { i, m -> m to i + 1 }
            .toMap()

    /**
     * `d MMM yyyy|yy H:mm[:ss[.fff]] [±HHMM|±HH:MM]`, case-insensitive; the leading weekday is dropped
     * first and localised months and named zones are normalised (03 Dates steps 1–4). A trailing dot on
     * abbreviated months ("janv.", "oct.") is optional.
     */
    private val datePart =
        """^(\d{1,2})\s+([A-Za-zÀ-ſ]+)\.?\s+(\d{2}|\d{4})\s+(\d{1,2}):(\d{1,2})""" +
            """(?::(\d{1,2})(?:[.,](\d{1,9}))?)?"""
    private val zonePart = """(?:\s*([+-]\d{2}:?\d{2}))?$"""
    private val rfc822 = Regex(datePart + zonePart)

    /** An ISO offset without a colon (`+0200`) gets one so kotlinx-datetime's format accepts it. */
    private val offsetWithoutColon = Regex("""([+-]\d{2})(\d{2})$""")

    /** A space before a trailing ISO offset ("…12:34:56 +02:00") is removed for the ISO fallback. */
    private val spaceBeforeOffset = Regex("""\s+([+-]\d{2}:\d{2}|Z)$""")
    private val utcMidnightOffset = UtcOffset.ZERO

    /** Parses a feed date to epoch milliseconds UTC, or null when no form matches (03 Dates). */
    public fun parse(raw: String): Long? {
        var text = raw.trim().replace(Regex("\\s+"), " ")

        // Step 1: drop the weekday, even a wrong or localised one ("Mié,").
        text = weekdayPrefix.replaceFirst(text, "")
        if (text.isEmpty()) return null

        // Step 3: replace a trailing named zone with its numeric offset. Runs before tokenising so the
        // pattern below only ever sees ±HHMM/±HH:MM.
        val lastToken = text.substringAfterLast(' ').lowercase().removeSuffix(".")
        namedZones[lastToken]?.let { zone ->
            text = text.substringBeforeLast(' ') + " " + zone
        }

        // Step 4: the RFC 822 tokenizer.
        rfc822.find(text)?.let { match -> return rfc822ToEpochMs(match) }

        // Step 5: ISO fallbacks, most specific first.
        val iso =
            text
                .replace('t', 'T')
                .replace('z', 'Z')
        val isoVariants =
            listOf(
                iso,
                iso.replaceFirst(' ', 'T'),
                iso.replaceFirst(' ', 'T').replace(spaceBeforeOffset, "$1"),
            )
        for (variant in isoVariants) {
            offsetWithoutColon.find(variant)?.let { m ->
                val fixed = variant.replaceRange(m.range, "${m.groupValues[1]}:${m.groupValues[2]}")
                isoWithOffset(fixed)?.let { return it }
            }
            isoWithOffset(variant)?.let { return it }
        }

        val localDateTime = runCatching { LocalDateTime.parse(iso.replaceFirst(' ', 'T')) }.getOrNull()
        if (localDateTime != null) {
            return localDateTime.toInstant(utcMidnightOffset).toEpochMilliseconds()
        }

        val localDate = runCatching { LocalDate.parse(iso.substringBefore(' ')) }.getOrNull()
        if (localDate != null) {
            val midnight =
                LocalDateTime(localDate.year, localDate.monthNumber, localDate.dayOfMonth, 0, 0, 0, 0)
            return midnight.toInstant(utcMidnightOffset).toEpochMilliseconds()
        }

        // Step 6: unparsable.
        return null
    }

    /** Applies step 2 (localised months) and converts one tokenizer match to epoch milliseconds. */
    private fun rfc822ToEpochMs(match: MatchResult): Long? {
        val groups = match.groupValues
        val day = groups[1].toIntOrNull() ?: return null

        val monthToken = groups[2].lowercase().removeSuffix(".")
        val month = englishMonths[monthToken] ?: localisedMonths[monthToken]?.let { englishMonths[it.lowercase()] }
        if (month == null) return null

        val rawYear = groups[3]
        val year =
            if (rawYear.length <= 2) {
                val twoDigit = rawYear.toIntOrNull() ?: return null
                if (twoDigit < TWO_DIGIT_YEAR_CENTURY_SPLIT) {
                    TWO_DIGIT_YEAR_RECENT_PREFIX + twoDigit
                } else {
                    TWO_DIGIT_YEAR_OLD_PREFIX + twoDigit
                }
            } else {
                rawYear.toIntOrNull() ?: return null
            }

        val hour = groups[4].toIntOrNull() ?: return null
        val minute = groups[5].toIntOrNull() ?: return null
        val second = groups[6].ifEmpty { "0" }.toIntOrNull() ?: return null
        val millis =
            groups[7].takeIf { it.isNotEmpty() }?.let { fraction ->
                (fraction + "00").substring(0, 3).toIntOrNull() ?: return null
            } ?: 0
        if (hour !in 0..23 || minute !in 0..MAX_MINUTE_OR_SECOND || second !in 0..MAX_MINUTE_OR_SECOND) return null

        // Day 1–31 validated against the real month length.
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return null

        val offset =
            groups[8].takeIf { it.isNotEmpty() }?.let { raw ->
                offsetOf(raw) ?: return null
            } ?: UtcOffset.ZERO

        val dateTime =
            LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, hour, minute, second, millis * 1_000_000)
        return dateTime.toInstant(offset).toEpochMilliseconds()
    }

    private fun offsetOf(raw: String): UtcOffset? {
        val sign = if (raw.startsWith("-")) -1 else 1
        val digits = raw.drop(1).removePrefix("+").replace(":", "")
        if (digits.length != 4) return null
        val hours = digits.substring(0, 2).toIntOrNull() ?: return null
        val minutes = digits.substring(2).toIntOrNull() ?: return null
        if (minutes > MAX_MINUTE_OR_SECOND) return null
        return UtcOffset(sign * hours, sign * minutes)
    }

    private fun isoWithOffset(text: String): Long? =
        runCatching {
            val components = DateTimeComponents.parse(text, Formats.ISO_DATE_TIME_OFFSET)
            components.toInstantUsingOffset()
        }.getOrNull()?.toEpochMilliseconds()
}
