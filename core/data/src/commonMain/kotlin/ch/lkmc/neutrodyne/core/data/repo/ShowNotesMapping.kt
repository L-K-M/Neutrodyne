// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.feeds.html.NoteBlock
import ch.lkmc.neutrodyne.feeds.html.NoteSpan
import ch.lkmc.neutrodyne.feeds.html.ShowNotesDocument

/** The block-by-block mirror into `:core:model` (03 Sanitiser and block model); 08 renders it. */
internal fun ShowNotesDocument.toModel(): ShowNotes = ShowNotes(blocks.map { it.toModel() })

private fun NoteBlock.toModel(): ShowNoteBlock =
    when (this) {
        is NoteBlock.Paragraph -> ShowNoteBlock.Paragraph(spans.map { it.toModel() })
        is NoteBlock.Heading -> ShowNoteBlock.Heading(level, spans.map { it.toModel() })
        is NoteBlock.ListBlock -> ShowNoteBlock.ListBlock(ordered, items.map { it.map { b -> b.toModel() } })
        is NoteBlock.Quote -> ShowNoteBlock.Quote(blocks.map { it.toModel() })
        is NoteBlock.Image -> ShowNoteBlock.Image(url, alt, width, height)
        NoteBlock.Rule -> ShowNoteBlock.Rule
    }

private fun NoteSpan.toModel(): ShowNoteSpan =
    when (this) {
        is NoteSpan.Text -> ShowNoteSpan.Text(text, style)
        is NoteSpan.Link -> ShowNoteSpan.Link(text, url, style)
        is NoteSpan.Timestamp -> ShowNoteSpan.Timestamp(text, positionMs)
        NoteSpan.LineBreak -> ShowNoteSpan.LineBreak
    }
