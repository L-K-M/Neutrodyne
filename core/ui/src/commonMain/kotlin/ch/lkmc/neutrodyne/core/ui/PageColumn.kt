// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 08 Desktop windows: lists cap their rows at this width and centre inside wider pages. A list
 * with nothing beside it fills a wide window (08 Pane roles), so its column keeps this width.
 */
public val PAGE_COLUMN_MAX_WIDTH: Dp = 720.dp

/** Centres this block in the width it is given, at most [PAGE_COLUMN_MAX_WIDTH] wide. */
public fun Modifier.pageColumn(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = PAGE_COLUMN_MAX_WIDTH)

/**
 * Hands [content] the side inset that centres a [PAGE_COLUMN_MAX_WIDTH] column in this box, for a
 * lazy list's content padding: the list itself still spans the page, so it scrolls from anywhere
 * and its scrollbar stays at the page's edge.
 */
@Composable
@Suppress("FunctionNaming") // Compose functions use PascalCase.
public fun PageColumnInset(
    modifier: Modifier = Modifier,
    content: @Composable (inset: Dp) -> Unit,
) {
    BoxWithConstraints(modifier) {
        // Without a bounded width there is no page to centre in.
        val inset = if (constraints.hasBoundedWidth) (maxWidth - PAGE_COLUMN_MAX_WIDTH) / 2 else 0.dp
        content(inset.coerceAtLeast(0.dp))
    }
}
