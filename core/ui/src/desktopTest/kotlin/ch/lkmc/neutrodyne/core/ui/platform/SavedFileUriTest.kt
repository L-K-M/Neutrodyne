// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deterministic half of the desktop save dialog (05 Destinations): the chosen name's
 * default-extension rule and the escaped local `file:` URI `FileExportDestination` consumes —
 * the native `FileDialog` interaction itself stays a manual check.
 */
class SavedFileUriTest {
    @Test fun anExtensionlessNameGetsTheMimesDefault() {
        val uri = savedFileUri("/tmp/exports", "feeds", "text/x-opml")

        assertEquals(File("/tmp/exports", "feeds.opml").toURI().toASCIIString(), uri)
    }

    @Test fun aNameWithAnExtensionIsKeptVerbatim() {
        val uri = savedFileUri("/tmp/exports", "feeds.xml", "text/x-opml")

        assertEquals(File("/tmp/exports", "feeds.xml").toURI().toASCIIString(), uri)
    }

    @Test fun unknownTypesGetNoInventedExtension() {
        assertNull(defaultExtension("application/octet-stream"))
        val uri = savedFileUri("/tmp", "blob", "application/octet-stream")

        assertEquals(File("/tmp", "blob").toURI().toASCIIString(), uri)
    }

    @Test fun theResultIsAnEscapedLocalFileUri() {
        val dir = File("/tmp", "my exports")
        val uri = savedFileUri(dir.absolutePath, "neutrodyne subs", "application/zip")

        assertTrue(uri.startsWith("file:"))
        assertTrue("%20" in uri)
        assertEquals(File(dir, "neutrodyne subs.zip"), File(URI(uri)))
    }
}
