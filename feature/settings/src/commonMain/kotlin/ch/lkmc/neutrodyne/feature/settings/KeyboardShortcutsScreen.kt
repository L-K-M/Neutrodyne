// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_add_podcast
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_back
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_close
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_close_window
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_discover
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_downloads
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_feeds
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_find
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_group_library
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_group_navigation
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_group_playback
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_group_window
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_import
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_library
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_next_chapter
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_next_episode
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_next_feed_page
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_play_pause
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_previous_chapter
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_previous_episode
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_previous_feed_page
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_quit
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_refresh
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_settings
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_skip_back
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_skip_forward
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_title
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_up_next
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_volume_down
import ch.lkmc.neutrodyne.feature.settings.resources.shortcuts_volume_up
import org.jetbrains.compose.resources.stringResource

/**
 * Help › Keyboard shortcuts (08 Keyboard and mouse, 11 Keyboard shortcuts): the desktop's global
 * chords in the four design groups, with the platform's modifier names. It is a navigation-level
 * dialog, so it closes through the navigator like the other overlay dialogs. [desktopOs] is null
 * off the desktop, where the key is never pushed; the names then fall back to the non-macOS set.
 */
@Composable
internal fun KeyboardShortcutsScreen(desktopOs: DesktopOs?) {
    val navigator = LocalAppNavigator.current
    val close = stringResource(Res.string.shortcuts_close)

    NdDialog(
        onDismissRequest = { navigator.pop() },
        title = stringResource(Res.string.shortcuts_title),
        confirm = NdDialogAction(close) { navigator.pop() },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (group in shortcutGroups(desktopOs)) {
                Text(
                    group.heading,
                    Modifier
                        .testTag(group.tag)
                        .padding(top = 12.dp, bottom = 4.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                )
                for (row in group.rows) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(row.action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(row.chord, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The chords of 11's list, grouped as 08 names them. */
@Composable
private fun shortcutGroups(desktopOs: DesktopOs?): List<ShortcutGroup> {
    val mac = desktopOs == DesktopOs.MACOS

    fun ctrl(key: String) = chord(mac, key, command = true)

    fun ctrlShift(key: String) = chord(mac, key, command = true, shift = true)

    fun shift(key: String) = chord(mac, key, shift = true)

    return listOf(
        ShortcutGroup(
            tag = "shortcuts_group_playback",
            heading = stringResource(Res.string.shortcuts_group_playback),
            rows =
                listOf(
                    ShortcutRow(stringResource(Res.string.shortcuts_play_pause), "Space"),
                    ShortcutRow(stringResource(Res.string.shortcuts_skip_back), "← / ${ctrl("←")}"),
                    ShortcutRow(stringResource(Res.string.shortcuts_skip_forward), "→ / ${ctrl("→")}"),
                    ShortcutRow(stringResource(Res.string.shortcuts_previous_chapter), shift("←")),
                    ShortcutRow(stringResource(Res.string.shortcuts_next_chapter), shift("→")),
                    ShortcutRow(stringResource(Res.string.shortcuts_previous_episode), ctrlShift("←")),
                    ShortcutRow(stringResource(Res.string.shortcuts_next_episode), ctrlShift("→")),
                    ShortcutRow(stringResource(Res.string.shortcuts_volume_up), ctrl("↑")),
                    ShortcutRow(stringResource(Res.string.shortcuts_volume_down), ctrl("↓")),
                ),
        ),
        ShortcutGroup(
            tag = "shortcuts_group_navigation",
            heading = stringResource(Res.string.shortcuts_group_navigation),
            rows =
                listOf(
                    ShortcutRow(stringResource(Res.string.shortcuts_find), ctrl("F")),
                    ShortcutRow(stringResource(Res.string.shortcuts_feeds), ctrl("1")),
                    ShortcutRow(stringResource(Res.string.shortcuts_library), ctrl("2")),
                    ShortcutRow(stringResource(Res.string.shortcuts_up_next), ctrl("3")),
                    ShortcutRow(stringResource(Res.string.shortcuts_downloads), ctrl("4")),
                    ShortcutRow(stringResource(Res.string.shortcuts_discover), ctrl("5")),
                    ShortcutRow(stringResource(Res.string.shortcuts_settings), ctrl(",")),
                    // 11 lists the Feeds paging chords as Ctrl+Tab even on macOS.
                    ShortcutRow(stringResource(Res.string.shortcuts_next_feed_page), chord(mac, "Tab", control = true)),
                    ShortcutRow(
                        stringResource(Res.string.shortcuts_previous_feed_page),
                        chord(mac, "Tab", control = true, shift = true),
                    ),
                    ShortcutRow(stringResource(Res.string.shortcuts_back), "Esc"),
                ),
        ),
        ShortcutGroup(
            tag = "shortcuts_group_library",
            heading = stringResource(Res.string.shortcuts_group_library),
            rows =
                listOf(
                    ShortcutRow(stringResource(Res.string.shortcuts_add_podcast), ctrl("N")),
                    ShortcutRow(stringResource(Res.string.shortcuts_import), ctrl("O")),
                    ShortcutRow(stringResource(Res.string.shortcuts_refresh), "${ctrl("R")} / F5"),
                ),
        ),
        ShortcutGroup(
            tag = "shortcuts_group_window",
            heading = stringResource(Res.string.shortcuts_group_window),
            rows =
                listOf(
                    ShortcutRow(stringResource(Res.string.shortcuts_close_window), ctrl("W")),
                    ShortcutRow(stringResource(Res.string.shortcuts_quit), ctrl("Q")),
                ),
        ),
    )
}

private data class ShortcutGroup(
    val tag: String,
    val heading: String,
    val rows: List<ShortcutRow>,
)

private data class ShortcutRow(
    val action: String,
    val chord: String,
)

/**
 * A chord in the platform's notation (08 Navigation: "Library (Ctrl+2)", "⌘2" on macOS). [command]
 * is Cmd on macOS and Ctrl elsewhere; [control] is Ctrl everywhere. macOS writes the symbols
 * without separators in its menu order (⌃⇧⌘); the others join words with "+".
 */
private fun chord(
    mac: Boolean,
    key: String,
    command: Boolean = false,
    control: Boolean = false,
    shift: Boolean = false,
): String {
    if (mac) {
        return buildString {
            if (control) append("⌃")
            if (shift) append("⇧")
            if (command) append("⌘")
            append(key)
        }
    }
    val words =
        buildList {
            if (command || control) add("Ctrl")
            if (shift) add("Shift")
            add(key)
        }
    return words.joinToString("+")
}
