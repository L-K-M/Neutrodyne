// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceRow
import ch.lkmc.neutrodyne.core.ui.SettingsSwitchRow
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_auto_download
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_keep_latest
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun YouTubeDownloadSettings(
    settings: SettingsRepository,
    capabilities: YouTubeCapabilitiesSource,
    modifier: Modifier = Modifier,
) {
    val caps by capabilities.capabilities.collectAsStateWithLifecycle()
    if (!caps.downloads) return
    val enabled by remember(settings) { settings.observe(YouTubeSettingKeys.AUTO_DOWNLOAD) }
        .collectAsStateWithLifecycle(false)
    val keep by remember(settings) { settings.observe(YouTubeSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST) }
        .collectAsStateWithLifecycle(2)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    val failed = stringResource(Res.string.settings_save_failed)
    Column(modifier) {
        SettingsSwitchRow(
            stringResource(Res.string.youtube_auto_download),
            null,
            enabled,
            { value ->
                scope.launch {
                    if (settings.set(YouTubeSettingKeys.AUTO_DOWNLOAD, value) is Outcome.Failure) {
                        snackbar.showSnackbar(failed)
                    }
                }
            },
        )
        SettingsChoiceRow(
            NdIcons.Download,
            stringResource(Res.string.youtube_keep_latest),
            null,
            KEEP_LATEST_CHOICES,
            keep,
            { it.toString() },
            { value ->
                scope.launch {
                    if (settings.set(YouTubeSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST, value) is Outcome.Failure) {
                        snackbar.showSnackbar(failed)
                    }
                }
            },
        )
    }
}

private val KEEP_LATEST_CHOICES = (1..10).toList()
