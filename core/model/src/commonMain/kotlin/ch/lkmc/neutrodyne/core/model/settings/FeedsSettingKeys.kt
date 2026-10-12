// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.persistentListOf

/**
 * 03 "Keys owned here": the `feeds.*` keys (refresh, paging, show notes, diagnostics). The refresh
 * preferences live in the portable `settings` file but never sync (PO-37's device-local cadence;
 * 03 Settings); only the show-notes and backfill choices sync (MS2). The `scheduled_tick_*` and
 * `last_run_*` internals are 03's diagnostics state, device-local by definition.
 */
object FeedsSettingKeys {
    /**
     * Settings › Feeds › refresh interval, in minutes; `0` is "Manual only" (PO-21). Allowed
     * values {0, 60, 120, 240, 480, 720, 1440} are enforced by the UI — the key itself stores any
     * Int32.
     */
    val REFRESH_INTERVAL_MINUTES: SettingKey.Int32 =
        SettingKey.Int32(
            name = "feeds.refresh_interval_minutes",
            default = DEFAULT_REFRESH_INTERVAL_MINUTES,
        )

    /** Settings › Feeds › refresh only on unmetered networks (Android; unused on the desktop). */
    val REFRESH_WIFI_ONLY: SettingKey.Bool =
        SettingKey.Bool(name = "feeds.refresh_wifi_only", default = false)

    /** Settings › Feeds › refresh when the app comes to the foreground (Android trigger). */
    val REFRESH_ON_APP_OPEN: SettingKey.Bool =
        SettingKey.Bool(name = "feeds.refresh_on_app_open", default = true)

    /**
     * Settings › Feeds › automatically fetch older pages of a newly subscribed paged feed
     * (03 RFC 5005 paging); synced from MS2.
     */
    val BACKFILL_PAGED_FEEDS: SettingKey.Bool =
        SettingKey.Bool(
            name = "feeds.backfill_paged_feeds",
            default = true,
            synced = true,
        )

    /** Settings › Feeds › show-notes images (03 Images and links); synced from MS2. */
    val SHOW_NOTES_IMAGES: SettingKey.Choice<ShowNotesImages> =
        SettingKey.Choice(
            name = "feeds.show_notes_images",
            default = ShowNotesImages.TAP_TO_LOAD,
            values =
                persistentListOf(ShowNotesImages.ALWAYS, ShowNotesImages.WIFI_ONLY, ShowNotesImages.TAP_TO_LOAD),
            synced = true,
        )

    /** The tick `refresh-periodic` was last enqueued with (-1 = none); dedupe of UPDATE enqueues. */
    val SCHEDULED_TICK_MINUTES: SettingKey.Int64 =
        SettingKey.Int64(name = "feeds.scheduled_tick_minutes", default = -1L, file = SettingsFile.DEVICE)

    /** Whether the last `refresh-periodic` enqueue carried the UNMETERED constraint. */
    val SCHEDULED_TICK_UNMETERED: SettingKey.Bool =
        SettingKey.Bool(
            name = "feeds.scheduled_tick_unmetered",
            default = false,
            file = SettingsFile.DEVICE,
        )

    /** Wall-clock end of the last refresh run (03 Diagnostics). */
    val LAST_RUN_FINISHED_AT: SettingKey.Int64 =
        SettingKey.Int64(name = "feeds.last_run_finished_at", default = 0L, file = SettingsFile.DEVICE)

    /** Wall-clock end of the last completed scope-All run; gates the Android foreground trigger. */
    val LAST_ALL_RUN_FINISHED_AT: SettingKey.Int64 =
        SettingKey.Int64(
            name = "feeds.last_all_run_finished_at",
            default = 0L,
            file = SettingsFile.DEVICE,
        )

    /** JSON summary of the last refresh run (origin, counts, stopped-by-deadline). */
    val LAST_RUN_SUMMARY: SettingKey.Text =
        SettingKey.Text(name = "feeds.last_run_summary", default = "", file = SettingsFile.DEVICE)

    /** Android `ListenableWorker.stopReason` of the last run (-1 when none; desktop -1/-2). */
    val LAST_RUN_STOP_REASON: SettingKey.Int32 =
        SettingKey.Int32(name = "feeds.last_run_stop_reason", default = -1, file = SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(
            REFRESH_INTERVAL_MINUTES,
            REFRESH_WIFI_ONLY,
            REFRESH_ON_APP_OPEN,
            BACKFILL_PAGED_FEEDS,
            SHOW_NOTES_IMAGES,
            SCHEDULED_TICK_MINUTES,
            SCHEDULED_TICK_UNMETERED,
            LAST_RUN_FINISHED_AT,
            LAST_ALL_RUN_FINISHED_AT,
            LAST_RUN_SUMMARY,
            LAST_RUN_STOP_REASON,
        )

    /** `feeds.refresh_interval_minutes` default: four hours (03 Settings). */
    const val DEFAULT_REFRESH_INTERVAL_MINUTES = 240

    /** `0` in `feeds.refresh_interval_minutes` means "Manual only" (PO-21). */
    const val MANUAL_ONLY_MINUTES = 0
}
