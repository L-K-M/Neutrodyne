// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeArchiveWriterFactoryTest {
    @Test
    fun duplicateEntryDoesNotOverwriteTheOriginal() {
        val factory = FakeArchiveWriterFactory()
        factory.create("backup.zip".toPath()).use { writer ->
            writer.entry("a").buffer().use { it.writeUtf8("original") }

            assertFailsWith<IllegalStateException> { writer.entry("a") }
        }

        assertEquals(
            "original",
            factory.written
                .single()
                .entries
                .getValue("a")
                .decodeToString(),
        )
    }
}
