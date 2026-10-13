// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocaleSwitchTest {
    @Test
    fun `existing title collator follows a runtime locale switch`() =
        preservingLocales {
            Locale.setDefault(Locale.US)
            val collator = PlatformTitleCollator()
            assertTrue(collator.compare("ä", "z") < 0)

            Locale.setDefault(Locale.forLanguageTag("sv-SE"))
            assertTrue(Collator.getInstance().compare("ä", "z") > 0)
            assertTrue(collator.compare("ä", "z") > 0)

            Locale.setDefault(Locale.US)
            assertTrue(collator.compare("ä", "z") < 0)
        }

    @Test
    fun `date formatter templates follow the current format locale`() =
        preservingLocales {
            Locale.setDefault(Locale.US)
            assertCurrentDateFormats()

            // FORMAT can change independently of the locale used for collation.
            Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE)
            assertCurrentDateFormats()

            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"))
            assertCurrentDateFormats()
        }

    private fun assertCurrentDateFormats() {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val instant = Instant.parse("2026-05-15T12:00:00Z")
        val date = instant.atZone(ZoneId.systemDefault())
        assertEquals(
            DateTimeFormatter.ofPattern("d", locale).format(date),
            DateFormatter.dayOfMonth(instant.toEpochMilli()),
        )
        assertEquals(
            DateTimeFormatter.ofPattern("MMM", locale).format(date),
            DateFormatter.monthShort(instant.toEpochMilli()),
        )
        assertEquals(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date),
            DateFormatter.date(instant.toEpochMilli()),
        )
    }

    private fun preservingLocales(block: () -> Unit) {
        val default = Locale.getDefault()
        val format = Locale.getDefault(Locale.Category.FORMAT)
        val display = Locale.getDefault(Locale.Category.DISPLAY)
        try {
            block()
        } finally {
            Locale.setDefault(default)
            Locale.setDefault(Locale.Category.FORMAT, format)
            Locale.setDefault(Locale.Category.DISPLAY, display)
        }
    }
}
