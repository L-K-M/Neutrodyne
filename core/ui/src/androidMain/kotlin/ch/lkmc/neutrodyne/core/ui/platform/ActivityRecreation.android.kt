// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberIsActivityRecreating(): () -> Boolean {
    val activity = LocalActivity.current
    return remember(activity) { { activity?.isChangingConfigurations == true } }
}
