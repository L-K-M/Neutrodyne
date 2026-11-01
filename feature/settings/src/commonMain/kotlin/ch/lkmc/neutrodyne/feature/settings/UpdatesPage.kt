// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.DateFormatter
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.update.DesktopAssetSelection
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.navigation.InstallHelpKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionNotice
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.install_help_title
import ch.lkmc.neutrodyne.feature.settings.resources.install_hint_mac
import ch.lkmc.neutrodyne.feature.settings.resources.install_hint_msi
import ch.lkmc.neutrodyne.feature.settings.resources.install_hint_tar
import ch.lkmc.neutrodyne.feature.settings.resources.install_hint_zip
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import ch.lkmc.neutrodyne.feature.settings.resources.updates_64_bit
import ch.lkmc.neutrodyne.feature.settings.resources.updates_after_download
import ch.lkmc.neutrodyne.feature.settings.resources.updates_available
import ch.lkmc.neutrodyne.feature.settings.resources.updates_check_now
import ch.lkmc.neutrodyne.feature.settings.resources.updates_checking
import ch.lkmc.neutrodyne.feature.settings.resources.updates_copied
import ch.lkmc.neutrodyne.feature.settings.resources.updates_copy_hash
import ch.lkmc.neutrodyne.feature.settings.resources.updates_current
import ch.lkmc.neutrodyne.feature.settings.resources.updates_debug
import ch.lkmc.neutrodyne.feature.settings.resources.updates_download_apk
import ch.lkmc.neutrodyne.feature.settings.resources.updates_download_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.updates_footer_android
import ch.lkmc.neutrodyne.feature.settings.resources.updates_footer_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.updates_hash_file
import ch.lkmc.neutrodyne.feature.settings.resources.updates_hash_hint
import ch.lkmc.neutrodyne.feature.settings.resources.updates_hash_label
import ch.lkmc.neutrodyne.feature.settings.resources.updates_how
import ch.lkmc.neutrodyne.feature.settings.resources.updates_invalid
import ch.lkmc.neutrodyne.feature.settings.resources.updates_last
import ch.lkmc.neutrodyne.feature.settings.resources.updates_limited
import ch.lkmc.neutrodyne.feature.settings.resources.updates_missing_apk
import ch.lkmc.neutrodyne.feature.settings.resources.updates_missing_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.updates_more
import ch.lkmc.neutrodyne.feature.settings.resources.updates_network
import ch.lkmc.neutrodyne.feature.settings.resources.updates_never
import ch.lkmc.neutrodyne.feature.settings.resources.updates_new_android
import ch.lkmc.neutrodyne.feature.settings.resources.updates_new_os
import ch.lkmc.neutrodyne.feature.settings.resources.updates_off
import ch.lkmc.neutrodyne.feature.settings.resources.updates_off_summary
import ch.lkmc.neutrodyne.feature.settings.resources.updates_on_android
import ch.lkmc.neutrodyne.feature.settings.resources.updates_on_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.updates_open_release
import ch.lkmc.neutrodyne.feature.settings.resources.updates_other_abi
import ch.lkmc.neutrodyne.feature.settings.resources.updates_released
import ch.lkmc.neutrodyne.feature.settings.resources.updates_releases
import ch.lkmc.neutrodyne.feature.settings.resources.updates_skip
import ch.lkmc.neutrodyne.feature.settings.resources.updates_stale
import ch.lkmc.neutrodyne.feature.settings.resources.updates_switch
import ch.lkmc.neutrodyne.feature.settings.resources.updates_title
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal fun UpdatesPage(
    build: BuildInfo,
    target: UpdateRuntimeTarget,
    openRelease: Boolean,
    vm: UpdatesSettingsViewModel = metroViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val saveFailed by vm.saveFailed.collectAsStateWithLifecycle()
    val writeError by vm.writeError.collectAsStateWithLifecycle()
    val requester = LocalPlatformActions.current.notifications
    val messages by vm.messages.collectAsStateWithLifecycle()
    ShowUserMessages(messages, vm::onMessageShown)
    UpdatesContent(
        state,
        build,
        target,
        openRelease,
        vm::checkNow,
        { vm.setEnabled(it, requester) },
        vm::skip,
        saveFailed || writeError != null,
    )
}

@Composable
internal fun UpdatesContent(
    state: UpdatesSettingsUiState,
    build: BuildInfo,
    target: UpdateRuntimeTarget,
    openRelease: Boolean = false,
    onCheck: () -> Unit = {},
    onToggle: (Boolean) -> Unit = {},
    onSkip: (Long) -> Unit = {},
    saveFailed: Boolean = false,
) {
    val open = updateLinkOpener()
    val navigator = LocalAppNavigator.current
    val requester = LocalPlatformActions.current.notifications
    var releaseConsumed by rememberSaveable { mutableStateOf(false) }
    val available = state.check as? UpdateCheckState.Available
    LaunchedEffect(openRelease, available?.info?.releaseUrl) {
        if (openRelease && !releaseConsumed && available != null) {
            // Consume before opening: restoration/recomposition must not relaunch a browser.
            releaseConsumed = true
            open(available.info.releaseUrl)
        }
    }
    val dev = build.debug || checksAreDevelopmentOnly(state.check)
    Column(Modifier.fillMaxSize().testTag("updates-page")) {
        NdTopAppBar(title = stringResource(Res.string.updates_title), navigation = { SettingsBackButton() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionContainer { Text(updateVersionLine(build)) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val check = state.check) {
                        is UpdateCheckState.Available -> {
                            UpdateCard(check.info, build, target, open, onSkip)
                        }

                        is UpdateCheckState.Disabled -> {
                            val text =
                                if (check.reason == UpdateDisabledReason.DEV_BUILD) {
                                    Res.string.updates_debug
                                } else {
                                    Res.string.updates_off
                                }
                            Text(stringResource(text))
                        }

                        is UpdateCheckState.Idle -> {
                            Text(stringResource(Res.string.updates_current))
                        }

                        UpdateCheckState.Checking -> {
                            Text(stringResource(Res.string.updates_checking))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }

                        is UpdateCheckState.Failed -> {
                            Text(updateErrorText(check.error))
                            NdOutlinedButton(
                                label = stringResource(Res.string.updates_releases),
                                onClick = { open("${build.repoUrl}/releases") },
                            )
                        }
                    }
                    available?.lastError?.let {
                        Text(stringResource(Res.string.updates_stale, updateErrorText(it)))
                    }
                }
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.updates_switch),
                summary =
                    stringResource(
                        when {
                            dev -> Res.string.updates_debug
                            !state.enabled -> Res.string.updates_off_summary
                            build.platform == BuildInfo.Platform.DESKTOP -> Res.string.updates_on_desktop
                            else -> Res.string.updates_on_android
                        },
                    ),
                checked = state.enabled && !dev,
                enabled = !dev,
                onCheckedChange = { enabled ->
                    onToggle(enabled)
                },
            )
            val lastCheckLabel =
                state.lastCheckAtMs?.let {
                    val relative = FeedDates.relative(it, LocalUiClock.current.now()).asString()
                    stringResource(Res.string.updates_last, relative)
                } ?: stringResource(Res.string.updates_never)
            Text(lastCheckLabel)
            if (!dev) {
                NdButton(
                    label = stringResource(Res.string.updates_check_now),
                    enabled = state.check != UpdateCheckState.Checking,
                    onClick = onCheck,
                )
            }
            if (requester?.granted == false) {
                NotificationPermissionNotice(enabled = !dev, requester = requester)
            }
            if (saveFailed) {
                Text(stringResource(Res.string.settings_save_failed), color = MaterialTheme.colorScheme.error)
            }
            SettingsRow(
                NdIcons.Info,
                stringResource(Res.string.install_help_title),
                null,
                { navigator.push(InstallHelpKey()) },
            )
            val footer =
                if (build.platform == BuildInfo.Platform.ANDROID) {
                    Res.string.updates_footer_android
                } else {
                    Res.string.updates_footer_desktop
                }
            Text(stringResource(footer, build.repoUrl))
        }
    }
}

@Composable
private fun UpdateCard(
    info: UpdateInfo,
    build: BuildInfo,
    target: UpdateRuntimeTarget,
    open: (String) -> Unit,
    onSkip: (Long) -> Unit,
) {
    val navigator = LocalAppNavigator.current
    var expanded by rememberSaveable(info.versionCode) { mutableStateOf(false) }
    var downloadOpened by rememberSaveable(info.versionCode) { mutableStateOf(false) }
    val download = updateDownload(info, target)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.updates_available, info.versionName),
            style = MaterialTheme.typography.titleMedium,
        )
        val released = DateFormatter.date(Instant.parse(info.publishedAt).toEpochMilliseconds())
        Text(stringResource(Res.string.updates_released, released))
        if (download != null) Text(download.kind + " · " + download.sizeBytes + " B")
        Text(info.notes, maxLines = if (expanded) Int.MAX_VALUE else NOTES_COLLAPSED_LINES)
        if (!expanded) TextButton(onClick = { expanded = true }) { Text(stringResource(Res.string.updates_more)) }
        val selection = info.desktopSelection
        when {
            target is UpdateRuntimeTarget.Android && info.minSdk > target.sdkInt -> {
                Text(stringResource(Res.string.updates_new_android, androidRelease(info.minSdk)))
            }

            selection is DesktopAssetSelection.RequiresNewerOs -> {
                Text(stringResource(Res.string.updates_new_os, osLabel(selection.os), selection.minOs))
            }

            download == null -> {
                val missingText =
                    if (target is UpdateRuntimeTarget.Android) {
                        Res.string.updates_missing_apk
                    } else {
                        Res.string.updates_missing_desktop
                    }
                val abi = (target as? UpdateRuntimeTarget.Android)?.preferredAbi ?: "?"
                Text(stringResource(missingText, abi))
            }
        }
        if (download != null) {
            Checksum(download.fileName, download.sha256, target)
            Text(stringResource(Res.string.updates_hash_hint))
            val downloadLabel =
                if (target is UpdateRuntimeTarget.Android) {
                    Res.string.updates_download_apk
                } else {
                    Res.string.updates_download_desktop
                }
            NdButton(label = stringResource(downloadLabel), onClick = {
                open(download.url)
                downloadOpened = true
            })
            val changesAbi = target is UpdateRuntimeTarget.Android && info.apk?.abi != build.apkAbi
            if (changesAbi) {
                val abiText =
                    if (info.apk?.abi in listOf("arm64-v8a", "x86_64")) {
                        Res.string.updates_64_bit
                    } else {
                        Res.string.updates_other_abi
                    }
                Text(stringResource(abiText, info.apk?.abi ?: "?"))
            }
            info.desktopAsset?.let { asset ->
                SelectionContainer {
                    val command = asset.kind == InstallKind.DEB || asset.kind == InstallKind.RPM
                    Text(
                        installHint(asset.kind, asset.fileName),
                        fontFamily = if (command) FontFamily.Monospace else FontFamily.Default,
                    )
                }
            }
            if (downloadOpened) Text(stringResource(Res.string.updates_after_download))
        }
        if (download == null) {
            NdButton(label = stringResource(Res.string.updates_open_release), onClick = { open(info.releaseUrl) })
        } else {
            NdOutlinedButton(
                label = stringResource(Res.string.updates_open_release),
                onClick = { open(info.releaseUrl) },
            )
        }
        TextButton(onClick = { onSkip(info.versionCode) }) { Text(stringResource(Res.string.updates_skip)) }
        TextButton(onClick = { navigator.push(InstallHelpKey(helpSection(build))) }) {
            Text(stringResource(Res.string.updates_how))
        }
    }
}

@Composable
private fun Checksum(
    file: String,
    sha256: String,
    target: UpdateRuntimeTarget,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val host = LocalSnackbarHost.current
    val copied = stringResource(Res.string.updates_copied)
    val copyChecksum = {
        clipboard.setText(AnnotatedString(sha256))
        if (target !is UpdateRuntimeTarget.Android || target.sdkInt < NATIVE_CLIPBOARD_FEEDBACK_API) {
            scope.launch { host.showSnackbar(copied) }
        }
    }
    val label = stringResource(Res.string.updates_hash_label)
    val copy = stringResource(Res.string.updates_copy_hash)
    Column {
        Text(stringResource(Res.string.updates_hash_file, file))
        SelectionContainer {
            Text(
                sha256.chunked(HASH_GROUP_SIZE).joinToString(" "),
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = label
                        customActions =
                            listOf(
                                CustomAccessibilityAction(copy) {
                                    copyChecksum()
                                    true
                                },
                            )
                    },
            )
        }
        TextButton(onClick = { copyChecksum() }) { Text(copy) }
    }
}

internal data class UpdateDownload(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: String,
)

internal fun updateDownload(
    info: UpdateInfo,
    target: UpdateRuntimeTarget,
): UpdateDownload? =
    when (target) {
        is UpdateRuntimeTarget.Android -> {
            info.apk?.takeIf { info.minSdk <= target.sdkInt }?.let {
                UpdateDownload(it.fileName, it.url, it.sizeBytes, it.sha256, "APK")
            }
        }

        is UpdateRuntimeTarget.Desktop -> {
            info.desktopAsset?.let {
                UpdateDownload(it.fileName, it.url, it.sizeBytes, it.sha256, kindLabel(it.kind))
            }
        }
    }

@Composable
private fun updateErrorText(error: UpdateCheckError): String =
    stringResource(
        when (error) {
            UpdateCheckError.NETWORK -> Res.string.updates_network
            UpdateCheckError.RATE_LIMITED -> Res.string.updates_limited
            UpdateCheckError.MANIFEST_INVALID -> Res.string.updates_invalid
        },
    )

@Composable
private fun installHint(
    kind: InstallKind,
    file: String,
): String =
    when (kind) {
        InstallKind.MSI -> stringResource(Res.string.install_hint_msi)
        InstallKind.ZIP -> stringResource(Res.string.install_hint_zip)
        InstallKind.DMG, InstallKind.MAC_ZIP -> stringResource(Res.string.install_hint_mac)
        InstallKind.DEB -> "sudo apt install ./$file"
        InstallKind.RPM -> "sudo dnf install ./$file"
        InstallKind.TAR_GZ -> stringResource(Res.string.install_hint_tar)
        InstallKind.DEV -> ""
    }

@Composable
internal fun updateVersionLine(build: BuildInfo): String =
    build.desktop?.let {
        "Neutrodyne ${build.versionName} · ${osLabel(it.os)} ${archLabel(it.arch)} · ${kindLabel(it.installKind)}"
    } ?: "Neutrodyne ${build.versionName} · ${build.apkAbi ?: "?"}"

internal fun androidRelease(sdk: Int): String =
    when (sdk) {
        26 -> "8"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        37 -> "17"
        else -> "API $sdk"
    }

internal fun helpSection(build: BuildInfo): String = build.desktop?.os?.name ?: "ALLOW"

private const val NOTES_COLLAPSED_LINES = 3
private const val HASH_GROUP_SIZE = 8

private const val NATIVE_CLIPBOARD_FEEDBACK_API = 33
