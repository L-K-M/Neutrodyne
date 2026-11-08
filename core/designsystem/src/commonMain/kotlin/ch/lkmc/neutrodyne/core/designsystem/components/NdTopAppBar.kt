// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex

public enum class NdTopAppBarAppearance { SURFACE, ARTWORK_OVERLAY }

/**
 * Standard top app bar (08 Nd wrappers): flat `surface` until [elevated] (scrolled) raises it to
 * `surfaceContainer`. [title] is a resolved resource string. The signature carries no experimental
 * Material 3 type (scroll behaviour, top-bar defaults), so callers need no opt-in (08 Theming and colour);
 * scroll-linked behaviour arrives with the first scrolling screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NdTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    elevated: Boolean = false,
    appearance: NdTopAppBarAppearance = NdTopAppBarAppearance.SURFACE,
) {
    val background =
        when (appearance) {
            NdTopAppBarAppearance.SURFACE -> Color.Transparent
            NdTopAppBarAppearance.ARTWORK_OVERLAY -> ARTWORK_SCRIM
        }
    TopAppBar(
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        // Pinned chrome must win overlap with off-screen semantics from scrolling siblings.
        modifier =
            modifier
                .background(background)
                .zIndex(PINNED_BAR_Z_INDEX),
        navigationIcon = navigation,
        actions = actions,
        colors = ndTopAppBarColors(elevated, appearance),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ndTopAppBarColors(
    elevated: Boolean,
    appearance: NdTopAppBarAppearance,
): TopAppBarColors =
    if (appearance == NdTopAppBarAppearance.ARTWORK_OVERLAY) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            navigationIconContentColor = Color.White,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White,
        )
    } else if (elevated) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

private const val PINNED_BAR_Z_INDEX = 1f

// White controls retain at least 5.7:1 contrast even over an entirely white cover.
private val ARTWORK_SCRIM = Color.Black.copy(alpha = 0.6f)
