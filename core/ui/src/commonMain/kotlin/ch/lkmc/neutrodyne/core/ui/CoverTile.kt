// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.NdBadge
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.tile_gone
import ch.lkmc.neutrodyne.core.ui.resources.tile_moved
import ch.lkmc.neutrodyne.core.ui.resources.tile_needs_password
import ch.lkmc.neutrodyne.core.ui.resources.tile_new_episodes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A labelled tile action: a resolved [label] (the caller's `stringResource` result) plus the
 * callback — the tile shows them as the overflow menu and mirrors them as custom actions (08's
 * context-menu/custom-action parity).
 */
public data class TileMenuAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The Library grid's cover tile (08 CoverTile): square `CoverArt` with 12 dp corners, the unplayed
 * `NdBadge` top-end, the health badge bottom-end (pending ring / `lock` / `link_off` /
 * `error_outline`), the `smart_display` YouTube glyph bottom-start, and a title line when
 * [showTitle] is on (`appearance.library_titles`). Selection (M2) draws the scrim/check/scale;
 * a secondary click or the hover/focus overflow opens [menuActions].
 */
@Composable
public fun CoverTile(
    tile: LibraryTile,
    showTitle: Boolean,
    selected: Boolean?,
    menuActions: List<TileMenuAction>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    val description =
        if (showTitle) {
            null
        } else {
            buildTileDescription(tile)
        }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .hoverable(interactionSource)
                    .onFocusChanged { focused = it.isFocused }
                    .onSecondaryClick { if (menuActions.isNotEmpty()) menuOpen = true }.semantics(mergeDescendants = true) {
                        if (description != null) contentDescription = description
                        customActions =
                            menuActions.map { action ->
                                CustomAccessibilityAction(action.label) {
                                    action.onClick()
                                    true
                                }
                            }
                    }.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            CoverArt(
                ref = tile.artwork,
                monogram = rememberMonogram(tile.displayTitle),
                title = tile.displayTitle,
                avgArgb = tile.artworkAvgArgb,
                shape = NeutrodyneShapes.Tile,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .let { m -> if (selected == true) m.scale(SELECTED_SCALE) else m },
            )

            if (selected != null) {
                // Selection mode (M2): scrim + check, scale applied to the art.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(NeutrodyneShapes.Tile)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
                )
                if (selected) {
                    Icon(
                        NdIcons.CheckCircleFilled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(TILE_BADGE_PAD)
                                .size(TILE_ICON_SIZE),
                    )
                }
            } else {
                if (tile.unplayedCount > 0) {
                    NdBadge(
                        count = tile.unplayedCount,
                        modifier = Modifier.align(Alignment.TopEnd).padding(TILE_BADGE_PAD),
                    )
                }
                StatusBadge(tile, Modifier.align(Alignment.BottomEnd).padding(TILE_BADGE_PAD))
                if (tile.sourceType != SourceType.RSS) {
                    Icon(
                        NdIcons.SmartDisplay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(TILE_BADGE_PAD)
                                .size(TILE_ICON_SIZE),
                    )
                }
            }

            if (hovered || focused || menuOpen) {
                if (menuActions.isNotEmpty()) {
                    NdIconButton(
                        onClick = { menuOpen = true },
                        icon = NdIcons.MoreVert,
                        contentDescription = stringResource(Res.string.action_more),
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(TILE_BADGE_PAD)
                                .size(TILE_ICON_TOUCH),
                    )
                }
            }
            Box(Modifier.align(Alignment.TopStart)) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuActions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }

        if (showTitle) {
            Text(
                tile.displayTitle,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TILE_TITLE_GAP),
            )
        }
    }
}

/** "{title}, {n} unplayed{, needs a password}" — read when the title is hidden (08). */
@Composable
private fun buildTileDescription(tile: LibraryTile): String {
    val parts = mutableListOf(tile.displayTitle)
    if (tile.unplayedCount > 0) {
        parts +=
            pluralStringResource(Res.plurals.tile_new_episodes, tile.unplayedCount, tile.unplayedCount)
    }
    when {
        tile.health.needsCredentials -> parts += stringResource(Res.string.tile_needs_password)
        tile.health.gone -> parts += stringResource(Res.string.tile_gone)
        tile.health.possiblyDead -> parts += stringResource(Res.string.tile_moved)
    }
    return parts.joinToString(", ")
}

/** 08's bottom-end status badge: pending ring → credentials lock → gone → possibly-dead. */
@Composable
private fun StatusBadge(
    tile: LibraryTile,
    modifier: Modifier,
) {
    when {
        tile.status == PodcastStatus.PENDING_FIRST_FETCH ->
            CircularProgressIndicator(modifier = modifier.size(TILE_ICON_SIZE))
        tile.health.needsCredentials ->
            Icon(
                NdIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = modifier.size(TILE_ICON_SIZE),
            )
        tile.health.gone ->
            Icon(
                NdIcons.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(TILE_ICON_SIZE),
            )
        tile.health.possiblyDead ->
            Icon(
                NdIcons.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(TILE_ICON_SIZE),
            )
    }
}

private val TILE_BADGE_PAD = 6.dp
private val TILE_ICON_SIZE = 20.dp
private val TILE_ICON_TOUCH = 40.dp
private val TILE_TITLE_GAP = 4.dp
private const val SCRIM_ALPHA = 0.35f
private const val SELECTED_SCALE = 0.92f
