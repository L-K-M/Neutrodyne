// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * Corner radii (08 Tokens): thumbnails 8, tiles 12, mini player 16, podcast/player art 24 and
 * sheets 28 dp at the top. `Shapes` maps the M3 slots onto them; the named shapes are for the
 * components whose corner differs from the slot (sheets, player art).
 */
public object NeutrodyneShapes {
    public val Thumbnail: RoundedCornerShape = RoundedCornerShape(8.dp)
    public val Tile: RoundedCornerShape = RoundedCornerShape(12.dp)
    public val MiniPlayer: RoundedCornerShape = RoundedCornerShape(16.dp)
    public val PlayerArt: RoundedCornerShape = RoundedCornerShape(24.dp)
    public val Sheet: RoundedCornerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    public val material: Shapes =
        Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = Thumbnail,
            medium = Tile,
            large = RoundedCornerShape(16.dp),
            extraLarge = PlayerArt,
        )
}

/** The M3 default type scale on the platform font; a brand typeface only if PO-17 asks (08 Tokens). */
public val NeutrodyneType: Typography = Typography()

/**
 * Motion tokens (08 Tokens): `spatial` for movement, `effects` for fades and state layers,
 * `emphasized` for large transitions. Under [LocalReducedMotion] every spec snaps. Expressive's
 * `MotionScheme` replaces this object in one place when adopted (PO-4).
 */
public object NeutrodyneMotion {
    /** Springs for position/size changes (dampingRatio 0.8, stiffness 380). */
    @Composable
    @ReadOnlyComposable
    public fun <T> spatial(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else spring(dampingRatio = 0.8f, stiffness = 380f)

    /** 200 ms tween for fades, tints and state layers. */
    @Composable
    @ReadOnlyComposable
    public fun <T> effects(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else tween(200)

    /** 400 ms tween for emphasized transitions. */
    @Composable
    @ReadOnlyComposable
    public fun <T> emphasized(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else tween(400)

    /** Non-composable variants for scopes without composition (physics values, draw code). */
    public fun <T> spatial(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = 0.8f, stiffness = 380f)

    public fun <T> effects(reducedMotion: Boolean): AnimationSpec<T> = if (reducedMotion) snap() else tween(200)

    public fun <T> emphasized(reducedMotion: Boolean): AnimationSpec<T> = if (reducedMotion) snap() else tween(400)
}
