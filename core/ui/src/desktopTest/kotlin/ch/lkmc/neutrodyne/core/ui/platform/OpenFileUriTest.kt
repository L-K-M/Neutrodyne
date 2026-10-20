// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.platform

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenFileUriTest {
    @Test fun selectedFileIsAnEscapedFileUri() {
        val directory = Files.createTempDirectory("open-file-uri")
        try {
            val selected = openedFileUri(directory.toString(), "My feeds #1.opml")
            assertTrue(selected.startsWith("file:"), "Expected a file URI, got a bare path")
            val uri = URI(selected)
            assertEquals("file", uri.scheme)
            assertEquals(directory.resolve("My feeds #1.opml"), Path.of(uri))
        } finally {
            Files.delete(directory)
        }
    }
}
