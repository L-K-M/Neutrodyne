// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.radio_valve_deck
import org.jetbrains.compose.resources.painterResource

public data class RadioActions(
    val selectEpisode: (Long) -> Unit = {},
    val discard: (Long) -> Unit = {},
    val undo: () -> Unit = {},
    val togglePlay: () -> Unit = {},
    val seek: (Long) -> Unit = {},
    val selectPlaylist: (Long) -> Unit = {},
    val createPlaylist: (String, Set<Long>) -> Unit = { _, _ -> },
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
        val scale = LocalDensity.current.fontScale
        // Always calculate against the unhidden deck, independent of content and the last result.
        val deckWidth = width - (if (narrow) 20.dp else 24.dp) + 18.dp
        val naturalDeck = deckWidth * (423f / 1100f)
        val playerBudget = if (wide) 0.dp else (if (compact) 207.dp else 240.dp) * scale
        val programmeBudget = (if (compact) 220.dp else 250.dp) * scale
        val deckVisible = height >= 660.dp && height - naturalDeck - playerBudget - 80.dp >= programmeBudget
        val tooShort = !wide && height < 480.dp
        Column(if (tooShort) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
            val contentHeight = if (tooShort) 500.dp * scale else height
            Column(Modifier.height(contentHeight).fillMaxWidth().padding(horizontal = if (narrow) 10.dp else 12.dp)) {
                if (deckVisible) {
                    Image(
                        painterResource(Res.drawable.radio_valve_deck),
                        null,
                        Modifier
                            .requiredWidth(
                                deckWidth,
                            ).height(naturalDeck)
                            .align(Alignment.CenterHorizontally)
                            .offset(y = 5.dp),
                        contentScale = ContentScale.Fit,
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
                                    RadioPlayer(state, actions, compact, short)
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
                                    Modifier.fillMaxWidth().weight(1f),
                                )
                                RadioPlayer(state, actions, compact, short)
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
