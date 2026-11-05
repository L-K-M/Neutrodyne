// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

public enum class ArtworkSurface { FULL, MINI }

private data class ComputedArtwork(
    val dark: Boolean,
    val contrast: Double,
    val scheme: ColorScheme?,
)

private val artworkSchemes = ArtworkSchemeCache()

/** Only this subtree is tinted. Missing/monochrome/disabled art uses the current app scheme. */
@Composable
public fun ArtworkTheme(
    seedArgb: Int?,
    surface: ArtworkSurface = ArtworkSurface.FULL,
    content: @Composable () -> Unit,
) {
    val app = LocalAppColorScheme.current ?: MaterialTheme.colorScheme
    val enabled = LocalArtworkTintEnabled.current && seedArgb != null
    val dark = LocalAppDarkTheme.current
    val contrast = LocalSystemUiState.current.contrast.normalizedContrast()
    val pureBlack = LocalPureBlack.current
    val reduced = LocalReducedMotion.current
    val computed by produceState<ComputedArtwork?>(null, seedArgb, enabled, dark, contrast) {
        if (!enabled) {
            value = null
        } else {
            val scheme = artworkSchemes.scheme(checkNotNull(seedArgb), dark, contrast)
            // MCU is synchronous CPU work: cancellation must be checked before publishing it.
            currentCoroutineContext().ensureActive()
            value = ComputedArtwork(dark, contrast, scheme)
        }
    }
    val artwork =
        remember(computed, dark, contrast, pureBlack) {
            computed
                ?.takeIf { it.dark == dark && it.contrast == contrast }
                ?.scheme
                ?.let { if (pureBlack) it.toPureBlack() else it }
        }
    val target =
        remember(app, enabled, artwork, surface) {
            when {
                !enabled || artwork == null -> {
                    app
                }

                surface == ArtworkSurface.MINI -> {
                    app.copy(
                        surfaceContainerHigh = lerp(app.surfaceContainerHigh, artwork.primaryContainer, MINI_BLEND),
                    )
                }

                else -> {
                    artwork
                }
            }
        }
    val animated = animateArtworkRoles(target, reduced || !enabled || artwork == null, dark, contrast)
    MaterialTheme(
        colorScheme =
            if (pureBlack &&
                surface == ArtworkSurface.FULL
            ) {
                animated.toPureBlack()
            } else {
                animated
            },
        content = content,
    )
}

@Composable
private fun animateArtworkRoles(
    target: ColorScheme,
    snap: Boolean,
    dark: Boolean,
    contrast: Double,
): ColorScheme {
    var start by remember(dark) { mutableStateOf(target) }
    var end by remember(dark) { mutableStateOf(target) }
    val progress = remember(dark) { Animatable(1f) }
    LaunchedEffect(target, snap) {
        start = interpolateArtworkRoles(start, end, progress.value, contrast)
        end = target
        progress.snapTo(0f)
        if (snap) progress.snapTo(1f) else progress.animateTo(1f, tween(TINT_DURATION_MS))
    }
    return if (snap) target else interpolateArtworkRoles(start, end, progress.value, contrast)
}

/** Keep text readable at intermediate frames, including hue changes and high contrast. */
internal fun interpolateArtworkRoles(
    from: ColorScheme,
    to: ColorScheme,
    fraction: Float,
    contrast: Double,
): ColorScheme {
    val p = fraction.coerceIn(0f, 1f)
    val scheme =
        to.copy(
            primary = lerp(from.primary, to.primary, p),
            onPrimary = lerp(from.onPrimary, to.onPrimary, p),
            primaryContainer = lerp(from.primaryContainer, to.primaryContainer, p),
            onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, p),
            secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, p),
            onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, p),
            surface = lerp(from.surface, to.surface, p),
            onSurface = lerp(from.onSurface, to.onSurface, p),
            onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, p),
            surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, p),
            surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, p),
            outlineVariant = lerp(from.outlineVariant, to.outlineVariant, p),
        )
    val ratio = MIN_TEXT_CONTRAST + contrast.normalizedContrast() * EXTRA_HIGH_CONTRAST
    val primary = readablePair(scheme.primary, scheme.onPrimary, to.primary, to.onPrimary, ratio)
    val container =
        readablePair(
            scheme.primaryContainer,
            scheme.onPrimaryContainer,
            to.primaryContainer,
            to.onPrimaryContainer,
            ratio,
        )
    val secondary =
        readablePair(
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            to.secondaryContainer,
            to.onSecondaryContainer,
            ratio,
        )
    return scheme.copy(
        primary = primary.first,
        onPrimary = primary.second,
        primaryContainer = container.first,
        onPrimaryContainer = container.second,
        secondaryContainer = secondary.first,
        onSecondaryContainer = secondary.second,
        onSurface = readableOn(scheme.surface, scheme.onSurface),
        onSurfaceVariant = readableOn(scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
    )
}

private fun readablePair(
    background: Color,
    foreground: Color,
    targetBackground: Color,
    targetForeground: Color,
    ratio: Double,
): Pair<Color, Color> {
    if (ArtColors.contrast(background.toArgb(), foreground.toArgb()) >= ratio) return background to foreground
    val on = readableOn(background, foreground)
    if (ArtColors.contrast(background.toArgb(), on.toArgb()) >= ratio) return background to on
    // No foreground can meet a high-contrast request on mid tones; snap this pair together.
    return targetBackground to targetForeground
}

private fun readableOn(
    background: Color,
    foreground: Color,
): Color {
    if (ArtColors.contrast(background.toArgb(), foreground.toArgb()) >= MIN_TEXT_CONTRAST) return foreground
    return if (ArtColors.contrast(background.toArgb(), Color.Black.toArgb()) >=
        ArtColors.contrast(background.toArgb(), Color.White.toArgb())
    ) {
        Color.Black
    } else {
        Color.White
    }
}

private const val MINI_BLEND = 0.12f
private const val TINT_DURATION_MS = 400
private const val MIN_TEXT_CONTRAST = 4.5
private const val EXTRA_HIGH_CONTRAST = 2.5
