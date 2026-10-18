// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.ImportWarning
import ch.lkmc.neutrodyne.core.model.LinkedRestoreReach
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.SourceGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The `@Serializable` mirror of `:core:model`'s `ImportOptions` (05 Preview contract) — the model
 * module carries no serialization plugin, so `import_session.optionsJson` encodes through this.
 * Field names are the model's; enums go by name so a newer build's added values decode to null
 * rather than failing the whole session.
 *
 * Unknown fields are ignored on read and defaults are never written, so sessions written by a
 * later milestone's extended options round-trip cleanly.
 */
@Serializable
internal data class ImportOptionsJson(
    val importGroups: Boolean = true,
    val excludedGroupKeys: Set<String> = emptySet(),
    val renamedGroups: Map<String, String> = emptyMap(),
    val targetGroupId: Long? = null,
    val targetGroupName: String? = null,
    val treatExistingAsPlayed: Boolean = false,
    val notifyNewEpisodes: Boolean = false,
    val restoreMode: String? = null,
    val restoreCategories: Set<String> = emptySet(),
    val redownloadIds: List<Long> = emptyList(),
    val fromAndroidBackup: Boolean = false,
    val restoreRequestedAt: Long? = null,
    val linkedReach: String? = null,
    val laneAttempts: Int = 0,
    val sourceGroups: List<SourceGroupJson> = emptyList(),
    val itemCustomTitles: Map<Int, String> = emptyMap(),
    val committedAt: Long? = null,
    val attemptMarkerAt: Long? = null,
) {
    fun toModel(): ImportOptions =
        ImportOptions(
            importGroups = importGroups,
            excludedGroupKeys = excludedGroupKeys,
            renamedGroups = renamedGroups,
            targetGroupId = targetGroupId,
            targetGroupName = targetGroupName,
            treatExistingAsPlayed = treatExistingAsPlayed,
            notifyNewEpisodes = notifyNewEpisodes,
            restoreMode = restoreMode?.let(::restoreMode),
            restoreCategories = restoreCategories.mapNotNullTo(HashSet(), ::restoreCategory),
            redownloadIds = redownloadIds,
            fromAndroidBackup = fromAndroidBackup,
            restoreRequestedAt = restoreRequestedAt,
            linkedReach = linkedReach?.let(::linkedRestoreReach),
            laneAttempts = laneAttempts,
            sourceGroups = sourceGroups.map(SourceGroupJson::toModel),
            itemCustomTitles = itemCustomTitles,
            committedAt = committedAt,
            attemptMarkerAt = attemptMarkerAt,
        )

    internal companion object {
        fun fromModel(options: ImportOptions): ImportOptionsJson =
            ImportOptionsJson(
                importGroups = options.importGroups,
                excludedGroupKeys = options.excludedGroupKeys,
                renamedGroups = options.renamedGroups,
                targetGroupId = options.targetGroupId,
                targetGroupName = options.targetGroupName,
                treatExistingAsPlayed = options.treatExistingAsPlayed,
                notifyNewEpisodes = options.notifyNewEpisodes,
                restoreMode = options.restoreMode?.name,
                restoreCategories = options.restoreCategories.mapTo(HashSet(), RestoreCategory::name),
                redownloadIds = options.redownloadIds,
                fromAndroidBackup = options.fromAndroidBackup,
                restoreRequestedAt = options.restoreRequestedAt,
                linkedReach = options.linkedReach?.name,
                laneAttempts = options.laneAttempts,
                sourceGroups = options.sourceGroups.map(SourceGroupJson::fromModel),
                itemCustomTitles = options.itemCustomTitles,
                committedAt = options.committedAt,
                attemptMarkerAt = options.attemptMarkerAt,
            )

        fun encode(options: ImportOptions): String = json.encodeToString(fromModel(options))

        fun decode(encoded: String): ImportOptions = json.decodeFromString<ImportOptionsJson>(encoded).toModel()

        /**
         * [decode] for rows that may be structurally corrupt: `null` instead of throwing, so
         * callers can leave the persisted string (and any restore intent hidden inside it)
         * untouched instead of substituting defaults.
         */
        fun decodeOrNull(encoded: String): ImportOptions? =
            try {
                decode(encoded)
            } catch (_: Exception) {
                null
            }

        private fun restoreMode(name: String): RestoreMode? = RestoreMode.entries.firstOrNull { it.name == name }

        private fun restoreCategory(name: String): RestoreCategory? =
            RestoreCategory.entries.firstOrNull { it.name == name }

        private fun linkedRestoreReach(name: String): LinkedRestoreReach? =
            LinkedRestoreReach.entries.firstOrNull { it.name == name }
    }
}

/**
 * 05's durable restore intent: a `PREVIEW` `NEUTRODYNE_BACKUP` session whose options carry a
 * committed `restoreRequestedAt`. The restore host owns such a session — nothing else may
 * write over, sweep or supersede it. Fails closed: an undecodable `optionsJson` may hide the
 * intent, so it reports `true` rather than exposing the row to overwrite or cleanup.
 */
internal fun ImportSessionEntity.hasRestoreIntent(): Boolean {
    if (state != ImportState.PREVIEW) return false
    val encoded = optionsJson ?: return false
    val options = ImportOptionsJson.decodeOrNull(encoded) ?: return true
    return options.restoreRequestedAt != null
}

/** Scheduling is stricter than ownership: unreadable intents stay protected, but cannot run. */
internal fun ImportSessionEntity.hasRunnableRestoreIntent(): Boolean {
    if (sourceFormat != ImportFormat.NEUTRODYNE_BACKUP || state != ImportState.PREVIEW ||
        payloadPath == null
    ) {
        return false
    }
    val options = optionsJson?.let(ImportOptionsJson::decodeOrNull) ?: return false
    return options.restoreRequestedAt != null && options.restoreMode != null
}

/** The `SourceGroup` mirror (05 §4: proposals ride in `optionsJson` — empty folders have no item rows). */
@Serializable
internal data class SourceGroupJson(
    val nameKey: String,
    val name: String,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    val excludedReason: String? = null,
) {
    fun toModel(): SourceGroup = SourceGroup(nameKey, name, colorArgb, iconKey, excludedReason)

    internal companion object {
        fun fromModel(group: SourceGroup): SourceGroupJson =
            SourceGroupJson(group.nameKey, group.name, group.colorArgb, group.iconKey, group.excludedReason)
    }
}

/** The `ImportWarning` mirror for `import_session.warningsJson`. */
@Serializable
internal data class ImportWarningJson(
    val code: String,
    val count: Int = 0,
    val detail: String? = null,
) {
    fun toModel(): ImportWarning = ImportWarning(code, count, detail)

    internal companion object {
        fun fromModel(warning: ImportWarning): ImportWarningJson =
            ImportWarningJson(warning.code, warning.count, warning.detail)

        fun encodeAll(warnings: List<ImportWarning>): String = json.encodeToString(warnings.map(::fromModel))

        fun decodeAll(encoded: String): List<ImportWarning> =
            json.decodeFromString<List<ImportWarningJson>>(encoded).map(ImportWarningJson::toModel)
    }
}

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
