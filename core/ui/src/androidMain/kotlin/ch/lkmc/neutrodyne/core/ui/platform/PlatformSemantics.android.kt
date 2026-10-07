// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

public actual fun Modifier.testTagsAsResourceId(): Modifier = semantics { testTagsAsResourceId = true }
