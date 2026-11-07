// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.platform

import ch.lkmc.neutrodyne.core.common.DirectoryChoice
import ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The Linux folder-chooser decision of 11's table: the portal answers first, a picked
 * folder returns at once, and a dismissal ends the pick without opening `JFileChooser`
 * — only a portal that does not answer at all falls through to Swing (that path needs a
 * display and stays with the manual checklist; a headless JVM would open nothing).
 */
class DesktopPlatformActionsFolderTest {
    @Test
    fun aChosenFolderIsReturned() =
        runTest {
            val portal = FakePortal(DirectoryChoice.Chosen("/home/alex/Podcasts"))
            val actions = DesktopPlatformActions(portal, CoroutineScope(coroutineContext))

            val picked = actions.files.pickFolder("Choose a folder")

            assertThat(picked).isEqualTo("/home/alex/Podcasts")
            assertThat(portal.titles).containsExactly("Choose a folder")
        }

    @Test
    fun aDismissedPortalDialogEndsWithoutAFolder() =
        runTest {
            val portal = FakePortal(DirectoryChoice.Cancelled)
            val actions = DesktopPlatformActions(portal, CoroutineScope(coroutineContext))

            val picked = actions.files.pickFolder("Choose a folder")

            assertThat(picked).isNull()
        }

    private class FakePortal(
        private val answer: DirectoryChoice,
    ) : LinuxDesktopPortal {
        val titles = mutableListOf<String>()

        override suspend fun showItems(file: String): Boolean = false

        override suspend fun chooseDirectory(
            title: String,
            start: String?,
        ): DirectoryChoice {
            titles += title
            return answer
        }
    }
}
