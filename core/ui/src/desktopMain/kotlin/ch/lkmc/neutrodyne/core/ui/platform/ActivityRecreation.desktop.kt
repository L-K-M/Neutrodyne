// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberIsActivityRecreating(): () -> Boolean = { false }
