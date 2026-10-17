// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.runtime.Composable

/** Read at disposal, when Android has decided whether this activity is being recreated. */
@Composable
internal expect fun rememberIsActivityRecreating(): () -> Boolean
