// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import kotlinx.coroutines.flow.Flow

/**
 * What the start-up gate renders (01 Application start-up; 08 Banners and the startup gate). The
 * shell maps `DatabaseOpener.awaitOpen()` onto this; the root renders only [Ready] through to the
 * app UI, so no repository or ViewModel exists while the database is closed.
 */
public sealed interface StartupGateState {
    /** The database is still opening; the gate shows an indeterminate loader. */
    public data object Pending : StartupGateState

    /** The database is open; the app UI renders. */
    public data object Ready : StartupGateState

    /** The database was repaired or restored while opening; the gate opens once and [cause] shows once. */
    public data class Recovered(val cause: UiText) : StartupGateState

    /** Opening failed; the gate keeps the error variant and "Try again" re-runs `awaitOpen()`. */
    public data class Failed(val reason: StartupFailure) : StartupGateState
}

/** The failure kinds the gate can word (02 `DatabaseOpenException.Reason`, mapped by the shell). */
public enum class StartupFailure {
    DISK_FULL,
    IO,
    UNKNOWN,
}

/** A root-level notice the shell's `UpdateNotices` asks the user about (01 root contract). */
public enum class RootNotice {
    /** The first-run card explaining the daily GitHub update check (08 priority 8). */
    FIRST_RUN_CHOICE,

    /** Android's developer-verification enforcement notice (M11a). */
    VERIFICATION_ENFORCEMENT,
}

/** The held-changes banner, mapped by the shell from `SyncController.heldChanges` (MS2). */
@Immutable
public data class HeldChangesBanner(
    /** The held batch id; "Review" pushes `SyncHeldChangesKey(id)`. */
    val id: Long,
    /** The device that staged the removals; null renders the generic wording. */
    val deviceName: String?,
    val podcastCount: Int,
    val groupCount: Int,
)

/** The "Continue on this device" card, mapped from `SyncController.remoteSession` (MS3). */
@Immutable
public data class RemoteSessionCard(
    val title: String,
    val podcastTitle: String,
    /** The remote position, formatted for display ("23:14") by the shell. */
    val positionText: String,
    val deviceName: String,
)

/** What the `player` slot needs: the current pane layout and how to navigate from the player. */
@Immutable
public data class PlayerSlotState(
    /** The pane layout the root currently renders; the slot chooses sheet vs side panel from it. */
    val layout: PaneLayout,
    /** Pushes a key onto the selected tab's stack (podcast link, Go to episode, …). */
    val navigate: (NavKey) -> Unit,
)

/** Root-level key chords dispatched to the shell's playback actions (08 Keyboard and mouse). */
public enum class PlaybackKey {
    /** Space: play/pause. */
    TOGGLE,

    /** ← / → (or Ctrl+← / Ctrl+→): skip back / forward by the configured intervals. */
    SKIP_BACK,
    SKIP_FORWARD,

    /** Shift+← / Shift+→: previous / next chapter. */
    PREVIOUS_CHAPTER,
    NEXT_CHAPTER,

    /** Ctrl/Cmd+Shift+← / →: previous / next episode. */
    PREVIOUS_EPISODE,
    NEXT_EPISODE,

    /** Ctrl/Cmd+↑ / ↓: desktop in-app volume. */
    VOLUME_UP,
    VOLUME_DOWN,

    /** Ctrl+Tab / Ctrl+Shift+Tab: next / previous Feeds page. */
    NEXT_FEED_PAGE,
    PREVIOUS_FEED_PAGE,
}

/**
 * The root's callbacks (01 root contract). The shells wire them to `StartupViewModel.retry()`,
 * `UpdateNotices.dismiss`, `SyncController.dismissRemoteSession()`, `PlaybackController.play()`
 * and 08's key dispatch.
 */
public class RootActions(
    public val retryStartup: () -> Unit,
    public val dismissNotice: (RootNotice) -> Unit,
    /** "Continue on this device" — the shell wires this to the resume path. */
    public val continueHere: () -> Unit,
    public val dismissRemoteSession: () -> Unit,
    /** Dispatches a root key chord; returns whether the action ran (for event consumption). */
    public val playbackKey: (PlaybackKey) -> Boolean,
    /**
     * The failed gate's storage action: Android runs `ACTION_MANAGE_STORAGE`, the desktop reveals
     * the data folder (08 Banners and the startup gate). `null` hides the button.
     */
    public val manageStorage: (() -> Unit)? = null,
    /** The failed gate's report action: the shell calls 09's `reportNonFatal`. `null` hides it. */
    public val reportStartupFailure: (() -> Unit)? = null,
    /**
     * The first-run card's "Turn off" — the shell writes `updates.check_enabled = false` (M11a);
     * the card's "OK" uses [dismissNotice].
     */
    public val disableUpdateChecks: () -> Unit = {},
)

/**
 * The slots the shells fill: [player] is `:feature:player`'s `PlayerSheet`/`PlayerSidePanel` (the
 * shells obtain `PlayerViewModel` themselves — the root never sees it) and [userMessages] is the
 * merged snackbar flow (`SyncController.notices`, download results, …).
 */
public class RootSlots(
    public val player: @Composable (PlayerSlotState) -> Unit,
    public val userMessages: Flow<UserMessage>,
)

/** Everything the root needs that is not a callback or a slot (01 root contract). */
@Immutable
public data class RootUiState(
    val startup: StartupGateState,
    /** The update badge on the Settings gear (08 Settings gear badge). */
    val settingsBadge: Boolean,
    val notice: RootNotice?,
    val heldChanges: HeldChangesBanner?,
    val remoteSession: RemoteSessionCard?,
    /**
     * Whether a `NowPlaying` exists — `ndPaneLayout` chooses the side panel vs the sheet from it
     * (08 Adaptive layouts). Mapped by the shell from `PlaybackStateSource.nowPlaying != null`.
     */
    val hasNowPlaying: Boolean = false,
    /** `ui.player_panel_hidden` — the side panel's close button sets it (08 Desktop windows). */
    val playerPanelHidden: Boolean = false,
) {
    public companion object {
        /** A ready gate with no badge or notices; the M0a default and the tests' base. */
        public val READY: RootUiState = RootUiState(
            startup = StartupGateState.Ready,
            settingsBadge = false,
            notice = null,
            heldChanges = null,
            remoteSession = null,
        )
    }
}
