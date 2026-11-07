// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.BrandColors
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowHinge
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.SchemeContent
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.max

internal enum class ReferenceArt(
    val color: Int,
) {
    COLOURFUL(0xFF2255DD.toInt()),
    MONOCHROME(0xFF888888.toInt()),
    LIGHT(0xFFFFFFCC.toInt()),
}

internal enum class PlayerViewport(
    val width: Int,
    val height: Int,
) {
    MINI(360, 640),
    COMPACT(360, 800),
    MEDIUM(700, 800),
    EXPANDED(900, 800),
    LARGE(1400, 900),
    LANDSCAPE(720, 400),
    TABLETOP(800, 700),
}

internal data class M10PlayerVisual(
    val viewport: PlayerViewport,
    val art: ReferenceArt,
    val dark: Boolean,
    val font: Float = 1f,
) {
    val name: String get() =
        "${viewport.name.lowercase()}-${art.name.lowercase()}-" +
            "${if (dark) "dark" else "light"}-f${(font * 100).toInt()}"
}

internal fun m10PlayerCases(full: Boolean): List<M10PlayerVisual> =
    buildList {
        for (viewport in PlayerViewport.entries) {
            for (art in ReferenceArt.entries) {
                for (dark in listOf(false, true)) add(M10PlayerVisual(viewport, art, dark))
            }
        }
        if (full) {
            for (viewport in PlayerViewport.entries) add(M10PlayerVisual(viewport, ReferenceArt.COLOURFUL, true, 2f))
        }
    }

internal fun referenceArt(art: ReferenceArt): ArtworkRef = ArtworkRef("reference-${art.name.lowercase()}", null, 1)

@Composable
internal fun M10PlayerContent(visual: M10PlayerVisual) {
    val density = LocalDensity.current
    NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState(visual.dark, 0.0, true)) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, visual.font)) {
            key(visual) {
                val panel = visual.viewport == PlayerViewport.EXPANDED || visual.viewport == PlayerViewport.LARGE
                val posture =
                    if (visual.viewport == PlayerViewport.TABLETOP) {
                        WindowPosture(
                            persistentListOf(
                                WindowHinge(
                                    WindowRectDp(0f, 340f, 800f, 360f),
                                    HingeOrientation.HORIZONTAL,
                                    HingeState.HALF_OPENED,
                                    true,
                                ),
                            ),
                        )
                    } else {
                        WindowPosture()
                    }
                val now = sampleNow().copy(artwork = referenceArt(visual.art))
                val state =
                    remember {
                        val initial =
                            if (visual.viewport == PlayerViewport.MINI) {
                                PlayerSheetValue.Collapsed
                            } else {
                                PlayerSheetValue.Expanded
                            }
                        PlayerSheetState(initial)
                    }
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    PlayerSheet(
                        PlayerUiState(
                            now = now,
                            colors =
                                ArtworkColors(
                                    now.artwork.key,
                                    1,
                                    visual.art.color.takeUnless { visual.art == ReferenceArt.MONOCHROME },
                                    visual.art.color,
                                ),
                        ),
                        now.position,
                        PlayerSlotState(
                            PaneLayout(1, panel, visual.viewport.width),
                            {},
                            windowWidthDp = visual.viewport.width,
                            posture = posture,
                        ),
                        { it.positionMs },
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        modifier =
                            if (panel) {
                                Modifier.width(if (visual.viewport == PlayerViewport.LARGE) 412.dp else 360.dp)
                            } else {
                                Modifier
                            },
                        state = state,
                    )
                }
            }
        }
    }
}

/** Await the rendered target, rather than timing a screenshot against Default-dispatcher work. */
internal fun M10PlayerVisual.expectedSheetArgb(): Int {
    val app = BrandColors.scheme(dark, 0.0)
    if (art == ReferenceArt.MONOCHROME) {
        return (if (viewport == PlayerViewport.MINI) app.surfaceContainerHigh else app.surface).toArgb()
    }
    val hct = Hct.fromInt(art.color)
    val content = SchemeContent(hct, dark, 0.0)
    if (viewport != PlayerViewport.MINI) return content.surface
    val container =
        if (dark && Hct.fromInt(content.primaryContainer).tone > 40.0) {
            TonalPalette.fromHueAndChroma(hct.hue, max(hct.chroma, 16.0)).tone(30)
        } else {
            content.primaryContainer
        }
    return lerp(app.surfaceContainerHigh, Color(container), 0.12f).toArgb()
}

internal val M10PlayerVisual.sheetTag: String
    get() =
        if (viewport == PlayerViewport.EXPANDED || viewport == PlayerViewport.LARGE) {
            "player_side_panel"
        } else {
            "player_sheet"
        }
