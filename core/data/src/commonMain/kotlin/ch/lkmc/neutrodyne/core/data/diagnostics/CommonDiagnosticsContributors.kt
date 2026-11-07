// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.common.getOrNull
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.update.UpdateCheckStore
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeEngineDiagnostics
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The APP card (09): build identity, platform and the update-check state every platform
 * shares (`UpdateCheckStore` holds the last fetched manifest).
 */
@ContributesIntoSet(AppScope::class)
internal class AppDiagnosticsContributor
    @Inject
    constructor(
        private val buildInfo: BuildInfo,
        private val platformInfo: PlatformInfo,
        private val settings: SettingsRepository,
        private val updateCheckStore: UpdateCheckStore,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.APP

        override suspend fun collect(): List<DiagnosticsLine> {
            val lines =
                mutableListOf(
                    DiagnosticsLine("versionName", buildInfo.versionName),
                    DiagnosticsLine("versionCode", buildInfo.versionCode.toString()),
                    DiagnosticsLine("build", if (buildInfo.debug) "debug" else "release"),
                    DiagnosticsLine("platform", buildInfo.platform.name.lowercase()),
                    DiagnosticsLine("platformDetail", platformInfo.userAgentPlatform),
                )
            buildInfo.apkAbi?.let { lines += DiagnosticsLine("apkAbi", it) }
            lines += DiagnosticsLine("updateCheckEnabled", settings.get(UpdateSettingKeys.CHECK_ENABLED).toString())
            val lastCheckAt = settings.get(UpdateSettingKeys.LAST_CHECK_AT)
            lines +=
                DiagnosticsLine(
                    "lastUpdateCheckAt",
                    if (lastCheckAt > 0) lastCheckAt.toString() else "never",
                )
            val snapshot = updateCheckStore.read().getOrNull()
            if (snapshot?.manifest != null) {
                lines += DiagnosticsLine("updateLatestVersion", snapshot.manifest.versionName)
            }
            return lines
        }
    }

/** The REFRESH card (09): last run times, summary and the refresh work's stop reason. */
@ContributesIntoSet(AppScope::class)
internal class RefreshDiagnosticsContributor
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.REFRESH

        override suspend fun collect(): List<DiagnosticsLine> {
            val finished = settings.get(FeedsSettingKeys.LAST_RUN_FINISHED_AT)
            val allFinished = settings.get(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT)
            val lines =
                mutableListOf(
                    DiagnosticsLine("lastRefreshAt", if (finished > 0) finished.toString() else "never"),
                    DiagnosticsLine(
                        "lastFullRefreshAt",
                        if (allFinished > 0) allFinished.toString() else "never",
                    ),
                    DiagnosticsLine(
                        "intervalMinutes",
                        settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES).toString(),
                    ),
                    DiagnosticsLine("wifiOnly", settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY).toString()),
                )
            val summary = settings.get(FeedsSettingKeys.LAST_RUN_SUMMARY)
            if (summary.isNotEmpty()) lines += DiagnosticsLine("lastRunSummary", summary)
            return lines
        }
    }

/** The DATABASE card (09): `db-maintenance` outcome, quick-check and verbose-log state. */
@ContributesIntoSet(AppScope::class)
internal class DatabaseDiagnosticsContributor
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val db: NeutrodyneDatabase,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.DATABASE

        override suspend fun collect(): List<DiagnosticsLine> {
            val lines = mutableListOf<DiagnosticsLine>()
            val lastRun = settings.get(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT)
            lines += DiagnosticsLine("maintenanceLastRunAt", if (lastRun > 0) lastRun.toString() else "never")
            val stats = settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS)
            if (stats.isNotEmpty()) {
                for (entry in statsEntries(stats)) {
                    lines += DiagnosticsLine("maintenance.${entry.first}", entry.second)
                }
            }
            val quickCheckFailedAt = settings.get(DiagnosticsSettingKeys.DB_QUICK_CHECK_FAILED_AT)
            if (quickCheckFailedAt > 0) {
                lines +=
                    DiagnosticsLine(
                        "quickCheckFailedAt",
                        quickCheckFailedAt.toString(),
                        DiagnosticsSeverity.PROBLEM,
                    )
            }
            val verboseUntil = settings.get(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL)
            lines +=
                DiagnosticsLine(
                    "verboseLogging",
                    if (verboseUntil > 0) "until $verboseUntil" else "off",
                )
            lines += DiagnosticsLine("podcasts", db.maintenanceDao().podcastCount().toString())
            lines += DiagnosticsLine("episodes", db.maintenanceDao().episodeCount().toString())
            return lines
        }

        private fun statsEntries(json: String): List<Pair<String, String>> =
            runCatching {
                (Json.parseToJsonElement(json) as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                    // One object/array entry must not drop the whole stats block.
                    runCatching { key to value.jsonPrimitive.content }.getOrNull()
                }
            }.getOrDefault(emptyList())
    }

/**
 * The SYNC card (09): enabled state, protocol, last sync and pending local changes. The
 * server's address never appears — diagnostics end up in public issues (09's SYNC rule).
 */
@ContributesIntoSet(AppScope::class)
internal class SyncDiagnosticsContributor
    @Inject
    constructor(
        private val db: NeutrodyneDatabase,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.SYNC

        override suspend fun collect(): List<DiagnosticsLine> {
            val state = db.syncStateDao().get()
            val lines =
                mutableListOf(
                    DiagnosticsLine("enabled", (state?.enabled == true).toString()),
                )
            if (state != null) {
                state.protocol?.let { lines += DiagnosticsLine("protocol", it.toString()) }
                val lastSync = state.lastSyncAt
                lines += DiagnosticsLine("lastSyncAt", lastSync?.toString() ?: "never")
                state.lastError?.let {
                    lines += DiagnosticsLine("lastError", Redactor.text(it), DiagnosticsSeverity.PROBLEM)
                }
            }
            lines += DiagnosticsLine("pendingChanges", db.syncOutboxDao().count().toString())
            lines += DiagnosticsLine("parkedChanges", db.syncParkedDao().count().toString())
            return lines
        }
    }

/** The YOUTUBE card (09): engine version and health, honest "not installed" until M12. */
@ContributesIntoSet(AppScope::class)
internal class YouTubeDiagnosticsContributor
    @Inject
    constructor(
        private val buildInfo: BuildInfo,
        private val engine: YouTubeEngineDiagnostics,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.YOUTUBE

        override suspend fun collect(): List<DiagnosticsLine> =
            listOf(
                DiagnosticsLine("engineBundled", buildInfo.youTubeEngineBundled.toString()),
                DiagnosticsLine("engineVersion", engine.installedVersion() ?: "not installed"),
                DiagnosticsLine("health", engine.health()),
            )
    }

/** The PARSE_WARNINGS card (09): the last 50 feeds' warnings from `FeedRefresher`'s LRU. */
@ContributesIntoSet(AppScope::class)
internal class ParseWarningsContributor
    @Inject
    constructor(
        private val refresher: FeedRefresher,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.PARSE_WARNINGS

        override suspend fun collect(): List<DiagnosticsLine> {
            val all = refresher.allParseWarnings()
            if (all.isEmpty()) return listOf(DiagnosticsLine("warnings", "none recorded"))
            return all.entries.flatMap { (podcastId, warnings) ->
                warnings.map { warning ->
                    val where = warning.itemIndex?.let { " item $it" } ?: ""
                    // Parser details quote feed text and URLs: redact at the source too, on
                    // top of the repository's own Redactor pass (01's belt-and-braces rule).
                    val detail = warning.detail.takeIf { it.isNotEmpty() }
                        ?.let { " ${Redactor.text(it).take(80)}" } ?: ""
                    DiagnosticsLine(
                        "podcast $podcastId$where",
                        "${warning.code}$detail",
                        DiagnosticsSeverity.WARNING,
                    )
                }
            }
        }
    }
