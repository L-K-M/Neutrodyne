// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.compose.runtime.Composable

/**
 * Overlay metadata understood by the host's scene strategies. Sheet and dialog keys are pushed on
 * the selected tab's stack and rendered above the root `PlayerSheet` through window-based surfaces.
 */
public object NdSceneMetadata {
    public const val KEY_OVERLAY: String = "nd.overlay"

    /** Metadata for bottom-sheet keys (`AddPodcastKey`, `SpeedKey`, …). */
    public fun bottomSheet(): Map<String, Any> = mapOf(KEY_OVERLAY to OVERLAY_SHEET)

    /** Metadata for dialog keys (`ExportKey`, `VerificationNoticeKey`, …). */
    public fun dialog(): Map<String, Any> = mapOf(KEY_OVERLAY to OVERLAY_DIALOG)

    /** Overlay kind stored under [KEY_OVERLAY]; read by `:core:ui`'s overlay scene strategies. */
    public const val OVERLAY_SHEET: String = "sheet"
    public const val OVERLAY_DIALOG: String = "dialog"

    /**
     * Pane-role marker stored on an entry (08 Screen inventory). `:core:navigation` cannot see
     * `adaptive-navigation3`, so installers record the role with [paneList]/[paneDetail]/
     * [paneExtra] and the shared host translates them into `ListDetailSceneStrategy`'s metadata
     * with a per-tab `sceneKey` — without that, two tabs' list entries would merge into one scene.
     */
    public const val KEY_PANE: String = "nd.pane"

    /** A composable drawn as the detail placeholder while a lone list entry fills ≥ 2 panes. */
    public const val KEY_DETAIL_PLACEHOLDER: String = "nd.detailPlaceholder"

    public const val PANE_LIST: String = "list"
    public const val PANE_DETAIL: String = "detail"
    public const val PANE_EXTRA: String = "extra"

    /** Marks a list-pane key; [detailPlaceholder] renders when it is the scene's only list. */
    public fun paneList(detailPlaceholder: (@Composable () -> Unit)? = null): Map<String, Any> =
        if (detailPlaceholder == null) {
            mapOf(KEY_PANE to PANE_LIST)
        } else {
            mapOf(KEY_PANE to PANE_LIST, KEY_DETAIL_PLACEHOLDER to detailPlaceholder)
        }

    /** Marks a detail-pane key (`PodcastKey`, `SettingsKey`, …). */
    public fun paneDetail(): Map<String, Any> = mapOf(KEY_PANE to PANE_DETAIL)

    /** Marks an extra-pane key (`EpisodeKey`). */
    public fun paneExtra(): Map<String, Any> = mapOf(KEY_PANE to PANE_EXTRA)
}
