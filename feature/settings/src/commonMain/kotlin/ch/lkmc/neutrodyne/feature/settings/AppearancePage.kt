// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.YouTubeRowArt
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceDialog
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_cover_large
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_cover_medium
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_cover_size
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_cover_small
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_titles
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_titles_summary
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_youtube_channel_avatar
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_youtube_row_art
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_youtube_video_thumbnail
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Appearance (08 Settings screen structure, M0a): the Library's cover size and title
 * switch, the YouTube row-art chooser where channels exist, and the Android gesture rows. The
 * rows write `appearance.*` keys straight through [SettingsRepository]. The retired theme,
 * wallpaper-colour, artwork-tint and pure-black preferences have no UI here any more.
 */
@Composable
internal fun AppearancePage(
    settings: SettingsRepository,
    platform: PlatformKind = LocalPlatformKind.current,
    hasChannels: Flow<Boolean> = flowOf(false),
) {
    val density by
        settings
            .observe(AppearanceSettingKeys.LIBRARY_DENSITY)
            .collectAsStateWithLifecycle(initialValue = AppearanceSettingKeys.LIBRARY_DENSITY.default)
    val titles by
        settings
            .observe(AppearanceSettingKeys.LIBRARY_TITLES)
            .collectAsStateWithLifecycle(initialValue = AppearanceSettingKeys.LIBRARY_TITLES.default)
    val channelsExist by hasChannels.collectAsStateWithLifecycle(initialValue = false)
    val rowArt by settings
        .observe(AppearanceSettingKeys.YOUTUBE_ROW_ART)
        .collectAsStateWithLifecycle(initialValue = AppearanceSettingKeys.YOUTUBE_ROW_ART.default)
    var showDensityDialog by rememberSaveable { mutableStateOf(false) }
    var showRowArtDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val saveFailed = stringResource(Res.string.settings_save_failed)

    // A failed write leaves the stored value, which the rows show, unchanged; the snackbar says so.
    fun <T : Any> save(
        key: SettingKey<T>,
        value: T,
    ) {
        scope.launch {
            if (settings.set(key, value) is Outcome.Failure) snackbarHost.showSnackbar(saveFailed)
        }
    }

    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings_appearance),
            navigation = { SettingsBackButton() },
        )
        // 08 Scrollbars: the desktop draws an overlay thumb at the page's end edge.
        val scroll = rememberScrollState()
        val bars = LocalScrollbars.current
        val barColor = ndScrollbarColor(bars)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, barColor),
        ) {
            SettingsRow(
                icon = NdIcons.GridView,
                title = stringResource(Res.string.appearance_cover_size),
                summary = stringResource(densityLabel(density)),
                onClick = { showDensityDialog = true },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.appearance_titles),
                summary = stringResource(Res.string.appearance_titles_summary),
                checked = titles,
                onCheckedChange = { save(AppearanceSettingKeys.LIBRARY_TITLES, it) },
            )
            if (channelsExist) {
                SettingsRow(
                    icon = NdIcons.Palette,
                    title = stringResource(Res.string.appearance_youtube_row_art),
                    summary = stringResource(rowArtLabel(rowArt)),
                    onClick = { showRowArtDialog = true },
                )
            }
            if (platform == PlatformKind.ANDROID) GestureSettings(settings)
        }
    }

    if (showDensityDialog) {
        SettingsChoiceDialog(
            icon = NdIcons.GridView,
            title = stringResource(Res.string.appearance_cover_size),
            options = AppearanceSettingKeys.LIBRARY_DENSITY.values,
            selected = density,
            label = { stringResource(densityLabel(it)) },
            onSelect = {
                showDensityDialog = false
                save(AppearanceSettingKeys.LIBRARY_DENSITY, it)
            },
            onDismissRequest = { showDensityDialog = false },
        )
    }

    if (showRowArtDialog && channelsExist) {
        SettingsChoiceDialog(
            icon = NdIcons.Palette,
            title = stringResource(Res.string.appearance_youtube_row_art),
            options = AppearanceSettingKeys.YOUTUBE_ROW_ART.values,
            selected = rowArt,
            label = { stringResource(rowArtLabel(it)) },
            onSelect = {
                showRowArtDialog = false
                save(AppearanceSettingKeys.YOUTUBE_ROW_ART, it)
            },
            onDismissRequest = { showRowArtDialog = false },
        )
    }
}

private fun densityLabel(choice: LibraryDensity): StringResource =
    when (choice) {
        LibraryDensity.SMALL -> Res.string.appearance_cover_small
        LibraryDensity.MEDIUM -> Res.string.appearance_cover_medium
        LibraryDensity.LARGE -> Res.string.appearance_cover_large
    }

private fun rowArtLabel(choice: YouTubeRowArt): StringResource =
    when (choice) {
        YouTubeRowArt.VIDEO_THUMBNAIL -> Res.string.appearance_youtube_video_thumbnail
        YouTubeRowArt.CHANNEL_AVATAR -> Res.string.appearance_youtube_channel_avatar
    }
