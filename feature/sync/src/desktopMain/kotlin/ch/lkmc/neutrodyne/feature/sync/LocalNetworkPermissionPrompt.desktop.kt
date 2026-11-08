// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable

/**
 * No desktop OS has a local-network runtime permission, so there is no prompt to run; the gate
 * never raises the state here either (10 Local-network gate).
 */
@Composable
internal actual fun rememberLocalNetworkPermissionPrompt(): LocalNetworkPermissionPrompt? = null
