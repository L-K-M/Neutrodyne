// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.model.ImportOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeImportOptionsTest {
    @Test fun ordinalOptionsRoundTripAndSurvivePreviewEdits() {
        val options =
            ImportOptions(
                itemYouTubeInputs = mapOf(2 to "@café", 7 to "https://youtube.com/c/show"),
                itemYouTubeVariants =
                    mapOf(2 to 2, 7 to 5),
            )
        assertEquals(options, ImportOptionsJson.decode(ImportOptionsJson.encode(options)))
        val edited =
            options.withUserChanges {
                it.copy(itemYouTubeInputs = emptyMap(), itemYouTubeVariants = emptyMap(), notifyNewEpisodes = true)
            }
        assertEquals(options.itemYouTubeInputs, edited.itemYouTubeInputs)
        assertEquals(options.itemYouTubeVariants, edited.itemYouTubeVariants)
        assertTrue(edited.notifyNewEpisodes)
    }

    @Test fun oldSessionsAndInvalidMasksAreSafe() {
        assertEquals(ImportOptions(), ImportOptionsJson.decode("{}"))
        val read = ImportOptionsJson.decode("""{"future":true,"itemYouTubeVariants":{"0":0,"1":8,"2":2}}""")
        assertEquals(mapOf(2 to 2), read.itemYouTubeVariants)
        assertEquals(emptyMap(), read.itemYouTubeInputs)
    }
}
