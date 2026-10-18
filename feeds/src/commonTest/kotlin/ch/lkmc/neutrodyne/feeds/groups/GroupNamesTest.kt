// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupNamesTest {
    @Test fun caseVariantsHaveTheSameKey() {
        assertEquals(valid("tech").nameKey, valid(" Tech ").nameKey)
        assertEquals("Tech", valid(" Tech ").name)
    }

    @Test fun fortyCodePointsAreAcceptedAndFortyOneRejected() {
        assertIs<GroupNames.Validation.Valid>(GroupNames.validate("a".repeat(40)))
        assertEquals(GroupNames.Validation.TooLong(40), GroupNames.validate("a".repeat(41)))
        assertIs<GroupNames.Validation.Valid>(GroupNames.validate("🎧".repeat(40)))
        assertEquals(GroupNames.Validation.TooLong(40), GroupNames.validate("🎧".repeat(41)))
        assertIs<GroupNames.Validation.Valid>(GroupNames.validate("🎧".repeat(20) + "a".repeat(20)))
        assertEquals(GroupNames.Validation.TooLong(40), GroupNames.validate("🎧".repeat(20) + "a".repeat(21)))
    }

    @Test fun emojiSequencesSurviveNormalization() {
        assertEquals("🎧 Commute 👩‍💻 ❤️", valid("🎧 Commute 👩‍💻 ❤️").name)
    }

    @Test fun canonicallyEquivalentNamesCollide() {
        assertEquals(valid("Café"), valid("Cafe\u0301"))
        assertEquals(valid("Café").nameKey, valid("CAFE\u0301").nameKey)
    }

    @Test fun emptyAndWhitespaceNamesAreRejected() {
        for (name in listOf("", " ", "\t\n\r\u00A0\u2007\u202F", "\u202A\u2069")) {
            assertEquals(GroupNames.Validation.Empty, GroupNames.validate(name))
        }
    }

    @Test fun whitespaceAndControlsBecomeSingleSpacesAndBidiControlsDisappear() {
        assertEquals("Tech news today", valid("\t Tech\u0000\u0085\u00A0news\u2028\u2029\u2007\u202Ftoday ").name)
        assertEquals("Tech", valid("T\u202Ae\u202Bc\u202Ch\u202D\u202E\u2066\u2067\u2068\u2069").name)
    }

    @Test fun lengthIsMeasuredAfterNormalizationInCodePoints() {
        assertIs<GroupNames.Validation.Valid>(GroupNames.validate("e\u0301".repeat(40)))
        // A variation selector is a separate code point even though it shares a glyph.
        assertEquals(GroupNames.Validation.TooLong(40), GroupNames.validate("❤️".repeat(21)))
    }

    @Test fun lowercaseUsesTheInvariantLocaleAndFinalNfc() {
        assertEquals("i\u0307", valid("İ").nameKey)
        assertEquals("i", valid("I").nameKey)
    }

    @Test fun truncateCutsTheNormalizedNameAtTheCodePointCap() {
        assertEquals("a".repeat(40), GroupNames.truncate("a".repeat(41))?.name)
        assertEquals("🎧".repeat(40), GroupNames.truncate("🎧".repeat(41))?.name)
        // A surrogate pair never splits: 20 supplementary chars plus 20 ASCII = 40 code points.
        assertEquals(
            "🎧".repeat(20) + "a".repeat(20),
            GroupNames.truncate("🎧".repeat(20) + "a".repeat(21))?.name,
        )
        // The cap applies after normalization, matching validate's own counting.
        assertEquals("a".repeat(40), GroupNames.truncate("  " + "a".repeat(41))?.name)
    }

    @Test fun truncatePassesShortNamesThroughNormalizedAndDropsEmpty() {
        assertEquals("Tech", GroupNames.truncate("  Tech  ")?.name)
        assertEquals(valid("Tech").nameKey, GroupNames.truncate("  Tech  ")?.nameKey)
        assertEquals("a".repeat(40), GroupNames.truncate("a".repeat(40))?.name)
        assertNull(GroupNames.truncate("   "))
    }

    @Test fun oldestFirstIsOnlyASuggestionForTheDocumentedWords() {
        for (name in listOf(
            "FICTION",
            "Audiobook",
            "Audio   book",
            "Drama",
            "Serial",
            "Story",
            "Stories",
            "Novel",
            "Hörbuch",
            "Hörspiel",
        )) {
            assertTrue(GroupNames.suggestsOldestFirst(name), name)
        }
        assertFalse(GroupNames.suggestsOldestFirst("🎧 Tech"))
    }

    private fun valid(name: String): GroupNames.Validation.Valid = assertIs(GroupNames.validate(name))
}
