// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.shownotes_image
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import org.jetbrains.compose.resources.stringResource

/**
 * How show-notes images are treated (03 Images and links, `feeds.show_notes_images`): the episode
 * screen maps the setting plus the metered state into this enum before rendering.
 */
public enum class ShowNotesImageMode {
    /** Images load inline (the `ALWAYS`/`WIFI_ONLY`-on-unmetered states). */
    SHOWN,

    /** `TAP_TO_LOAD` before the tap: a 48 dp placeholder row; `onLoadImages` reveals them. */
    TAP_TO_LOAD,

    /**
     * `WIFI_ONLY` on a metered link (03): images stay unloaded and the same 48 dp placeholder
     * row shows, but without the tap — the Wi-Fi-only setting offers no per-episode escape.
     */
    BLOCKED,
}

/**
 * 08 "Show notes renderer": emits one lazy item per block of the sanitised model so long notes
 * never compose at once. Links and timestamps are `LinkAnnotation`s inside `SelectionContainer`
 * text; taps come back through [onLink]/[onTimestamp] (callers route to `ExternalUrlOpener` / the
 * player). [durationMs] decides whether a timestamp stays a link (beyond it, plain text — 08).
 */
public fun LazyListScope.showNotes(
    notes: ShowNotes,
    imageMode: ShowNotesImageMode,
    durationMs: Long?,
    onLink: (String) -> Unit,
    onTimestamp: (Long) -> Unit,
    onLoadImages: () -> Unit,
) {
    notes.blocks.forEachIndexed { index, block ->
        item(key = "note-$index", contentType = block.contentType()) {
            ShowNoteBlockContent(
                block = block,
                imageMode = imageMode,
                durationMs = durationMs,
                onLink = onLink,
                onTimestamp = onTimestamp,
                onLoadImages = onLoadImages,
                level = 0,
            )
        }
    }
}

@Composable
private fun ShowNoteBlockContent(
    block: ShowNoteBlock,
    imageMode: ShowNotesImageMode,
    durationMs: Long?,
    onLink: (String) -> Unit,
    onTimestamp: (Long) -> Unit,
    onLoadImages: () -> Unit,
    level: Int,
) {
    when (block) {
        is ShowNoteBlock.Paragraph -> {
            SelectionContainer(Modifier.padding(bottom = PARAGRAPH_GAP)) {
                Text(
                    block.spans.toAnnotatedString(durationMs, onLink, onTimestamp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        is ShowNoteBlock.Heading -> {
            Text(
                block.spans.toAnnotatedString(durationMs, onLink, onTimestamp),
                style =
                    if (block.level <= HEADING_LARGE_MAX_LEVEL) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                modifier = Modifier.padding(top = HEADING_TOP_GAP, bottom = HEADING_BOTTOM_GAP),
            )
        }

        is ShowNoteBlock.ListBlock -> {
            NoteList(block, imageMode, durationMs, onLink, onTimestamp, onLoadImages, level)
        }

        is ShowNoteBlock.Quote -> {
            NoteQuote(block, imageMode, durationMs, onLink, onTimestamp, onLoadImages, level)
        }

        is ShowNoteBlock.Image -> {
            NoteImage(block, imageMode, onLoadImages)
        }

        ShowNoteBlock.Rule -> {
            HorizontalDivider(Modifier.padding(vertical = RULE_GAP))
        }
    }
}

/** Bullet "•" or "1." in a 24 dp gutter per nesting level, ≤ 4 deep (08). */
@Composable
private fun NoteList(
    block: ShowNoteBlock.ListBlock,
    imageMode: ShowNotesImageMode,
    durationMs: Long?,
    onLink: (String) -> Unit,
    onTimestamp: (Long) -> Unit,
    onLoadImages: () -> Unit,
    level: Int,
) {
    Column(
        Modifier.padding(
            start = LIST_GUTTER * level.coerceAtMost(MAX_LIST_LEVEL),
            bottom = PARAGRAPH_GAP,
        ),
    ) {
        block.items.forEachIndexed { index, itemBlocks ->
            Row {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(LIST_MARKER_WIDTH),
                )
                Column(Modifier.weight(1f)) {
                    itemBlocks.forEach { itemBlock ->
                        ShowNoteBlockContent(
                            block = itemBlock,
                            imageMode = imageMode,
                            durationMs = durationMs,
                            onLink = onLink,
                            onTimestamp = onTimestamp,
                            onLoadImages = onLoadImages,
                            level = level + 1,
                        )
                    }
                }
            }
        }
    }
}

/** 4 dp `outlineVariant` start border, 12 dp indent (08). */
@Composable
private fun NoteQuote(
    block: ShowNoteBlock.Quote,
    imageMode: ShowNotesImageMode,
    durationMs: Long?,
    onLink: (String) -> Unit,
    onTimestamp: (Long) -> Unit,
    onLoadImages: () -> Unit,
    level: Int,
) {
    Row(Modifier.height(IntrinsicSize.Min).padding(bottom = PARAGRAPH_GAP)) {
        Box(
            Modifier
                .width(QUOTE_BAR_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Column(Modifier.padding(start = QUOTE_INDENT).weight(1f)) {
            block.blocks.forEach { inner ->
                ShowNoteBlockContent(inner, imageMode, durationMs, onLink, onTimestamp, onLoadImages, level)
            }
        }
    }
}

/**
 * Inline image at its intrinsic aspect (max width, ≤ 480 dp); otherwise the 48 dp
 * "Image: {alt}" row (08). TAP_TO_LOAD's row calls [onLoadImages] on tap (08's privacy gate);
 * BLOCKED (Wi-Fi-only on a metered link) shows the same row without the tap, because that
 * setting has no per-episode escape (03). The model is a plain URL string so the artwork mapper
 * does not apply (08).
 *
 * The feed's `width`/`height` attributes only pre-size the box: [declaredAspect] returns them
 * as an aspect ratio when they are positive and sane; without them (or once the bitmap lands,
 * correcting a wrong hint) the loaded image's own aspect takes over, so the modifier chain is
 * `heightIn(max)` + `aspectRatio` — the cap bounds the height and a too-tall ratio trades
 * width for it instead of clipping (UI review round 3: `Fit`, never `FillWidth`). An absurd
 * pair (3 × 10000) would make `aspectRatio` fall back to a constraints-free candidate and
 * throw on the unrepresentable size, so it is treated like absent dimensions.
 */
@Composable
private fun NoteImage(
    block: ShowNoteBlock.Image,
    imageMode: ShowNotesImageMode,
    onLoadImages: () -> Unit,
) {
    if (imageMode != ShowNotesImageMode.SHOWN) {
        val tapToLoad = imageMode == ShowNotesImageMode.TAP_TO_LOAD
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TOUCH_TARGET)
                    .let { m -> if (tapToLoad) m.clickable(onClick = onLoadImages) else m }
                    .padding(bottom = PARAGRAPH_GAP),
        ) {
            Icon(NdIcons.Image, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Text(
                stringResource(Res.string.shownotes_image, block.alt ?: block.url),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (tapToLoad) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(start = TEXT_GAP),
            )
        }
        return
    }

    val context = LocalPlatformContext.current
    val request = remember(block.url) { ImageRequest.Builder(context).data(block.url).build() }
    var loadedAspect by remember(block.url) { mutableStateOf<Float?>(null) }
    val aspect = declaredAspect(block.width, block.height) ?: loadedAspect
    AsyncImage(
        model = request,
        contentDescription = block.alt,
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            val image = state.result.image
            if (image.width > 0 && image.height > 0) {
                loadedAspect = image.width.toFloat() / image.height
            }
        },
        modifier =
            Modifier
                // The inter-block gap sits outside the capped image box.
                .padding(bottom = PARAGRAPH_GAP)
                .let { m -> if (aspect == null) m.fillMaxWidth() else m }
                .heightIn(max = IMAGE_MAX_HEIGHT)
                .let { m -> if (aspect != null) m.aspectRatio(aspect) else m },
    )
}

/** The `width`/`height` attribute pair as an aspect ratio, or null when absent or absurd. */
private fun declaredAspect(
    width: Int?,
    height: Int?,
): Float? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val aspect = width.toFloat() / height
    return if (aspect in MIN_IMAGE_ASPECT..MAX_IMAGE_ASPECT) aspect else null
}

private fun ShowNoteBlock.contentType(): String =
    when (this) {
        is ShowNoteBlock.Paragraph -> "paragraph"
        is ShowNoteBlock.Heading -> "heading"
        is ShowNoteBlock.ListBlock -> "list"
        is ShowNoteBlock.Quote -> "quote"
        is ShowNoteBlock.Image -> "image"
        ShowNoteBlock.Rule -> "rule"
    }

/** Style bits of `ShowNotesStyles` (03): BOLD 1, ITALIC 2, UNDERLINE 4, CODE 8. */
private fun spanStyle(bits: Int): SpanStyle =
    SpanStyle(
        fontWeight = if (bits and STYLE_BOLD != 0) FontWeight.Bold else null,
        fontStyle = if (bits and STYLE_ITALIC != 0) FontStyle.Italic else null,
        textDecoration = if (bits and STYLE_UNDERLINE != 0) TextDecoration.Underline else null,
        fontFamily = if (bits and STYLE_CODE != 0) FontFamily.Monospace else null,
    )

@Composable
private fun List<ShowNoteSpan>.toAnnotatedString(
    durationMs: Long?,
    onLink: (String) -> Unit,
    onTimestamp: (Long) -> Unit,
): AnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    // The builder scope is not composable, so the listeners are hoisted and read the tag back.
    val linkListener =
        remember(onLink) {
            LinkInteractionListener { annotation ->
                (annotation as? LinkAnnotation.Clickable)?.tag?.let(onLink)
            }
        }
    val seekListener =
        remember(onTimestamp) {
            LinkInteractionListener { annotation ->
                (annotation as? LinkAnnotation.Clickable)
                    ?.tag
                    ?.removePrefix(SEEK_TAG_PREFIX)
                    ?.toLongOrNull()
                    ?.let(onTimestamp)
            }
        }
    return buildAnnotatedString {
        for (span in this@toAnnotatedString) {
            when (span) {
                is ShowNoteSpan.Text -> {
                    pushStyle(spanStyle(span.style))
                    append(span.text)
                    pop()
                }

                is ShowNoteSpan.Link -> {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = span.url,
                            styles = linkStyle,
                            linkInteractionListener = linkListener,
                        ),
                    ) {
                        pushStyle(spanStyle(span.style))
                        append(span.text)
                        pop()
                    }
                }

                is ShowNoteSpan.Timestamp -> {
                    val seekable = durationMs == null || span.positionMs <= durationMs
                    if (seekable) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "$SEEK_TAG_PREFIX${span.positionMs}",
                                styles = linkStyle,
                                linkInteractionListener = seekListener,
                            ),
                        ) {
                            append(span.text)
                        }
                    } else {
                        append(span.text)
                    }
                }

                ShowNoteSpan.LineBreak -> {
                    append('\n')
                }
            }
        }
    }
}

private const val SEEK_TAG_PREFIX = "seek:"

private const val STYLE_BOLD = 1
private const val STYLE_ITALIC = 2
private const val STYLE_UNDERLINE = 4
private const val STYLE_CODE = 8

private const val HEADING_LARGE_MAX_LEVEL = 2
private const val MAX_LIST_LEVEL = 4

private val PARAGRAPH_GAP = 12.dp
private val HEADING_TOP_GAP = 16.dp
private val HEADING_BOTTOM_GAP = 4.dp
private val LIST_GUTTER = 24.dp
private val LIST_MARKER_WIDTH = 24.dp
private val QUOTE_BAR_WIDTH = 4.dp
private val QUOTE_INDENT = 12.dp
private val RULE_GAP = 12.dp
private val IMAGE_MAX_HEIGHT = 480.dp

/**
 * Declared `width`/`height` ratios outside this range (a 3 × 10000 banner, a 10000 × 3 strip)
 * are treated as unknown dimensions: they would either vanish or make `aspectRatio` request a
 * size `Constraints` cannot represent (UI review round 2, P1).
 */
private const val MIN_IMAGE_ASPECT = 1f / 16
private const val MAX_IMAGE_ASPECT = 16f
private val TOUCH_TARGET = 48.dp
private val ICON_SIZE = 24.dp
private val TEXT_GAP = 12.dp
