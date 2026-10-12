// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.repo.toModel
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.feeds.html.NoteBlock
import ch.lkmc.neutrodyne.feeds.html.NoteSpan
import ch.lkmc.neutrodyne.feeds.html.ShowNotesDocument
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `ShowNotesDocument` mirror must carry every span attribute the sanitizer emits (03). */
class ShowNotesMappingTest {
    @Test
    fun timestampSpanKeepsItsStyle() {
        val notes =
            ShowNotesDocument(
                listOf(
                    NoteBlock.Paragraph(
                        listOf(NoteSpan.Timestamp("1:23", positionMs = 83_000, style = ShowNotesStyles.BOLD)),
                    ),
                ),
            ).toModel()

        val span = (notes.blocks.single() as ShowNoteBlock.Paragraph).spans.single()
        assertEquals(ShowNoteSpan.Timestamp("1:23", positionMs = 83_000, style = ShowNotesStyles.BOLD), span)
    }
}
