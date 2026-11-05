// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.brand_mark
import org.jetbrains.compose.resources.painterResource

/** The generated in-app mark derived from media-sources/icon.png. */
@Composable
public fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.brand_mark), contentDescription = null, modifier = modifier)
}
