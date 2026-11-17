// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_after_day
import ch.lkmc.neutrodyne.core.ui.resources.download_any_network
import ch.lkmc.neutrodyne.core.ui.resources.download_auto
import ch.lkmc.neutrodyne.core.ui.resources.download_charging
import ch.lkmc.neutrodyne.core.ui.resources.download_delete_played
import ch.lkmc.neutrodyne.core.ui.resources.download_external_cleanup
import ch.lkmc.neutrodyne.core.ui.resources.download_external_group
import ch.lkmc.neutrodyne.core.ui.resources.download_group_enable_first
import ch.lkmc.neutrodyne.core.ui.resources.download_immediately
import ch.lkmc.neutrodyne.core.ui.resources.download_keep
import ch.lkmc.neutrodyne.core.ui.resources.download_keep_value
import ch.lkmc.neutrodyne.core.ui.resources.download_network
import ch.lkmc.neutrodyne.core.ui.resources.download_never
import ch.lkmc.neutrodyne.core.ui.resources.download_unmetered
import ch.lkmc.neutrodyne.core.ui.resources.download_video
import ch.lkmc.neutrodyne.core.ui.resources.setting_inherit
import ch.lkmc.neutrodyne.core.ui.resources.setting_invalid_value
import ch.lkmc.neutrodyne.core.ui.resources.setting_off
import ch.lkmc.neutrodyne.core.ui.resources.setting_on
import org.jetbrains.compose.resources.stringResource

public enum class DownloadSettingsScope {
    APP,
    GROUP,
    PODCAST,
}

/** Typed field edits preserve unrelated overrides inside the repository's update transaction. */
public sealed class DownloadSettingEdit(
    public val field: SettingField,
) {
    public abstract fun applyTo(value: SettingOverrides): SettingOverrides

    public data class Enabled(
        val value: Boolean?,
    ) : DownloadSettingEdit(SettingField.AUTO_DOWNLOAD) {
        override fun applyTo(value: SettingOverrides): SettingOverrides = value.copy(autoDownload = this.value)
    }

    public data class Keep(
        val value: Int?,
    ) : DownloadSettingEdit(SettingField.AUTO_DOWNLOAD_KEEP_LATEST) {
        override fun applyTo(value: SettingOverrides): SettingOverrides =
            value.copy(autoDownloadKeepLatest = this.value)
    }

    public data class Network(
        val value: NetworkPolicy?,
    ) : DownloadSettingEdit(SettingField.AUTO_DOWNLOAD_NETWORK) {
        override fun applyTo(value: SettingOverrides): SettingOverrides = value.copy(autoDownloadNetwork = this.value)
    }

    public data class Charging(
        val value: Boolean?,
    ) : DownloadSettingEdit(SettingField.AUTO_DOWNLOAD_REQUIRE_CHARGING) {
        override fun applyTo(value: SettingOverrides): SettingOverrides =
            value.copy(autoDownloadRequireCharging = this.value)
    }

    public data class Video(
        val value: Boolean?,
    ) : DownloadSettingEdit(SettingField.INCLUDE_VIDEO_IN_AUTO_DOWNLOAD) {
        override fun applyTo(value: SettingOverrides): SettingOverrides =
            value.copy(includeVideoInAutoDownload = this.value)
    }

    public data class Delete(
        val value: DeleteAfter?,
    ) : DownloadSettingEdit(SettingField.DELETE_AFTER_PLAYED) {
        override fun applyTo(value: SettingOverrides): SettingOverrides = value.copy(deleteAfterPlayed = this.value)
    }
}

private fun downloadDependentsEnabled(
    scope: DownloadSettingsScope,
    settings: ScopedSettingsView,
): Boolean =
    when (scope) {
        // Enabled groups can inherit these defaults while app-wide automatic downloads are off.
        DownloadSettingsScope.APP -> true

        DownloadSettingsScope.GROUP -> settings.own.autoDownload == true

        DownloadSettingsScope.PODCAST -> settings.effective.autoDownload == true
    }

@Composable
public fun DownloadSettingsRows(
    settings: ScopedSettingsView,
    scope: DownloadSettingsScope,
    platform: PlatformKind,
    onEdit: (DownloadSettingEdit) -> Unit,
    modifier: Modifier = Modifier,
    invalidFields: Set<SettingField> = emptySet(),
    externalGroupReason: String? = null,
) {
    val value = settings.effective
    val own = settings.own
    val supported = settings.sources[SettingField.AUTO_DOWNLOAD] != SettingSource.NotSupported
    val dependents =
        downloadDependentsEnabled(
            scope,
            settings,
        )
    val fields =
        buildList {
            if (supported) {
                add(SettingField.AUTO_DOWNLOAD)
                add(SettingField.AUTO_DOWNLOAD_KEEP_LATEST)
                if (platform == PlatformKind.ANDROID) {
                    add(SettingField.AUTO_DOWNLOAD_NETWORK)
                    add(SettingField.AUTO_DOWNLOAD_REQUIRE_CHARGING)
                }
                add(SettingField.INCLUDE_VIDEO_IN_AUTO_DOWNLOAD)
            }
            add(SettingField.DELETE_AFTER_PLAYED)
        }
    Column(modifier) {
        fields.forEach { field ->
            val enabled = field == SettingField.AUTO_DOWNLOAD || field == SettingField.DELETE_AFTER_PLAYED || dependents
            // Scoped rows press the scope's own value ("Inherit" when unset) and name the effective
            // value it resolves to; app defaults are concrete and need no summary.
            val details: @Composable ColumnScope.() -> Unit = {
                if (!enabled && scope == DownloadSettingsScope.GROUP) {
                    Text(
                        stringResource(Res.string.download_group_enable_first),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (scope != DownloadSettingsScope.APP) {
                    SettingAttribution(settings.sources[field] ?: SettingSource.AppDefault)
                }
                if (field in invalidFields) {
                    Text(
                        stringResource(Res.string.setting_invalid_value),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val summary = if (scope == DownloadSettingsScope.APP) null else downloadFieldValue(field, value)
            when (field) {
                SettingField.AUTO_DOWNLOAD -> {
                    DownloadChoice(
                        field,
                        BOOLEANS,
                        own.autoDownload,
                        scope,
                        summary,
                        enabled,
                        { boolText(it) },
                        details,
                    ) {
                        onEdit(DownloadSettingEdit.Enabled(it))
                    }
                }

                SettingField.AUTO_DOWNLOAD_KEEP_LATEST -> {
                    DownloadChoice(
                        field,
                        DownloadSettingKeys.KEEP_LATEST_RANGE.toList(),
                        own.autoDownloadKeepLatest,
                        scope,
                        summary,
                        enabled,
                        { stringResource(Res.string.download_keep_value, it) },
                        details,
                    ) { onEdit(DownloadSettingEdit.Keep(it)) }
                }

                SettingField.AUTO_DOWNLOAD_NETWORK -> {
                    DownloadChoice(
                        field,
                        NetworkPolicy.entries,
                        own.autoDownloadNetwork,
                        scope,
                        summary,
                        enabled,
                        { networkText(it) },
                        details,
                    ) { onEdit(DownloadSettingEdit.Network(it)) }
                }

                SettingField.AUTO_DOWNLOAD_REQUIRE_CHARGING -> {
                    DownloadChoice(
                        field,
                        BOOLEANS,
                        own.autoDownloadRequireCharging,
                        scope,
                        summary,
                        enabled,
                        { boolText(it) },
                        details,
                    ) { onEdit(DownloadSettingEdit.Charging(it)) }
                }

                SettingField.INCLUDE_VIDEO_IN_AUTO_DOWNLOAD -> {
                    DownloadChoice(
                        field,
                        BOOLEANS,
                        own.includeVideoInAutoDownload,
                        scope,
                        summary,
                        enabled,
                        { boolText(it) },
                        details,
                    ) { onEdit(DownloadSettingEdit.Video(it)) }
                }

                SettingField.DELETE_AFTER_PLAYED -> {
                    DownloadChoice(
                        field,
                        DeleteAfter.entries,
                        own.deleteAfterPlayed,
                        scope,
                        summary,
                        enabled,
                        { deleteAfterText(it) },
                        details,
                    ) { onEdit(DownloadSettingEdit.Delete(it)) }
                }

                else -> {
                    Unit
                }
            }
        }
        externalGroupReason?.let {
            Text(
                stringResource(
                    Res.string.download_external_group,
                    it,
                ),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (!supported) {
            Text(
                stringResource(Res.string.download_external_cleanup),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

/** One download field as a slip whose keys (or counter) set it; scoped slips add "Inherit" first. */
@Composable
private fun <T : Any> DownloadChoice(
    field: SettingField,
    options: List<T>,
    selected: T?,
    scope: DownloadSettingsScope,
    summary: String?,
    enabled: Boolean,
    label: @Composable (T) -> String,
    details: @Composable ColumnScope.() -> Unit,
    onSelect: (T?) -> Unit,
) {
    val inherit = stringResource(Res.string.setting_inherit)
    val choices: List<T?> = if (scope == DownloadSettingsScope.APP) options else listOf(null) + options
    SettingsChoiceRow(
        NdIcons.Download,
        downloadFieldLabel(field),
        summary,
        choices,
        selected,
        label = { if (it == null) inherit else label(it) },
        onSelect = onSelect,
        enabled = enabled,
        tag = { "download:${field.name}:${it ?: "inherit"}" },
        details = details,
    )
}

private val BOOLEANS = listOf(true, false)

@Composable
private fun downloadFieldLabel(field: SettingField): String =
    stringResource(
        when (field) {
            SettingField.AUTO_DOWNLOAD -> Res.string.download_auto
            SettingField.AUTO_DOWNLOAD_KEEP_LATEST -> Res.string.download_keep
            SettingField.AUTO_DOWNLOAD_NETWORK -> Res.string.download_network
            SettingField.AUTO_DOWNLOAD_REQUIRE_CHARGING -> Res.string.download_charging
            SettingField.INCLUDE_VIDEO_IN_AUTO_DOWNLOAD -> Res.string.download_video
            SettingField.DELETE_AFTER_PLAYED -> Res.string.download_delete_played
            else -> error("Not a download field: $field")
        },
    )

@Composable
private fun downloadFieldValue(
    field: SettingField,
    value: SettingOverrides,
): String =
    when (field) {
        SettingField.AUTO_DOWNLOAD -> {
            boolText(value.autoDownload == true)
        }

        SettingField.AUTO_DOWNLOAD_KEEP_LATEST -> {
            stringResource(
                Res.string.download_keep_value,
                value.autoDownloadKeepLatest ?: DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST.default,
            )
        }

        SettingField.AUTO_DOWNLOAD_NETWORK -> {
            networkText(value.autoDownloadNetwork ?: NetworkPolicy.UNMETERED)
        }

        SettingField.AUTO_DOWNLOAD_REQUIRE_CHARGING -> {
            boolText(value.autoDownloadRequireCharging == true)
        }

        SettingField.INCLUDE_VIDEO_IN_AUTO_DOWNLOAD -> {
            boolText(value.includeVideoInAutoDownload == true)
        }

        SettingField.DELETE_AFTER_PLAYED -> {
            deleteAfterText(value.deleteAfterPlayed ?: DeleteAfter.AFTER_24H)
        }

        else -> {
            error("Not a download field: $field")
        }
    }

@Composable
private fun boolText(value: Boolean): String =
    stringResource(if (value) Res.string.setting_on else Res.string.setting_off)

@Composable
private fun networkText(value: NetworkPolicy): String =
    stringResource(
        if (value ==
            NetworkPolicy.UNMETERED
        ) {
            Res.string.download_unmetered
        } else {
            Res.string.download_any_network
        },
    )

@Composable
private fun deleteAfterText(value: DeleteAfter): String =
    stringResource(
        when (value) {
            DeleteAfter.IMMEDIATELY -> Res.string.download_immediately
            DeleteAfter.AFTER_24H -> Res.string.download_after_day
            DeleteAfter.NEVER -> Res.string.download_never
        },
    )
