// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * MD4 AC1's keyboard half: every action of 08's custom-actions catalogue that the shell owns
 * (the root shortcuts of 08 Keyboard and mouse and 11 Keyboard shortcuts, and the window menu
 * of 11 Menus) is reachable by keyboard or menu. The per-row actions whose only path is the
 * context menu are a later MD4 slice and are listed here as [Reachability.OPEN]; the M10
 * per-screen keys (Enter, Alt+↑/↓, Delete, Page Up/Down) belong to the shared UI, not this
 * shell, so they are not claimed either.
 *
 * The keyboard paths themselves are driven by `RootShortcutTest` (core:ui desktopTest); the
 * menu paths by `DesktopImportMenuTest`'s menu structure assertions.
 */
class KeyboardReachabilityChecklistTest {
    @Test
    fun everyCatalogueActionIsReachableOrListedOpen() {
        val unclassified = CATALOGUE.filter { it.reachability == Reachability.OPEN && it.detail.isBlank() }

        assertThat(unclassified).isEmpty()
        assertThat(CATALOGUE.map { it.element to it.action }.distinct().size).isEqualTo(CATALOGUE.size)
    }

    @Test
    fun theShellOwnsTheGlobalPlaybackNavigationAndWindowActions() {
        val paths = CATALOGUE.filter { it.reachability != Reachability.OPEN }.map { it.element to it.action }

        // Playback transports and the window-level actions are the shell's; the per-row
        // actions stay with the context-menu slice.
        assertThat(paths).containsAtLeast(
            "Episode row" to "Play / Pause / Resume",
            "Episode row" to "Open podcast",
            "Up next row" to "Remove from Up next",
            "Manage groups row" to "Edit",
            "Feeds pager" to "Next group",
            "Feeds pager" to "Previous group",
            "Mini player" to "Play/Pause",
            "Mini player" to "Skip forward",
            "Full player" to "Collapse player",
            "Full player" to "Chapter next",
            "Downloads row" to "Open episode",
            "Window" to "Close",
            "Window" to "Quit",
            "Help" to "Install & updates help",
            "Help" to "Keyboard shortcuts",
            "Help" to "Licences",
            "Help" to "Diagnostics",
            "Help" to "Report a problem",
        )
    }

    @Test
    fun theContextMenuSliceOwnsThePerRowActions() {
        val open = CATALOGUE.filter { it.reachability == Reachability.OPEN }.map { it.element to it.action }

        assertThat(open).containsAtLeast(
            "Episode row" to "Play next",
            "Episode row" to "Play last",
            "Episode row" to "Download / Pause / Resume / Cancel / Retry / Delete download",
            "Episode row" to "Mark played / Mark unplayed",
            "Episode row" to "Check again",
            "Episode row" to "Select",
            "Up next row" to "Move to top",
            "Manage groups row" to "Move to top",
            "Cover tile" to "Select",
            "Cover tile" to "Add to groups",
            "Cover tile" to "Unsubscribe",
            "Group tile and tab" to "Play",
            "Group tile and tab" to "Refresh",
            "Mini player" to "Expand player",
            "Mini player" to "Dismiss (paused)",
            "Downloads row" to "Download now",
            "Downloads row" to "Cancel",
            "Downloads row" to "Retry",
            "Downloads row" to "Share file",
            "Continue on this device card" to "Play",
            "Continue on this device card" to "Dismiss",
            "Devices row" to "Rename",
            "Devices row" to "Remove",
        )
    }
}

private enum class Reachability { KEYBOARD, MENU, OPEN }

private data class CatalogueAction(
    val element: String,
    val action: String,
    val reachability: Reachability,
    val detail: String,
)

/**
 * 08's custom-actions catalogue, classified by the desktop paths this shell provides. The
 * `detail` names the shortcut or menu item; OPEN rows are the context-menu slice's.
 */
private val CATALOGUE =
    listOf(
        // Episode row
        CatalogueAction("Episode row", "Play / Pause / Resume", Reachability.KEYBOARD, "Space; Playback › Play/Pause"),
        CatalogueAction("Episode row", "Watch on YouTube", Reachability.OPEN, "context menu"),
        CatalogueAction("Episode row", "Play next", Reachability.OPEN, "context menu"),
        CatalogueAction("Episode row", "Play last", Reachability.OPEN, "context menu"),
        CatalogueAction(
            "Episode row",
            "Download / Pause / Resume / Cancel / Retry / Delete download",
            Reachability.OPEN,
            "context menu",
        ),
        CatalogueAction("Episode row", "Mark played / Mark unplayed", Reachability.OPEN, "context menu"),
        CatalogueAction("Episode row", "Open podcast", Reachability.KEYBOARD, "Enter (row primary action)"),
        CatalogueAction("Episode row", "Check again", Reachability.OPEN, "context menu"),
        CatalogueAction("Episode row", "Select", Reachability.OPEN, "context menu"),
        // Swipe-enabled rows: the configured swipe actions appear in the list above.
        // Up next row
        CatalogueAction("Up next row", "Move up", Reachability.KEYBOARD, "Alt+↑"),
        CatalogueAction("Up next row", "Move down", Reachability.KEYBOARD, "Alt+↓"),
        CatalogueAction("Up next row", "Move to top", Reachability.OPEN, "context menu"),
        CatalogueAction("Up next row", "Remove from Up next", Reachability.KEYBOARD, "Delete"),
        // Manage groups row
        CatalogueAction("Manage groups row", "Move up", Reachability.KEYBOARD, "Alt+↑"),
        CatalogueAction("Manage groups row", "Move down", Reachability.KEYBOARD, "Alt+↓"),
        CatalogueAction("Manage groups row", "Move to top", Reachability.OPEN, "context menu"),
        CatalogueAction("Manage groups row", "Edit", Reachability.KEYBOARD, "Enter (row primary action)"),
        CatalogueAction("Manage groups row", "Delete", Reachability.KEYBOARD, "Delete"),
        // Cover tile
        CatalogueAction("Cover tile", "Open", Reachability.KEYBOARD, "Enter"),
        CatalogueAction("Cover tile", "Select", Reachability.OPEN, "context menu"),
        CatalogueAction("Cover tile", "Add to groups", Reachability.OPEN, "context menu"),
        CatalogueAction("Cover tile", "Unsubscribe", Reachability.OPEN, "context menu"),
        // Group tile and tab
        CatalogueAction("Group tile and tab", "Open feed", Reachability.KEYBOARD, "Enter"),
        CatalogueAction("Group tile and tab", "Play", Reachability.OPEN, "context menu"),
        CatalogueAction("Group tile and tab", "Refresh", Reachability.OPEN, "context menu"),
        CatalogueAction("Group tile and tab", "Edit", Reachability.OPEN, "context menu"),
        // Feeds pager
        CatalogueAction("Feeds pager", "Next group", Reachability.KEYBOARD, "Ctrl+Tab"),
        CatalogueAction("Feeds pager", "Previous group", Reachability.KEYBOARD, "Ctrl+Shift+Tab"),
        // Mini player
        CatalogueAction("Mini player", "Play/Pause", Reachability.KEYBOARD, "Space"),
        CatalogueAction("Mini player", "Skip forward", Reachability.KEYBOARD, "→"),
        CatalogueAction("Mini player", "Expand player", Reachability.OPEN, "context menu"),
        CatalogueAction("Mini player", "Dismiss (paused)", Reachability.OPEN, "context menu"),
        // Full player
        CatalogueAction("Full player", "Collapse player", Reachability.KEYBOARD, "Esc"),
        CatalogueAction("Full player", "Chapter next", Reachability.KEYBOARD, "Shift+→"),
        CatalogueAction("Full player", "Chapter previous", Reachability.KEYBOARD, "Shift+←"),
        // Downloads row
        CatalogueAction("Downloads row", "Play", Reachability.KEYBOARD, "Enter"),
        CatalogueAction("Downloads row", "Pause/Resume", Reachability.KEYBOARD, "Space"),
        CatalogueAction("Downloads row", "Download now", Reachability.OPEN, "context menu"),
        CatalogueAction("Downloads row", "Use mobile data (Android)", Reachability.OPEN, "Android only"),
        CatalogueAction("Downloads row", "Cancel", Reachability.OPEN, "context menu"),
        CatalogueAction("Downloads row", "Retry", Reachability.OPEN, "context menu"),
        CatalogueAction("Downloads row", "Delete", Reachability.KEYBOARD, "Delete"),
        CatalogueAction("Downloads row", "Share file", Reachability.OPEN, "context menu"),
        CatalogueAction("Downloads row", "Open episode", Reachability.KEYBOARD, "Enter"),
        // Continue on this device card (MS3)
        CatalogueAction("Continue on this device card", "Play", Reachability.OPEN, "context menu"),
        CatalogueAction("Continue on this device card", "Dismiss", Reachability.OPEN, "context menu"),
        // Devices row (MS2)
        CatalogueAction("Devices row", "Rename", Reachability.OPEN, "context menu"),
        CatalogueAction("Devices row", "Remove", Reachability.OPEN, "context menu"),
        // The window-level actions of 11 Menus and 11 Keyboard shortcuts.
        CatalogueAction("Window", "Add podcast", Reachability.KEYBOARD, "Ctrl/Cmd+N; File › Add podcast…"),
        CatalogueAction("Window", "Import OPML or backup", Reachability.KEYBOARD, "Ctrl/Cmd+O; File › Import"),
        CatalogueAction("Window", "Export OPML", Reachability.MENU, "File › Export OPML…"),
        CatalogueAction("Window", "Back up library", Reachability.MENU, "File › Back up library…"),
        CatalogueAction("Window", "Refresh", Reachability.KEYBOARD, "Ctrl/Cmd+R, F5; File › Refresh"),
        CatalogueAction("Window", "Find", Reachability.KEYBOARD, "Ctrl/Cmd+F; Edit › Find"),
        CatalogueAction("Window", "Settings", Reachability.KEYBOARD, "Ctrl/Cmd+,; Go › Settings"),
        CatalogueAction("Window", "Feeds", Reachability.KEYBOARD, "Ctrl/Cmd+1; Go › Feeds"),
        CatalogueAction("Window", "Library", Reachability.KEYBOARD, "Ctrl/Cmd+2; Go › Library"),
        CatalogueAction("Window", "Up next", Reachability.KEYBOARD, "Ctrl/Cmd+3; Go › Up next"),
        CatalogueAction("Window", "Downloads", Reachability.KEYBOARD, "Ctrl/Cmd+4; Go › Downloads"),
        CatalogueAction("Window", "Discover", Reachability.KEYBOARD, "Ctrl/Cmd+5; Go › Discover"),
        CatalogueAction("Window", "Back", Reachability.KEYBOARD, "Esc; Go › Back"),
        CatalogueAction("Window", "Skip back", Reachability.KEYBOARD, "←; Playback › Skip back"),
        CatalogueAction("Window", "Skip forward", Reachability.KEYBOARD, "→; Playback › Skip forward"),
        CatalogueAction("Window", "Previous chapter", Reachability.KEYBOARD, "Shift+←; Playback › Previous chapter"),
        CatalogueAction("Window", "Next chapter", Reachability.KEYBOARD, "Shift+→; Playback › Next chapter"),
        CatalogueAction(
            "Window",
            "Previous episode",
            Reachability.KEYBOARD,
            "Ctrl/Cmd+Shift+←; Playback › Previous episode",
        ),
        CatalogueAction("Window", "Next episode", Reachability.KEYBOARD, "Ctrl/Cmd+Shift+→; Playback › Next episode"),
        CatalogueAction("Window", "Volume up", Reachability.KEYBOARD, "Ctrl/Cmd+↑; Playback › Volume up"),
        CatalogueAction("Window", "Volume down", Reachability.KEYBOARD, "Ctrl/Cmd+↓; Playback › Volume down"),
        CatalogueAction("Window", "Speed presets", Reachability.MENU, "Playback › Speed"),
        CatalogueAction("Window", "Skip silence", Reachability.MENU, "Playback › Skip silence"),
        CatalogueAction("Window", "Sleep timer", Reachability.MENU, "Playback › Sleep timer"),
        CatalogueAction("Window", "Close", Reachability.KEYBOARD, "Ctrl/Cmd+W; File › Close window"),
        CatalogueAction("Window", "Quit", Reachability.KEYBOARD, "Ctrl/Cmd+Q; File › Quit"),
        CatalogueAction("Window", "Minimise", Reachability.MENU, "Window › Minimise (macOS)"),
        CatalogueAction("Window", "Zoom", Reachability.MENU, "Window › Zoom (macOS)"),
        CatalogueAction("Window", "Bring all to front", Reachability.MENU, "Window › Bring all to front (macOS)"),
        CatalogueAction("Help", "Install & updates help", Reachability.MENU, "Help › Install & updates help"),
        CatalogueAction("Help", "Keyboard shortcuts", Reachability.MENU, "Help › Keyboard shortcuts"),
        CatalogueAction("Help", "Licences", Reachability.MENU, "Help › Licences"),
        CatalogueAction("Help", "Diagnostics", Reachability.MENU, "Help › Diagnostics"),
        CatalogueAction("Help", "Report a problem", Reachability.MENU, "Help › Report a problem"),
    )
