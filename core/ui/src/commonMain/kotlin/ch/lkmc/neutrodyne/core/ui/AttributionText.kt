// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.setting_app_default
import ch.lkmc.neutrodyne.core.ui.resources.setting_from_group
import ch.lkmc.neutrodyne.core.ui.resources.setting_from_groups
import ch.lkmc.neutrodyne.core.ui.resources.setting_from_two_groups
import ch.lkmc.neutrodyne.core.ui.resources.setting_groups_title
import ch.lkmc.neutrodyne.core.ui.resources.setting_not_supported
import ch.lkmc.neutrodyne.core.ui.resources.setting_podcast_override
import org.jetbrains.compose.resources.stringResource

/** The deciding sources supplied by the resolver, in its deterministic group order (05). */
public object AttributionText {
    public fun describe(source: SettingSource): UiText =
        when (source) {
            SettingSource.NotSupported -> {
                UiText.Res(Res.string.setting_not_supported)
            }

            SettingSource.PodcastOverride -> {
                UiText.Res(Res.string.setting_podcast_override)
            }

            SettingSource.AppDefault -> {
                UiText.Res(Res.string.setting_app_default)
            }

            is SettingSource.Group -> {
                UiText.Res(Res.string.setting_from_group, listOf(source.name))
            }

            is SettingSource.Groups -> {
                when (source.groups.size) {
                    1 -> describe(source.groups.single())
                    2 -> UiText.Res(Res.string.setting_from_two_groups, source.groups.map { it.name })
                    else -> UiText.Res(Res.string.setting_from_groups, listOf(source.groups.size))
                }
            }
        }
}

/** Long deciding-group lists stay inspectable on every scoped settings row (05 Attribution). */
@Composable
public fun SettingAttribution(
    source: SettingSource,
    modifier: Modifier = Modifier,
) {
    var showGroups by rememberSaveable { mutableStateOf(false) }
    val text = AttributionText.describe(source).asString()
    if (source is SettingSource.Groups && source.groups.size >= GROUP_LIST_THRESHOLD) {
        TextButton(onClick = { showGroups = true }, modifier = modifier) { Text(text) }
    } else {
        Text(text, modifier = modifier)
    }
    if (showGroups && source is SettingSource.Groups) {
        NdDialog(onDismissRequest = { showGroups = false }, title = stringResource(Res.string.setting_groups_title)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                source.groups.forEach { Text(it.name, modifier = Modifier.padding(GROUP_PADDING)) }
            }
        }
    }
}

private const val GROUP_LIST_THRESHOLD = 3
private val GROUP_PADDING = 16.dp
