// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import okio.Path.Companion.toPath
import java.net.URI
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `mpris:artUrl` must be a legal URI: raw `file://$path` interpolation breaks on macOS
 * ("Application Support") and Windows (backslashes), so spaces and non-ASCII percent-encode.
 */
class DesktopArtworkContentUrisTest {
    @Test
    fun `spaces and non-ascii path characters are percent-encoded`() {
        val path = "/home/u/Application Support/art/é.png".toPath()

        val uri = DesktopArtworkContentUris().of("u-key", 1, path)

        // java.net.URI rejects raw spaces/non-ASCII; the URI must parse and lead back to the file
        // (compared as nio paths, so a Windows drive prefix does not matter).
        assertTrue(uri.startsWith("file:/") && "Application%20Support" in uri && "%C3%A9" in uri, uri)
        assertEquals(path.toNioPath().toAbsolutePath(), Paths.get(URI(uri)))
    }

    @Test
    fun `no pinned path yields the empty uri`() {
        assertEquals("", DesktopArtworkContentUris().of("u-key", 1, null))
    }
}
