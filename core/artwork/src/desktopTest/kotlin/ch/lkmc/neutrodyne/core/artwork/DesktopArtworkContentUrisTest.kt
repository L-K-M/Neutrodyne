// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import okio.Path.Companion.toPath
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `mpris:artUrl` must be a legal URI: raw `file://$path` interpolation breaks on macOS
 * ("Application Support") and Windows (backslashes), so spaces and non-ASCII percent-encode.
 */
class DesktopArtworkContentUrisTest {
    @Test
    fun `spaces and non-ascii path characters are percent-encoded`() {
        val path = "/home/u/Application Support/art/é.png".toPath()

        val uri = DesktopArtworkContentUris().of("u-key", 1, path)

        // java.net.URI rejects raw spaces/non-ASCII; the decoded path must round-trip.
        assertEquals("file:///home/u/Application%20Support/art/%C3%A9.png", uri)
        assertEquals("/home/u/Application Support/art/é.png", URI(uri).path)
    }

    @Test
    fun `no pinned path yields the empty uri`() {
        assertEquals("", DesktopArtworkContentUris().of("u-key", 1, null))
    }
}
