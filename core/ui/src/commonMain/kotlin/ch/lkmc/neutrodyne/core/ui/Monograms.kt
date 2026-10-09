// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec

/**
 * The deterministic monogram spec for [title], computed once per title (08 CoverArt: `Monogram`
 * lives in `:core:common` because it needs `Nfc`, which `:core:designsystem` cannot see — this is
 * the seam between them).
 */
@Composable
public fun rememberMonogram(title: String): MonogramSpec = remember(title) { Monogram.spec(title) }
