// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        modifier = modifier,
        navigationIcon = navigation,
        actions = actions,
        colors = ndTopAppBarColors(elevated),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ndTopAppBarColors(elevated: Boolean): TopAppBarColors =
    if (elevated) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
