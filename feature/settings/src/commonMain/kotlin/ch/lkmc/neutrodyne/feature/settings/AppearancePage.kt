// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_dynamic_color
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_dynamic_color_summary
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_theme
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_theme_dark
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_theme_light
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_theme_system
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Appearance (08 Settings screen structure, M0a): the Theme chooser and, where the
 * platform offers wallpaper dynamic colour (Android API 31+; hidden below and on the desktop),
 * its switch. Both rows write `appearance.*` keys straight through [SettingsRepository]; the
 * shell collects them into `AppearancePrefs`, so a change re-themes at once.
 */
@Composable
internal fun AppearancePage(
    settings: SettingsRepository,
    dynamicColorAvailable: Boolean,
) {
    val theme by
        settings
            .observe(AppearanceSettingKeys.THEME)
            .collectAsStateWithLifecycle(initialValue = AppearanceSettingKeys.THEME.default)
    val dynamicColor by
        settings
            .observe(AppearanceSettingKeys.DYNAMIC_COLOR)
            .collectAsStateWithLifecycle(initialValue = AppearanceSettingKeys.DYNAMIC_COLOR.default)
    val scope = rememberCoroutineScope()
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings_appearance),
            navigation = { SettingsBackButton() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsRow(
                icon = NdIcons.Palette,
                title = stringResource(Res.string.appearance_theme),
                summary = stringResource(themeLabel(theme)),
                onClick = { showThemeDialog = true },
            )
            if (dynamicColorAvailable) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.appearance_dynamic_color),
                    summary = stringResource(Res.string.appearance_dynamic_color_summary),
                    checked = dynamicColor,
                    onCheckedChange = { checked ->
                        scope.launch { settings.set(AppearanceSettingKeys.DYNAMIC_COLOR, checked) }
                    },
                )
            }
        }
    }

    if (showThemeDialog) {
        ThemeDialog(
            selected = theme,
            onSelect = { mode ->
                showThemeDialog = false
                scope.launch { settings.set(AppearanceSettingKeys.THEME, mode) }
            },
            onDismissRequest = { showThemeDialog = false },
        )
    }
}

@Composable
private fun ThemeDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismissRequest: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onDismissRequest,
        icon = NdIcons.Palette,
        title = stringResource(Res.string.appearance_theme),
    ) {
        Column {
            for (mode in AppearanceSettingKeys.THEME.values) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == selected,
                                onClick = { onSelect(mode) },
                                role = Role.RadioButton,
                            ).padding(vertical = RADIO_ROW_GAP),
                ) {
                    RadioButton(selected = mode == selected, onClick = null)
                    Text(
                        stringResource(themeLabel(mode)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = RADIO_LABEL_GAP),
                    )
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode): StringResource =
    when (mode) {
        ThemeMode.SYSTEM -> Res.string.appearance_theme_system
        ThemeMode.LIGHT -> Res.string.appearance_theme_light
        ThemeMode.DARK -> Res.string.appearance_theme_dark
    }

private val RADIO_ROW_GAP = 6.dp
private val RADIO_LABEL_GAP = 16.dp
