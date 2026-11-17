// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.EnamelSign
import ch.lkmc.neutrodyne.core.designsystem.radio.Faceplate
import ch.lkmc.neutrodyne.core.designsystem.radio.LocalCabinetBackKey
import ch.lkmc.neutrodyne.core.designsystem.radio.LocalRadioFaceplate
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial

/**
 * The page header (08 Nd wrappers, radio look since 2026-11-17): a metal faceplate across the top
 * of the cabinet's paper tray, with the navigation key at the start, [title] on a centred enamel
 * sign and the actions at the end. Icon buttons in either slot become ivory keys through
 * [LocalRadioFaceplate]; other content is drawn in cream. A blank [title] shows no sign. Without
 * a [navigation] key the page's cabinet supplies its Back key ([LocalCabinetBackKey]), so pages
 * opened from the radio's Menu return home like every other radio part.
 * [elevated] (content scrolled under the plate) deepens the plate's shadow on the paper.
 */
@Composable
public fun NdTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    elevated: Boolean = false,
) {
    // Pinned chrome must win overlap with off-screen semantics from scrolling siblings.
    Faceplate(
        modifier
            .fillMaxWidth()
            .zIndex(PINNED_BAR_Z_INDEX)
            .plateShadow(elevated),
    ) {
        val navigationKey = navigation ?: LocalCabinetBackKey.current
        CompositionLocalProvider(LocalContentColor provides Cream, LocalRadioFaceplate provides true) {
            FaceplateRow(
                navigation = { Box(contentAlignment = Alignment.Center) { navigationKey?.invoke() } },
                sign = {
                    if (title.isNotBlank()) {
                        EnamelSign(title, Modifier.semantics { heading() })
                    }
                },
                actions = { Row(verticalAlignment = Alignment.CenterVertically, content = actions) },
            )
        }
    }
}

/**
 * Lays the sign out centred on the plate when the keys beside it leave room, and otherwise
 * between them, so a long title never runs under a key.
 */
@Composable
private fun FaceplateRow(
    navigation: @Composable () -> Unit,
    sign: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(navigation, sign, actions),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = FACEPLATE_MIN_HEIGHT)
                .padding(horizontal = 6.dp, vertical = 6.dp),
    ) { (navigationMeasurables, signMeasurables, actionMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val navigationPlaceables = navigationMeasurables.map { it.measure(loose) }
        val actionPlaceables = actionMeasurables.map { it.measure(loose) }
        val navigationWidth = navigationPlaceables.maxOfOrNull { it.width } ?: 0
        val actionWidth = actionPlaceables.maxOfOrNull { it.width } ?: 0
        val gap = SIGN_GAP.roundToPx()
        val width = constraints.maxWidth
        val centredRoom = width - 2 * (maxOf(navigationWidth, actionWidth) + gap)
        val betweenRoom = width - navigationWidth - actionWidth - 2 * gap
        val signPlaceables =
            signMeasurables.map {
                it.measure(Constraints(maxWidth = maxOf(betweenRoom, 0), maxHeight = loose.maxHeight))
            }
        val signWidth = signPlaceables.maxOfOrNull { it.width } ?: 0
        val contentHeight =
            maxOf(
                navigationPlaceables.maxOfOrNull { it.height } ?: 0,
                actionPlaceables.maxOfOrNull { it.height } ?: 0,
                signPlaceables.maxOfOrNull { it.height } ?: 0,
            )
        val height = maxOf(contentHeight, constraints.minHeight)
        val signX =
            if (signWidth <= centredRoom) {
                (width - signWidth) / 2
            } else {
                navigationWidth + gap + (betweenRoom - signWidth) / 2
            }
        layout(width, height) {
            navigationPlaceables.forEach { it.placeRelative(0, (height - it.height) / 2) }
            signPlaceables.forEach { it.placeRelative(signX, (height - it.height) / 2) }
            actionPlaceables.forEach { it.placeRelative(width - it.width, (height - it.height) / 2) }
        }
    }
}

/** The faceplate overhangs the paper tray; its shadow falls on the paper beneath. */
private fun Modifier.plateShadow(elevated: Boolean): Modifier =
    drawWithContent {
        drawContent()
        val depth = (if (elevated) 10.dp else 5.dp).toPx()
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0x8C1C1006), Color.Transparent),
                startY = size.height,
                endY = size.height + depth,
            ),
            topLeft = Offset(0f, size.height),
            size = Size(size.width, depth),
        )
    }

private const val PINNED_BAR_Z_INDEX = 1f
private val FACEPLATE_MIN_HEIGHT = 64.dp
private val SIGN_GAP = 6.dp
