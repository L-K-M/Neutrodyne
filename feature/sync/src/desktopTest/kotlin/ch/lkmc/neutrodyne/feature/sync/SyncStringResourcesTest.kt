// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.allStringResources
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compose resources are not Android resources: a backslash before an apostrophe is shown to the user. */
@OptIn(ExperimentalResourceApi::class)
class SyncStringResourcesTest {
    @Test
    fun noEnglishTextShowsAnEscape() = assertNoEscapes(Locale.ENGLISH)

    @Test
    fun noGermanTextShowsAnEscape() = assertNoEscapes(Locale.GERMAN)

    private fun assertNoEscapes(locale: Locale) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val resources = Res.allStringResources
            assertTrue(resources.isNotEmpty())
            val escaped = runBlocking { resources.filterValues { '\\' in getString(it) }.keys }
            assertEquals(emptySet<String>(), escaped)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
