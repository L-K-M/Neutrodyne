// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.ui.Modifier

/** CMP desktop has no `testTagsAsResourceId` semantics property; desktop tests use tags directly. */
public actual fun Modifier.testTagsAsResourceId(): Modifier = this
