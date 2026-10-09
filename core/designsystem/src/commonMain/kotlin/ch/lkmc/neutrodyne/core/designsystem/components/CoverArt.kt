// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext

/**
 * 08 "CoverArt and CoverTile": an [ArtworkRef] rendered through Coil's multiplatform
 * `AsyncImage` (never `SubcomposeAsyncImage` in lazy lists) with the [Covers] request tiers.
 * The [monogram] spec comes from `:core:ui`'s `rememberMonogram(title)` (its `Monogram` needs
 * `:core:common`'s `Nfc`, which this module cannot see). `ref == null` or a monogram key
 * (`m-…`) draws [MonogramPainter] directly — crisper and theme-aware; failures fall back to the
 * same monogram silently. Transparent images draw on `surfaceContainerHighest`; non-square art
 * is centre-cropped.
 *
 * [title] feeds the monogram's two-line label at ≥ 96 dp tiles; [avgArgb] (M10) otherwise the
 * monogram background tone is the placeholder colour — never grey (R5.4).
 */
@Composable
public fun CoverArt(
    ref: ArtworkRef?,
    monogram: MonogramSpec,
    modifier: Modifier = Modifier,
    tier: CoverTier = CoverTier.THUMB,
    aspect: CoverAspect = CoverAspect.SQUARE,
    shape: Shape = NeutrodyneShapes.Thumbnail,
    avgArgb: Int? = null,
    title: String? = null,
    contentDescription: String? = null,
) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val crossfade = !LocalReducedMotion.current
    val painter = rememberMonogramPainter(spec = monogram, title = title)

    // A missing ref or a monogram row renders the painter directly (08: never load m- files).
    if (ref == null || ref.key.startsWith(MONOGRAM_KEY_PREFIX)) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
        return
    }

    val placeholder =
        avgArgb?.let(::Color) ?: Color(painter.backgroundArgb)
    val request =
        remember(ref, tier, aspect, crossfade, density) {
            Covers.request(context, ref, tier, density, aspect, crossfade)
        }
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            placeholder = ColorPainter(placeholder),
            error = painter,
            fallback = painter,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** `ArtworkKeys.monogram` rows — painted live instead of loaded (08 Monograms and mosaics). */
private const val MONOGRAM_KEY_PREFIX = "m-"
