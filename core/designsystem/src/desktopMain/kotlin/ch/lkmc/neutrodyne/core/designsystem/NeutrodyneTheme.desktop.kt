// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Desktop has no wallpaper dynamic colour: the brand scheme always applies (08 Layers). */
@Composable
internal actual fun platformDynamicScheme(dark: Boolean): ColorScheme? = null
