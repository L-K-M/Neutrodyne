// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.EngravedHeading
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceRow
import ch.lkmc.neutrodyne.core.ui.SettingsSwitchRow
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
    EngravedHeading(stringResource(Res.string.appearance_gestures))
    SettingsSwitchRow(
        title = stringResource(Res.string.appearance_feeds_row_swipe),
        summary = null,
        checked = enabled,
        onCheckedChange = { save(AppearanceSettingKeys.FEEDS_ROW_SWIPE, it) },
    )
    SwipeChoice(startTitle, start, SwipeDirection.START_TO_END) {
        save(AppearanceSettingKeys.SWIPE_START_ACTION, it)
    }
    SwipeChoice(endTitle, end, SwipeDirection.END_TO_START) {
        save(AppearanceSettingKeys.SWIPE_END_ACTION, it)
    }
}

/**
 * One swipe direction's keys. Queue actions arrive in M4 and downloads in M6, and an inert choice
 * is never offered, so the keys are Mark played and None. Any other stored action lights None,
 * as the row's summary read before the keys.
 */
@Composable
private fun SwipeChoice(
    title: String,
    stored: SwipeAction,
    direction: SwipeDirection,
    onSelect: (SwipeAction) -> Unit,
) {
    SettingsChoiceRow(
        icon = NdIcons.Settings,
        title = title,
        summary = null,
        options = OFFERED_SWIPES,
        selected = if (stored == SwipeAction.MARK_PLAYED) SwipeAction.MARK_PLAYED else SwipeAction.NONE,
        label = { stringResource(swipeLabel(it)) },
        onSelect = onSelect,
        tag = { "swipe:${direction.name}:${it.name}" },
    )
}

private val OFFERED_SWIPES = listOf(SwipeAction.MARK_PLAYED, SwipeAction.NONE)

private fun swipeLabel(action: SwipeAction) =
    if (action == SwipeAction.MARK_PLAYED) Res.string.swipe_mark_played else Res.string.swipe_none
