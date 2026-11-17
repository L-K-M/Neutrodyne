// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Chassis
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.VALVE_DECK_ASPECT
import ch.lkmc.neutrodyne.core.designsystem.radio.ValveDeck
import ch.lkmc.neutrodyne.core.designsystem.radio.cabinetEdges
import ch.lkmc.neutrodyne.core.designsystem.radio.radioStage

public data class RadioActions(
    val selectEpisode: (Long) -> Unit = {},
    val playEpisode: (Long) -> Unit = {},
    val discard: (Long) -> Unit = {},
    val undo: () -> Unit = {},
    val togglePlay: () -> Unit = {},
    val seek: (Long) -> Unit = {},
    val skipBack: () -> Unit = {},
    val skipForward: () -> Unit = {},
    val selectPlaylist: (Long) -> Unit = {},
    val menu: () -> Unit = {},
    val loadEpisode: (Int) -> Unit = {},
    val visibleEpisodes: (Set<Long>) -> Unit = {},
    val retry: () -> Unit = {},
)

/** The physical home is shared by Android and desktop; system bars belong to the host. */
@Composable
public fun RadioScreen(
    state: RadioState,
    actions: RadioActions,
    modifier: Modifier = Modifier,
) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Ink,
                onPrimary = Cream,
                surface = Cream,
                onSurface = Ink,
                outline = Brass,
            ),
    ) {
        RadioCabinet(state, actions, modifier)
    }
}

@Composable
private fun RadioCabinet(
    state: RadioState,
    actions: RadioActions,
    modifier: Modifier = Modifier,
) {
    val programme = rememberSaveable(saver = RadioProgrammeState.Saver) { RadioProgrammeState() }
    val scroll = rememberLazyListState()
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .radioStage()
            .windowInsetsPadding(WindowInsets.systemBars)
            .testTag("radio_home"),
    ) {
        val width = maxWidth
        val height = maxHeight
        val narrow = width < 370.dp
        val wide = width >= 600.dp
        val viewportHeight =
            with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.height
                    .toDp()
            }
        val compact = viewportHeight < 800.dp
        val short = viewportHeight < 660.dp
        val rows =
            when {
                compact -> RadioRows.COMPACT
                wide -> RadioRows.WIDE
                else -> RadioRows.STANDARD
            }
        val scale = LocalDensity.current.fontScale
        // The deck spans the cabinet, its height following the width up to a share of the window's
        // height; beyond that its chassis grows longer instead. It shows whenever the window is tall
        // enough for it at all (the tubes stay on every screen, owner decision 2026-11-17): the
        // cabinet takes the rest, and the whole radio scrolls when that falls short of the cabinet's
        // minimum.
        val deckWidth = width - (if (narrow) 20.dp else 24.dp) + 18.dp
        val deckHeight = minOf(deckWidth / VALVE_DECK_ASPECT, height * DECK_HEIGHT_SHARE)
        val deckVisible = height >= DECK_MIN_HEIGHT
        val minimumHeight =
            when {
                wide -> 280.dp
                compact -> 440.dp
                else -> 500.dp
            } * scale
        val contentHeight = maxOf(height, minimumHeight + if (deckVisible) deckHeight else 0.dp)
        val needsScroll = contentHeight > height
        Column(if (needsScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
            Column(Modifier.height(contentHeight).fillMaxWidth().padding(horizontal = if (narrow) 10.dp else 12.dp)) {
                if (deckVisible) {
                    ValveDeck(
                        Modifier
                            .testTag("radio_valve_deck")
                            .requiredWidth(
                                deckWidth,
                            ).height(deckHeight)
                            .align(Alignment.CenterHorizontally)
                            .offset(y = 5.dp),
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 22.dp)) {
                    MaterialPlate(
                        RadioMaterial.WOOD,
                        Modifier
                            .fillMaxSize()
                            .shadow(
                                8.dp,
                                RoundedCornerShape(5.dp),
                            ).clip(RoundedCornerShape(5.dp))
                            .cabinetEdges(),
                    ) {
                        val inset = if (narrow) 10.dp else 12.dp
                        if (wide) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                RadioProgramme(
                                    state,
                                    actions,
                                    programme,
                                    scroll,
                                    compact,
                                    rows,
                                    Modifier.weight(1.15f).fillMaxHeight(),
                                )
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Speaker(
                                            Modifier.fillMaxWidth().heightIn(max = 220.dp).fillMaxHeight(),
                                            large = true,
                                        )
                                    }
                                    RadioPlayer(
                                        state,
                                        actions,
                                        compact,
                                        short,
                                    )
                                }
                            }
                        } else {
                            Column(
                                Modifier.fillMaxSize().padding(
                                    horizontal = inset,
                                    vertical = if (compact) 10.dp else 12.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp),
                            ) {
                                Speaker(
                                    Modifier.fillMaxWidth().height(
                                        if (short) {
                                            14.dp
                                        } else if (compact) {
                                            16.dp
                                        } else {
                                            20.dp
                                        },
                                    ),
                                )
                                RadioProgramme(
                                    state,
                                    actions,
                                    programme,
                                    scroll,
                                    compact,
                                    rows,
                                    Modifier.fillMaxWidth().weight(1f),
                                )
                                RadioPlayer(
                                    state,
                                    actions,
                                    compact,
                                    short,
                                )
                            }
                        }
                    }
                    // The plinth overlaps the cabinet; feet project into the space below it.
                    Box(
                        Modifier
                            .align(
                                Alignment.BottomStart,
                            ).offset(30.dp, 14.dp)
                            .size(30.dp, 10.dp)
                            .background(Chassis),
                    )
                    Box(
                        Modifier
                            .align(
                                Alignment.BottomEnd,
                            ).offset((-30).dp, 14.dp)
                            .size(30.dp, 10.dp)
                            .background(Chassis),
                    )
                    MaterialPlate(
                        RadioMaterial.WOOD_EDGE,
                        Modifier
                            .align(
                                Alignment.BottomCenter,
                            ).offset(y = 7.dp)
                            .requiredWidth(width - (if (narrow) 20.dp else 24.dp) + 14.dp)
                            .height(15.dp)
                            .cabinetEdges(),
                    ) {
                    }
                }
            }
        }
    }
}

// The shortest window that shows the deck, as on every other page, and the largest share of the
// window's height the deck takes.
private val DECK_MIN_HEIGHT = 560.dp
private const val DECK_HEIGHT_SHARE = 0.22f
