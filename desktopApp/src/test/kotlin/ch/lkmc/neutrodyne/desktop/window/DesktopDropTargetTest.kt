// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.desktop.DesktopOpenHandler
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The window drop target's text routing (11 Drag and drop): a dragged browser link becomes the
 * same input the OS hand-off takes, and anything unreadable becomes the handler's rejection.
 */
class DesktopDropTargetTest {
    @Test
    fun aSingleUrlIsOneInput() {
        assertThat(droppedTextInputs("https://example.com/feed.xml"))
            .containsExactly("https://example.com/feed.xml")
    }

    @Test
    fun uriListLinesAndCommentsAreSeparated() {
        val text =
            """
            https://example.com/feed.xml
            # a comment
            https://example.com/other.opml
            """.trimIndent()

        assertThat(droppedTextInputs(text))
            .containsExactly("https://example.com/feed.xml", "https://example.com/other.opml")
            .inOrder()
    }

    @Test
    fun moreThanTwentyInputsKeepTheFirstTwenty() {
        val text = (1..25).joinToString("\n") { "https://example.com/feed-$it.xml" }

        assertThat(droppedTextInputs(text)).hasSize(DesktopOpenHandler.MAX_INPUTS)
        assertThat(droppedTextInputs(text).first()).isEqualTo("https://example.com/feed-1.xml")
    }

    @Test
    fun blankTextBecomesTheHandlersRejectionInput() {
        assertThat(droppedTextInputs("")).containsExactly("")
        assertThat(droppedTextInputs("\n# only a comment\n")).containsExactly("")
    }
}
