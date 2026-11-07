// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.downloadExternalReasonText
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_mark_played
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_suggest_rss
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_title
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun YouTubePage(
    settings: SettingsRepository,
    capabilities: YouTubeCapabilitiesSource?,
    platform: PlatformKind,
) {
    val caps by (capabilities?.capabilities ?: remember { flowOf(null) }).collectAsStateWithLifecycle(null)
    val suggest by remember(settings) { settings.observe(YouTubeSettingKeys.SUGGEST_RSS) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.SUGGEST_RSS.default)
    val mark by remember(settings) { settings.observe(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN.default)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    val failed = stringResource(Res.string.settings_save_failed)

    fun save(
        key: SettingKey<Boolean>,
        value: Boolean,
    ) {
        scope.launch {
            if (suspendRunCatching {
                    settings.set(
                        key,
                        value,
                    )
                }.getOrNull() !is Outcome.Success
            ) {
                snackbar.showSnackbar(failed)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = stringResource(Res.string.youtube_title), navigation = { SettingsBackButton() })
        caps?.externalReason?.let { Text(downloadExternalReasonText(it, platform).asString()) }
        if (caps == null) Text(downloadExternalReasonText(ExternalReason.NOT_YET_AVAILABLE, platform).asString())
        SettingsSwitchRow(
            stringResource(Res.string.youtube_suggest_rss),
            null,
            suggest,
            { save(YouTubeSettingKeys.SUGGEST_RSS, it) },
        )
        SettingsSwitchRow(
            stringResource(Res.string.youtube_mark_played),
            null,
            mark,
            { save(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN, it) },
        )
    }
}
