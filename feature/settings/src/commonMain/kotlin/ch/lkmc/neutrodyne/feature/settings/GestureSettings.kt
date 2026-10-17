// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_feeds_row_swipe
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_gestures
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import ch.lkmc.neutrodyne.feature.settings.resources.swipe_left
import ch.lkmc.neutrodyne.feature.settings.resources.swipe_mark_played
import ch.lkmc.neutrodyne.feature.settings.resources.swipe_none
import ch.lkmc.neutrodyne.feature.settings.resources.swipe_right
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private enum class SwipeDirection { START_TO_END, END_TO_START }

/** Called only on Android. Later-milestone choices remain stored, with no action in M2. */
@Composable
internal fun GestureSettings(settings: SettingsRepository) {
    val enabled by settings.observe(AppearanceSettingKeys.FEEDS_ROW_SWIPE).collectAsStateWithLifecycle(false)
    val start by settings
        .observe(AppearanceSettingKeys.SWIPE_START_ACTION)
        .collectAsStateWithLifecycle(AppearanceSettingKeys.SWIPE_START_ACTION.default)
    val end by settings
        .observe(AppearanceSettingKeys.SWIPE_END_ACTION)
        .collectAsStateWithLifecycle(AppearanceSettingKeys.SWIPE_END_ACTION.default)
    var editing by rememberSaveable { mutableStateOf<SwipeDirection?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    val failed = stringResource(Res.string.settings_save_failed)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val startTitle = stringResource(if (rtl) Res.string.swipe_left else Res.string.swipe_right)
    val endTitle = stringResource(if (rtl) Res.string.swipe_right else Res.string.swipe_left)

    fun <T : Any> save(
        key: SettingKey<T>,
        value: T,
    ) {
        scope.launch {
            if (settings.set(key, value) is Outcome.Failure) snackbar.showSnackbar(failed)
        }
    }
    Text(stringResource(Res.string.appearance_gestures), Modifier.padding(16.dp))
    SettingsSwitchRow(
        title = stringResource(Res.string.appearance_feeds_row_swipe),
        summary = null,
        checked = enabled,
        onCheckedChange = { save(AppearanceSettingKeys.FEEDS_ROW_SWIPE, it) },
    )
    SettingsRow(
        NdIcons.Settings,
        startTitle,
        stringResource(swipeLabel(start)),
        onClick = { editing = SwipeDirection.START_TO_END },
    )
    SettingsRow(
        NdIcons.Settings,
        endTitle,
        stringResource(swipeLabel(end)),
        onClick = { editing = SwipeDirection.END_TO_START },
    )
    val direction = editing ?: return
    NdDialog(
        onDismissRequest = { editing = null },
        icon = NdIcons.Settings,
        title = if (direction == SwipeDirection.START_TO_END) startTitle else endTitle,
    ) {
        Column {
            // Queue actions arrive in M4, downloads in M6. Never offer an inert choice.
            for (action in listOf(SwipeAction.MARK_PLAYED, SwipeAction.NONE)) {
                NdTextButton(stringResource(swipeLabel(action)), onClick = {
                    save(
                        if (direction == SwipeDirection.START_TO_END) {
                            AppearanceSettingKeys.SWIPE_START_ACTION
                        } else {
                            AppearanceSettingKeys.SWIPE_END_ACTION
                        },
                        action,
                    )
                    editing = null
                })
            }
        }
    }
}

private fun swipeLabel(action: SwipeAction) =
    if (action == SwipeAction.MARK_PLAYED) Res.string.swipe_mark_played else Res.string.swipe_none
