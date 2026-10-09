// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The `:core:model` mirror of `:feeds`' `ShowNotesDocument` (03 Sanitiser and block model, open
 * question 1 resolved by D-option): `:core:data` maps block by block; 08 renders these types so
 * feature code never sees `:feeds`.
 */
data class ShowNotes(
    val blocks: List<ShowNoteBlock>,
)

sealed interface ShowNoteBlock {
    data class Paragraph(
        val spans: List<ShowNoteSpan>,
    ) : ShowNoteBlock

    data class Heading(
        val level: Int,
        val spans: List<ShowNoteSpan>,
    ) : ShowNoteBlock

    data class ListBlock(
        val ordered: Boolean,
        val items: List<List<ShowNoteBlock>>,
    ) : ShowNoteBlock

    data class Quote(
        val blocks: List<ShowNoteBlock>,
    ) : ShowNoteBlock

    data class Image(
        val url: String,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : ShowNoteBlock

    data object Rule : ShowNoteBlock
}

sealed interface ShowNoteSpan {
    /** Style bits match `:feeds`' `ShowNotesStyles` (BOLD 1, ITALIC 2, UNDERLINE 4, CODE 8). */
    data class Text(
        val text: String,
        val style: Int = 0,
    ) : ShowNoteSpan

    data class Link(
        val text: String,
        val url: String,
        val style: Int = 0,
    ) : ShowNoteSpan

    /** A show-notes timestamp; tapping seeks (03 Timestamp linkifier, 06). */
    data class Timestamp(
        val text: String,
        val positionMs: Long,
    ) : ShowNoteSpan

    data object LineBreak : ShowNoteSpan
}
