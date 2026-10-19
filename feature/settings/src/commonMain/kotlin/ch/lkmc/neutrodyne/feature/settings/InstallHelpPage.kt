// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.help_access_bridge_command
import ch.lkmc.neutrodyne.feature.settings.resources.help_advanced_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_advanced_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_allow_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_allow_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_android_auto_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_android_auto_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_builds_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_builds_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.help_builds_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_check_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_check_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_collapsed
import ch.lkmc.neutrodyne.feature.settings.resources.help_copy
import ch.lkmc.neutrodyne.feature.settings.resources.help_developer_options
import ch.lkmc.neutrodyne.feature.settings.resources.help_download_android
import ch.lkmc.neutrodyne.feature.settings.resources.help_download_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_download_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_expanded
import ch.lkmc.neutrodyne.feature.settings.resources.help_google_play_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_google_play_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_linux_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_linux_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_macos_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_macos_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_obtainium_add
import ch.lkmc.neutrodyne.feature.settings.resources.help_obtainium_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_obtainium_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_privacy_settings
import ch.lkmc.neutrodyne.feature.settings.resources.help_screen_readers_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_screen_readers_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_uncertified_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_uncertified_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_uninstall_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_uninstall_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_version_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_version_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.help_version_target
import ch.lkmc.neutrodyne.feature.settings.resources.help_version_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_why_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_why_title
import ch.lkmc.neutrodyne.feature.settings.resources.help_windows_body
import ch.lkmc.neutrodyne.feature.settings.resources.help_windows_title
import ch.lkmc.neutrodyne.feature.settings.resources.install_help_title
import ch.lkmc.neutrodyne.feature.settings.resources.notice_post_body
import ch.lkmc.neutrodyne.feature.settings.resources.notice_pre_body
import ch.lkmc.neutrodyne.feature.settings.resources.updates_more
import ch.lkmc.neutrodyne.feature.settings.resources.updates_releases
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class InstallHelpSection {
    VERSION,
    DOWNLOAD,
    CHECK,
    BUILDS,
    ALLOW,
    GOOGLE_PLAY,
    UNCERTIFIED,
    ADVANCED,
    OBTAINIUM,
    ANDROID_AUTO,
    MACOS,
    WINDOWS,
    LINUX,
    SCREEN_READERS,
    UNINSTALL,
    WHY,
}

internal fun helpSections(
    build: BuildInfo,
    phase: VerificationPhase,
): List<InstallHelpSection> {
    val common =
        listOf(
            InstallHelpSection.VERSION,
            InstallHelpSection.DOWNLOAD,
            InstallHelpSection.CHECK,
            InstallHelpSection.BUILDS,
        )
    if (build.platform == BuildInfo.Platform.DESKTOP) {
        val current = InstallHelpSection.entries.firstOrNull { it.name == build.desktop?.os?.name }
        return common + listOfNotNull(current) +
            listOf(InstallHelpSection.SCREEN_READERS, InstallHelpSection.UNINSTALL, InstallHelpSection.WHY) +
            listOf(InstallHelpSection.MACOS, InstallHelpSection.WINDOWS, InstallHelpSection.LINUX).filter {
                it !=
                    current
            }
    }
    val androidSections =
        common +
            listOf(
                InstallHelpSection.ALLOW,
                InstallHelpSection.GOOGLE_PLAY,
                InstallHelpSection.UNCERTIFIED,
                InstallHelpSection.ADVANCED,
                InstallHelpSection.OBTAINIUM,
                InstallHelpSection.ANDROID_AUTO,
                InstallHelpSection.WHY,
            )
    return if (phase ==
        VerificationPhase.POST_ENFORCEMENT
    ) {
        listOf(InstallHelpSection.GOOGLE_PLAY) +
            androidSections.filter { it != InstallHelpSection.GOOGLE_PLAY }
    } else {
        androidSections
    }
}

@Composable
internal fun InstallHelpPage(
    build: BuildInfo,
    target: UpdateRuntimeTarget,
    section: String,
    phase: VerificationPhase,
    available: UpdateInfo? = null,
) {
    val open = updateLinkOpener()
    val expandedLabel = stringResource(Res.string.help_expanded)
    val collapsedLabel = stringResource(Res.string.help_collapsed)
    val sections = helpSections(build, phase)
    val clipboard = LocalClipboardManager.current
    val platformActions = LocalPlatformActions.current
    val urls = platformActions.urls
    val commands = helpCommands(build, target, available)
    val selected = sections.firstOrNull { it.name == section }
    // Unknown or foreign section names leave everything collapsed; desktop's default opens its OS.
    val initial =
        selected ?: if (section.isEmpty()) sections.firstOrNull { it.name == build.desktop?.os?.name } else null
    var expanded by rememberSaveable { mutableStateOf(initial?.name) }
    val scroll = rememberScrollState()
    val positions = remember { mutableMapOf<InstallHelpSection, Int>() }
    var scrolled by rememberSaveable { mutableStateOf(false) }
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(positioned) {
        if (positioned && !scrolled && initial != null) {
            scrolled = true
            scroll.scrollTo(positions[initial] ?: 0)
        }
    }
    Column(Modifier.fillMaxSize().testTag("install-help-page")) {
        NdTopAppBar(title = stringResource(Res.string.install_help_title), navigation = { SettingsBackButton() })
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionContainer { Text(updateVersionLine(build)) }
            (target as? UpdateRuntimeTarget.Android)?.let {
                Text(
                    stringResource(
                        Res.string.help_version_target,
                        it.preferredAbi ?: "?",
                    ),
                )
            }
            build.desktop?.let { Text(it.runtime) }
            for (item in sections) {
                Column(
                    Modifier.onGloballyPositioned {
                        positions[item] = it.positionInParent().y.toInt()
                        if (item == initial) positioned = true
                    },
                ) {
                    SettingsRow(
                        NdIcons.Info,
                        stringResource(helpTitle(item)),
                        null,
                        {
                            expanded =
                                if (expanded == item.name) null else item.name
                        },
                        modifier =
                            Modifier.semantics {
                                stateDescription =
                                    if (expanded == item.name) expandedLabel else collapsedLabel
                            },
                    )
                    if (expanded == item.name) {
                        if (item == InstallHelpSection.GOOGLE_PLAY) {
                            Text(
                                stringResource(
                                    if (phase == VerificationPhase.PRE_ENFORCEMENT) {
                                        Res.string.notice_pre_body
                                    } else {
                                        Res.string.notice_post_body
                                    },
                                ),
                            )
                        }
                        SelectionContainer { Text(stringResource(helpBody(item, build.platform))) }
                        val itemCommands =
                            when (item) {
                                InstallHelpSection.CHECK -> {
                                    commands
                                }

                                InstallHelpSection.ADVANCED -> {
                                    listOf("adb install -r " + helpFileName(build, target, available))
                                }

                                InstallHelpSection.MACOS -> {
                                    listOf("xattr -dr com.apple.quarantine /Applications/Neutrodyne.app")
                                }

                                InstallHelpSection.LINUX -> {
                                    listOf(
                                        "sudo apt install ./FILE.deb",
                                        "sudo dnf install ./FILE.rpm",
                                        "sudo zypper install ./FILE.rpm",
                                        "update-desktop-database ~/.local/share/applications",
                                    )
                                }

                                InstallHelpSection.SCREEN_READERS -> {
                                    listOf(stringResource(Res.string.help_access_bridge_command))
                                }

                                else -> {
                                    emptyList()
                                }
                            }
                        for (command in itemCommands) {
                            SelectionContainer { Text(command, fontFamily = FontFamily.Monospace) }
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(command)) },
                            ) { Text(stringResource(Res.string.help_copy)) }
                        }
                        if (item ==
                            InstallHelpSection.MACOS
                        ) {
                            TextButton(onClick = {
                                open("x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension")
                            }) { Text(stringResource(Res.string.help_privacy_settings)) }
                        }
                        if (item in
                            listOf(InstallHelpSection.DOWNLOAD, InstallHelpSection.VERSION, InstallHelpSection.CHECK)
                        ) {
                            Text(build.repoUrl)
                            TextButton(
                                onClick = { open("${build.repoUrl}/releases") },
                            ) { Text(stringResource(Res.string.updates_releases)) }
                        }
                        if (item ==
                            InstallHelpSection.WHY
                        ) {
                            TextButton(
                                onClick = { open("${build.repoUrl}#install-and-update") },
                            ) { Text(stringResource(Res.string.updates_more)) }
                        }
                        if (item ==
                            InstallHelpSection.OBTAINIUM
                        ) {
                            TextButton(
                                onClick = {
                                    if (urls.open("obtainium://add/${build.repoUrl}") ==
                                        OpenResult.NO_HANDLER
                                    ) {
                                        open("https://github.com/ImranR98/Obtainium")
                                    }
                                },
                            ) { Text(stringResource(Res.string.help_obtainium_add)) }
                        }
                    }
                }
            }
        }
    }
}

private fun helpTitle(section: InstallHelpSection): StringResource =
    when (section) {
        InstallHelpSection.VERSION -> Res.string.help_version_title
        InstallHelpSection.DOWNLOAD -> Res.string.help_download_title
        InstallHelpSection.CHECK -> Res.string.help_check_title
        InstallHelpSection.BUILDS -> Res.string.help_builds_title
        InstallHelpSection.ALLOW -> Res.string.help_allow_title
        InstallHelpSection.GOOGLE_PLAY -> Res.string.help_google_play_title
        InstallHelpSection.UNCERTIFIED -> Res.string.help_uncertified_title
        InstallHelpSection.ADVANCED -> Res.string.help_advanced_title
        InstallHelpSection.OBTAINIUM -> Res.string.help_obtainium_title
        InstallHelpSection.ANDROID_AUTO -> Res.string.help_android_auto_title
        InstallHelpSection.MACOS -> Res.string.help_macos_title
        InstallHelpSection.WINDOWS -> Res.string.help_windows_title
        InstallHelpSection.LINUX -> Res.string.help_linux_title
        InstallHelpSection.SCREEN_READERS -> Res.string.help_screen_readers_title
        InstallHelpSection.UNINSTALL -> Res.string.help_uninstall_title
        InstallHelpSection.WHY -> Res.string.help_why_title
    }

private fun helpBody(
    section: InstallHelpSection,
    platform: BuildInfo.Platform,
): StringResource =
    when (section) {
        InstallHelpSection.VERSION -> {
            if (platform == BuildInfo.Platform.ANDROID) {
                Res.string.help_version_body
            } else {
                Res.string.help_version_desktop
            }
        }

        InstallHelpSection.DOWNLOAD -> {
            if (platform == BuildInfo.Platform.ANDROID) {
                Res.string.help_download_android
            } else {
                Res.string.help_download_body
            }
        }

        InstallHelpSection.CHECK -> {
            Res.string.help_check_body
        }

        InstallHelpSection.BUILDS -> {
            if (platform == BuildInfo.Platform.ANDROID) {
                Res.string.help_builds_body
            } else {
                Res.string.help_builds_desktop
            }
        }

        InstallHelpSection.ALLOW -> {
            Res.string.help_allow_body
        }

        InstallHelpSection.GOOGLE_PLAY -> {
            Res.string.help_google_play_body
        }

        InstallHelpSection.UNCERTIFIED -> {
            Res.string.help_uncertified_body
        }

        InstallHelpSection.ADVANCED -> {
            Res.string.help_advanced_body
        }

        InstallHelpSection.OBTAINIUM -> {
            Res.string.help_obtainium_body
        }

        InstallHelpSection.ANDROID_AUTO -> {
            Res.string.help_android_auto_body
        }

        InstallHelpSection.MACOS -> {
            Res.string.help_macos_body
        }

        InstallHelpSection.WINDOWS -> {
            Res.string.help_windows_body
        }

        InstallHelpSection.LINUX -> {
            Res.string.help_linux_body
        }

        InstallHelpSection.SCREEN_READERS -> {
            Res.string.help_screen_readers_body
        }

        InstallHelpSection.UNINSTALL -> {
            Res.string.help_uninstall_body
        }

        InstallHelpSection.WHY -> {
            Res.string.help_why_body
        }
    }

private fun helpFileName(
    build: BuildInfo,
    target: UpdateRuntimeTarget,
    available: UpdateInfo?,
): String {
    available?.let { updateDownload(it, target)?.let { download -> return download.fileName } }
    val abi = (target as? UpdateRuntimeTarget.Android)?.preferredAbi ?: build.apkAbi
    val desktop = build.desktop ?: return "neutrodyne-${build.versionName}-$abi.apk"
    val extension =
        when (desktop.installKind) {
            InstallKind.MAC_ZIP -> {
                "zip"
            }

            InstallKind.DEV -> {
                when (desktop.os) {
                    DesktopOs.WINDOWS -> {
                        "zip"
                    }

                    DesktopOs.MACOS -> {
                        if (build.versionName.startsWith("0.")) "zip" else "dmg"
                    }

                    DesktopOs.LINUX -> {
                        "tar.gz"
                    }
                }
            }

            else -> {
                desktop.installKind.name
                    .lowercase()
                    .replace('_', '.')
            }
        }
    val os =
        when (desktop.os) {
            DesktopOs.WINDOWS -> "windows"
            DesktopOs.MACOS -> "macos"
            DesktopOs.LINUX -> "linux"
        }
    // Windows on Arm uses the same x64 release files (PO-40).
    val assetArch = if (desktop.os == DesktopOs.WINDOWS) DesktopArch.X64 else desktop.arch
    return "neutrodyne-${build.versionName}-$os-${archLabel(assetArch)}.$extension"
}

private fun helpCommands(
    build: BuildInfo,
    target: UpdateRuntimeTarget,
    available: UpdateInfo?,
): List<String> {
    // A release-only card has no downloadable file: keep its tag out of installed-file commands.
    val selected = available?.takeIf { updateDownload(it, target) != null }
    val file = helpFileName(build, target, selected)
    val repo = build.repoUrl.removePrefix("https://github.com/")
    val version = selected?.versionName ?: build.versionName
    val checksum =
        when (build.desktop?.os) {
            DesktopOs.WINDOWS -> "Get-FileHash $file -Algorithm SHA256"
            DesktopOs.MACOS -> "shasum -a 256 $file"
            else -> "sha256sum --check --ignore-missing SHA256SUMS"
        }
    return listOf(
        checksum,
        "gh release verify-asset v$version $file --repo $repo",
        "gh attestation verify $file --repo $repo",
    )
}
