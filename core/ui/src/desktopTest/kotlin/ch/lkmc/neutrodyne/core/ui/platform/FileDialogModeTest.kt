// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileDialogModeTest {
    @Test fun fileOpenIgnoresPreviousMacFolderMode() {
        val key = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "true")
            withFileDialogMode(FileDialogMode.FILE) { assertEquals("false", System.getProperty(key)) }
            assertEquals("true", System.getProperty(key))
            assertFailsWith<IllegalStateException> {
                withFileDialogMode(FileDialogMode.FILE) { error("Dialog failed") }
            }
            assertEquals("true", System.getProperty(key))
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
