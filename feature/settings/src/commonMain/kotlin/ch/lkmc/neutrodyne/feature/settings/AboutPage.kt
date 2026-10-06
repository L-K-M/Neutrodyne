// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.about_debug_build
import ch.lkmc.neutrodyne.feature.settings.resources.about_licence_android
import ch.lkmc.neutrodyne.feature.settings.resources.about_licence_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.about_source
import ch.lkmc.neutrodyne.feature.settings.resources.about_version
import ch.lkmc.neutrodyne.feature.settings.resources.about_version_abi
import ch.lkmc.neutrodyne.feature.settings.resources.about_version_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.app_name
import ch.lkmc.neutrodyne.feature.settings.resources.settings_about
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › About (01 About statements, 08 About): app name, the version-and-identity line for
 * the platform, the platform's licence statement, the Licences row and the source link. All facts
 * come from the shell-bound [BuildInfo]; links open through `LocalPlatformActions`.
 */
@Composable
internal fun AboutPage(buildInfo: BuildInfo) {
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings_about),
            navigation = { SettingsBackButton() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                versionLine(buildInfo),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (buildInfo.debug) {
                Text(
                    stringResource(Res.string.about_debug_build),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(licenceStatementFor(buildInfo.platform)),
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsRow(
                icon = NdIcons.Article,
                title = stringResource(Res.string.settings_licences),
                summary = null,
                onClick = { navigator.push(LicencesKey) },
            )
            SettingsRow(
                icon = NdIcons.Explore,
                title = stringResource(Res.string.about_source),
                summary = buildInfo.repoUrl,
                onClick = { urls.open(buildInfo.repoUrl) },
            )
        }
    }
}

/** The platform's version line (08 About): "Version 1.0.0 (1000095) · arm64-v8a" on Android. */
@Composable
private fun versionLine(buildInfo: BuildInfo): String = when (buildInfo.platform) {
    BuildInfo.Platform.ANDROID -> {
        val abi = buildInfo.apkAbi
        if (abi != null) {
            stringResource(Res.string.about_version_abi, buildInfo.versionName, buildInfo.versionCode, abi)
        } else {
            stringResource(Res.string.about_version, buildInfo.versionName, buildInfo.versionCode)
        }
    }
    BuildInfo.Platform.DESKTOP -> {
        val desktop = buildInfo.desktop
        if (desktop == null) {
            stringResource(Res.string.about_version, buildInfo.versionName, buildInfo.versionCode)
        } else {
            stringResource(
                Res.string.about_version_desktop,
                buildInfo.versionName,
                osLabel(desktop.os),
                archLabel(desktop.arch),
                kindLabel(desktop.installKind),
            )
        }
    }
}

/** The licence statement is one per platform (01 About statements). */
private fun licenceStatementFor(platform: BuildInfo.Platform) = when (platform) {
    BuildInfo.Platform.ANDROID -> Res.string.about_licence_android
    BuildInfo.Platform.DESKTOP -> Res.string.about_licence_desktop
}

// OS/arch/install-kind labels are product names, not translated copy.
private fun osLabel(os: DesktopOs): String = when (os) {
    DesktopOs.WINDOWS -> "Windows"
    DesktopOs.MACOS -> "macOS"
    DesktopOs.LINUX -> "Linux"
}

private fun archLabel(arch: DesktopArch): String = when (arch) {
    DesktopArch.X64 -> "x64"
    DesktopArch.ARM64 -> "arm64"
}

private fun kindLabel(kind: InstallKind): String = when (kind) {
    InstallKind.MSI -> "MSI"
    InstallKind.ZIP -> "ZIP"
    InstallKind.DMG -> "DMG"
    InstallKind.MAC_ZIP -> "ZIP"
    InstallKind.DEB -> "DEB"
    InstallKind.RPM -> "RPM"
    InstallKind.TAR_GZ -> "tar.gz"
    InstallKind.DEV -> "dev build"
}
