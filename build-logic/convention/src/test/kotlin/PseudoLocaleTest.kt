// SPDX-License-Identifier: Unlicense
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PseudoLocaleTest {
    @Test fun expansionPreservesFormatArgumentsEscapesAndUrls() {
        val source = "Download %1\$d episodes: %2\$s\\nhttps://example.com/feed?q=a"
        val result = pseudoLocalize(source, PseudoLocale.EXPANDED)
        assertTrue(result.startsWith("[Đöŵñľöàđ"))
        for (literal in listOf("%1\$d", "%2\$s", "\\n", "https://example.com/feed?q=a")) assertTrue(literal in result)
        assertTrue(result.length > source.length)
        assertEquals(result, pseudoLocalize(source, PseudoLocale.EXPANDED))
    }

    @Test fun rtlUsesPairedDirectionControlsWithoutChangingArguments() {
        val result = pseudoLocalize("Play %1\$s", PseudoLocale.RTL)
        assertEquals('\u202e', result.first())
        assertEquals('\u202c', result.last())
        assertTrue("%1\$s" in result)
    }
}
