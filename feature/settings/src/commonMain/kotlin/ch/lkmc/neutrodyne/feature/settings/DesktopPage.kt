// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.domain.DesktopIntegration
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.LoginItemState
import ch.lkmc.neutrodyne.core.model.settings.CloseBehaviour
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_keep
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_keep_mac
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_keep_summary
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_keep_summary_mac
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_quit
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_quit_summary
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_quit_summary_mac
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_title
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_menu
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_menu_done
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_menu_summary
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_not_setup
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_open
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_open_on
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_links_setup
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_login
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_login_approval
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_login_disabled_os
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_login_open_settings
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_login_summary
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_login_translocated
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_section_links
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_section_window
import ch.lkmc.neutrodyne.feature.settings.resources.settings_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Desktop (08 Settings › Desktop; 11 Desktop settings). One page holds the window
 * section — the "When the window is closed" radio pair writes `desktop.close_behaviour`, and
 * "Start at login" toggles the OS login item through [DesktopIntegration] — and the downloads
 * section supplied by [downloadsFolder] (11 Change folder: [DesktopDownloadFolderSection]). The
 * login switch shows what the OS reports, not the stored intent, so an entry the user removed or
 * disabled in the OS reads correctly; the row is hidden where registration is unsupported (dev
 * runs, macOS until its registrar lands). `desktopIntegration` is `null` off the desktop — the
 * page is unreachable there — and the downloads slot defaults to empty so the page composes
 * without a download graph. MD2's links slice adds the LINKS section (11 Links and files
 * from the OS): "Open podcast links with Neutrodyne" where the OS registers schemes at run
 * time (Windows; Linux tar.gz through its menu entry) and "Add to applications menu" on
 * Linux tar.gz; the language and accessibility rows join with their own slices.
 */
@Composable
internal fun DesktopPage(
    settings: SettingsRepository,
    desktopIntegration: DesktopIntegration?,
    desktopOs: DesktopOs?,
    downloadsFolder: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val saveFailed = stringResource(Res.string.settings_save_failed)
    // observe() builds a fresh, unequal Flow every call — an unremembered instance would
    // restart the collection on each recomposition.
    val behaviourFlow = remember(settings) { settings.observe(DesktopSettingKeys.CLOSE_BEHAVIOUR) }
    val behaviour by
        behaviourFlow.collectAsStateWithLifecycle(initialValue = DesktopSettingKeys.CLOSE_BEHAVIOUR.default)
    val loginFlow =
        remember(desktopIntegration) {
            desktopIntegration?.loginItem ?: flowOf(LoginItemState.UNSUPPORTED)
        }
    val loginItem by loginFlow.collectAsStateWithLifecycle(initialValue = LoginItemState.UNSUPPORTED)
    val linksFlow =
        remember(desktopIntegration) {
            desktopIntegration?.linkHandlers ?: flowOf(null as Boolean?)
        }
    val linkHandlers by linksFlow.collectAsStateWithLifecycle(initialValue = null)
    val canAddToApplicationsMenu = desktopIntegration?.canAddToApplicationsMenu == true
    val platformActions = LocalPlatformActions.current
    val macOs = desktopOs == DesktopOs.MACOS

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
            title = stringResource(Res.string.settings_desktop),
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
            Text(
                stringResource(Res.string.desktop_section_window),
                Modifier.padding(SECTION_PADDING),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(Res.string.desktop_close_title),
                Modifier.padding(horizontal = SECTION_PADDING),
                style = MaterialTheme.typography.bodyLarge,
            )
            CloseBehaviourRadioRow(
                title = stringResource(Res.string.desktop_close_quit),
                summary =
                    stringResource(
                        if (macOs) {
                            Res.string.desktop_close_quit_summary_mac
                        } else {
                            Res.string.desktop_close_quit_summary
                        },
                    ),
                selected = behaviour == CloseBehaviour.QUIT_WHEN_IDLE,
                onSelect = { save(DesktopSettingKeys.CLOSE_BEHAVIOUR, CloseBehaviour.QUIT_WHEN_IDLE) },
            )
            CloseBehaviourRadioRow(
                title =
                    stringResource(
                        if (macOs) Res.string.desktop_close_keep_mac else Res.string.desktop_close_keep,
                    ),
                summary =
                    stringResource(
                        if (macOs) {
                            Res.string.desktop_close_keep_summary_mac
                        } else {
                            Res.string.desktop_close_keep_summary
                        },
                    ),
                selected = behaviour == CloseBehaviour.KEEP_RUNNING,
                onSelect = { save(DesktopSettingKeys.CLOSE_BEHAVIOUR, CloseBehaviour.KEEP_RUNNING) },
            )
            if (loginItem != LoginItemState.UNSUPPORTED) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.desktop_login),
                    summary = stringResource(loginSummary(loginItem)),
                    checked = loginItem.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            val outcome = desktopIntegration?.setStartAtLogin(enabled)
                            if (outcome == null || outcome is Outcome.Failure) {
                                snackbarHost.showSnackbar(saveFailed)
                            }
                        }
                    },
                )
                if (loginItem == LoginItemState.REQUIRES_APPROVAL) {
                    TextButton(
                        onClick = { platformActions.urls.open(MAC_LOGIN_ITEMS_URL) },
                        modifier = Modifier.padding(horizontal = SECTION_PADDING),
                    ) {
                        Text(stringResource(Res.string.desktop_login_open_settings))
                    }
                }
            }
            // 08's page order: WINDOW, LINKS (Windows; Linux tar.gz), then DOWNLOADS.
            if (linkHandlers != null || canAddToApplicationsMenu) {
                Text(
                    stringResource(Res.string.desktop_section_links),
                    Modifier.padding(SECTION_PADDING),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (linkHandlers != null) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.desktop_links_open)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (linkHandlers ==
                                    true
                                ) {
                                    Res.string.desktop_links_open_on
                                } else {
                                    Res.string.desktop_links_not_setup
                                },
                            ),
                        )
                    },
                    trailingContent = {
                        if (linkHandlers != true) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (desktopIntegration?.registerLinkHandlers() != true) {
                                            snackbarHost.showSnackbar(saveFailed)
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(Res.string.desktop_links_setup))
                            }
                        }
                    },
                )
            }
            if (canAddToApplicationsMenu) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.desktop_links_menu)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (linkHandlers ==
                                    true
                                ) {
                                    Res.string.desktop_links_menu_done
                                } else {
                                    Res.string.desktop_links_menu_summary
                                },
                            ),
                        )
                    },
                    trailingContent = {
                        if (linkHandlers != true) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (desktopIntegration?.addToApplicationsMenu() != true) {
                                            snackbarHost.showSnackbar(saveFailed)
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(Res.string.desktop_links_setup))
                            }
                        }
                    },
                )
            }
            downloadsFolder()
        }
    }
}

/**
 * Whether the login item the OS reports counts as "on": the entry exists ([LoginItemState.
 * REGISTERED]) or exists but the OS has it switched off ([LoginItemState.DISABLED_BY_OS], which
 * the row says). [LoginItemState.REQUIRES_APPROVAL] still shows off — the OS has no entry yet.
 */
private val LoginItemState.enabled: Boolean
    get() = this == LoginItemState.REGISTERED || this == LoginItemState.DISABLED_BY_OS

private fun loginSummary(state: LoginItemState) =
    when (state) {
        // The entry exists but the OS switched it off — say so where the user can act on it.
        LoginItemState.DISABLED_BY_OS -> Res.string.desktop_login_disabled_os

        LoginItemState.REQUIRES_APPROVAL -> Res.string.desktop_login_approval

        // A translocated first start cannot register the bundle; the row says what to do.
        LoginItemState.TRANSLOCATED -> Res.string.desktop_login_translocated

        else -> Res.string.desktop_login_summary
    }

@Composable
private fun CloseBehaviourRadioRow(
    title: String,
    summary: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    )
}

/** `x-apple.systempreferences:` deep link into macOS System Settings › General › Login Items. */
private const val MAC_LOGIN_ITEMS_URL = "x-apple.systempreferences:com.apple.LoginItems-Settings.extension"

private val SECTION_PADDING = 16.dp
