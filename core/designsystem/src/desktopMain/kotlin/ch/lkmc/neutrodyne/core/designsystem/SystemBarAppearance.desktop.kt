// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import androidx.compose.runtime.Composable

/** Desktop has no status bar to tint: no-op. */
@Composable
public actual fun PlatformSystemBarAppearance(appearance: StatusBarAppearance) = Unit
