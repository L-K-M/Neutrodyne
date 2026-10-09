// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.html

/**
 * The common show-notes block model (03 Sanitiser and block model), mirrored 1:1 into `:core:model` by
 * `:core:data`; 08 renders the mirror. Immutable; produced by `ShowNotesSanitizer.toDocument` at display
 * time.
 */
public data class ShowNotesDocument(
    val blocks: List<NoteBlock>,
)

public sealed interface NoteBlock {
    public data class Paragraph(
        val spans: List<NoteSpan>,
    ) : NoteBlock

    public data class Heading(
        /** 1..6 as produced by the sanitiser; renderers should still clamp defensively. */
        val level: Int,
        val spans: List<NoteSpan>,
    ) : NoteBlock

    public data class ListBlock(
        val ordered: Boolean,
        val items: List<List<NoteBlock>>,
    ) : NoteBlock

    public data class Quote(
        val blocks: List<NoteBlock>,
    ) : NoteBlock

    public data class Image(
        val url: String,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : NoteBlock

    public data object Rule : NoteBlock
}

public sealed interface NoteSpan {
    /** Style bits: [ShowNotesStyles.BOLD] 1, [ITALIC] 2, [UNDERLINE] 4, [CODE] 8. */
    public data class Text(
        val text: String,
        val style: Int = ShowNotesStyles.NONE,
    ) : NoteSpan

    public data class Link(
        val text: String,
        val url: String,
        val style: Int = ShowNotesStyles.NONE,
    ) : NoteSpan

    /** A show-notes timestamp; tapping seeks (03 Timestamp linkifier, 06). */
    public data class Timestamp(
        val text: String,
        val positionMs: Long,
    ) : NoteSpan

    public data object LineBreak : NoteSpan
}

/** Style bit constants for [NoteSpan.Text] and [NoteSpan.Link]. */
public object ShowNotesStyles {
    public const val NONE: Int = 0
    public const val BOLD: Int = 1
    public const val ITALIC: Int = 2
    public const val UNDERLINE: Int = 4
    public const val CODE: Int = 8
}
