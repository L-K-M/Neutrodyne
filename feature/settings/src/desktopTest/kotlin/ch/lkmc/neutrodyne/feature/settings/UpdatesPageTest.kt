// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.update.DesktopAssetSelection
import ch.lkmc.neutrodyne.core.model.update.UpdateApk
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDesktopAsset
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import ch.lkmc.neutrodyne.core.navigation.InstallHelpKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeUpdateNotices
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.updateContractInfo
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UpdatesPageTest {
    private var oldLocale: Locale = Locale.ENGLISH
    private val navigator = RecordingAppNavigator()
    private val opened = mutableListOf<String>()
    private val apk =
        UpdateApk(
            "arm64-v8a",
            "neutrodyne-1.0.0-arm64-v8a.apk",
            "https://github.com/L-K-M/Neutrodyne/releases/download/v1.0.0/neutrodyne-1.0.0-arm64-v8a.apk",
            1234,
            "a".repeat(64),
        )
    private val info =
        updateContractInfo().copy(
            apk = apk,
            notes = "<b>plain text, not HTML</b>\nSecond line\nThird\nFourth",
        )
    private val availableState = UpdatesSettingsUiState(UpdateCheckState.Available(info, 123), lastCheckAtMs = 123)
    private val target = UpdateRuntimeTarget.Android("arm64-v8a", 36)

    @BeforeTest fun english() {
        oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(oldLocale)
    }

    @Test fun availableShowsPlainNotesAccessibleHashAndValidatedLinks() =
        runComposeUiTest {
            var skipped: Long? = null
            host {
                UpdatesContent(
                    UpdatesSettingsUiState(
                        UpdateCheckState.Available(info, 123),
                        lastCheckAtMs = 123,
                    ),
                    UpdateTestBuild,
                    target,
                    onSkip = {
                        skipped =
                            it
                    },
                )
            }
            onNodeWithText("Update available: 1.0.0").assertExists()
            onNodeWithText("<b>plain text, not HTML</b>", substring = true).assertExists()
            onNodeWithContentDescription("SHA-256 checksum").assertExists()
            onNodeWithText("Download APK for this device").performScrollTo().performClick()
            assertEquals(listOf(apk.url), opened)
            onNodeWithText("This is the 64-bit version", substring = true).assertExists()
            onNodeWithText("Open release on GitHub").performScrollTo().performClick()
            assertEquals(info.releaseUrl, opened.last())
            onNodeWithText("How to install").performScrollTo().performClick()
            assertEquals(RecordingAppNavigator.Call.Push(InstallHelpKey("ALLOW")), navigator.calls.last())
            onNodeWithText("Skip this version").performScrollTo().performClick()
            assertEquals(info.versionCode, skipped)
        }

    @Test fun copyFeedbackComplementsThePlatformClipboard() {
        for (sdk in listOf(32, 36)) {
            runComposeUiTest {
                host { UpdatesContent(availableState, UpdateTestBuild, target.copy(sdkInt = sdk)) }
                onNodeWithText("Copy SHA-256").performScrollTo().performClick()
                if (sdk == 32) {
                    onNodeWithText("Copied").assertExists()
                } else {
                    onNodeWithText("Copied").assertDoesNotExist()
                }
            }
        }
    }

    @Test fun oldAndroidAndMissingAbiExposeOnlyReleaseLink() {
        for (card in listOf(info.copy(minSdk = 37), info.copy(apk = null))) {
            runComposeUiTest {
                host {
                    UpdatesContent(
                        UpdatesSettingsUiState(UpdateCheckState.Available(card, 123)),
                        UpdateTestBuild,
                        target,
                    )
                }
                onNodeWithText("Download APK for this device").assertDoesNotExist()
                onNodeWithContentDescription("SHA-256 checksum").assertDoesNotExist()
                onNodeWithText("Open release on GitHub").assertExists()
            }
        }
    }

    @Test fun desktopCardShowsSelectedFileAndRejectsNewerOs() {
        val file = "neutrodyne-1.0.0-linux-arm64.deb"
        val url = "${UpdateTestBuild.repoUrl}/releases/download/v1.0.0/$file"
        val asset =
            UpdateDesktopAsset(
                DesktopOs.LINUX,
                DesktopArch.ARM64,
                InstallKind.DEB,
                file,
                url,
                1234,
                apk.sha256,
                null,
            )
        val build =
            UpdateTestDesktop.copy(
                desktop =
                    UpdateTestDesktop.desktop!!.copy(os = DesktopOs.LINUX, installKind = InstallKind.DEB),
            )
        runComposeUiTest {
            val card = info.copy(apk = null, desktopSelection = DesktopAssetSelection.Selected(asset))
            host {
                UpdatesContent(
                    UpdatesSettingsUiState(UpdateCheckState.Available(card, 123)),
                    build,
                    UpdateRuntimeTarget.Desktop("13"),
                )
            }
            onNodeWithText("Download for this computer").performScrollTo().performClick()
            assertEquals(listOf(asset.url), opened)
            onNodeWithText("sudo apt install ./" + asset.fileName).assertExists()
        }
        runComposeUiTest {
            val card =
                info.copy(
                    apk = null,
                    desktopSelection = DesktopAssetSelection.RequiresNewerOs(DesktopOs.MACOS, "99"),
                )
            host {
                UpdatesContent(
                    UpdatesSettingsUiState(UpdateCheckState.Available(card, 123)),
                    UpdateTestDesktop,
                    UpdateRuntimeTarget.Desktop("13"),
                )
            }
            onNodeWithText("This version needs macOS 99 or later").assertExists()
            onNodeWithText("Download for this computer").assertDoesNotExist()
            onNodeWithContentDescription("SHA-256 checksum").assertDoesNotExist()
        }
    }

    @Test fun checkingDisablesCheckNowAndDebugHidesIt() {
        runComposeUiTest {
            host { UpdatesContent(UpdatesSettingsUiState(UpdateCheckState.Checking), UpdateTestBuild, target) }
            onNodeWithText("Checking for updates…").assertExists()
            onNodeWithText("Check now").performScrollTo().assertIsNotEnabled()
        }
        runComposeUiTest {
            host {
                UpdatesContent(
                    UpdatesSettingsUiState(UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD)),
                    UpdateTestBuild.copy(debug = true),
                    target,
                )
            }
            onNodeWithText("Check now").assertDoesNotExist()
            onNodeWithText("Check for updates").assertIsNotEnabled()
        }
    }

    @Test fun allErrorsAndStaleAvailableStayReadable() {
        for (error in UpdateCheckError.entries) {
            runComposeUiTest {
                host {
                    UpdatesContent(
                        UpdatesSettingsUiState(UpdateCheckState.Failed(error, null)),
                        UpdateTestBuild,
                        target,
                    )
                }
                onNodeWithText("Open releases").assertExists()
                onNodeWithText("Not checked yet").assertExists()
            }
        }
        runComposeUiTest {
            host {
                UpdatesContent(
                    UpdatesSettingsUiState(UpdateCheckState.Available(info, 123, UpdateCheckError.NETWORK)),
                    UpdateTestBuild,
                    target,
                )
            }
            onNodeWithText("Couldn’t check again:", substring = true).assertExists()
            onNodeWithText("Open release on GitHub").assertExists()
        }
    }

    @Test fun notificationActionOpensReleaseOnceAcrossRecompositionAndRestoration() =
        runComposeUiTest {
            var change by mutableStateOf(false)
            var visible by mutableStateOf(true)
            var saved: Map<String, List<Any?>>? = null
            var registry: SaveableStateRegistry? = null
            host {
                if (visible) {
                    val entryRegistry = remember { SaveableStateRegistry(saved) { true } }
                    registry = entryRegistry
                    CompositionLocalProvider(LocalSaveableStateRegistry provides entryRegistry) {
                        TextProbe(change)
                        UpdatesContent(availableState, UpdateTestBuild, target, openRelease = true)
                    }
                }
            }
            waitForIdle()
            assertEquals(listOf(info.releaseUrl), opened)
            runOnIdle { change = true }
            runOnIdle {
                saved = registry!!.performSave()
                visible = false
            }
            runOnIdle { visible = true }
            waitForIdle()
            assertEquals(listOf(info.releaseUrl), opened)
        }

    @Test fun keyboardCanFocusAndOpenInstallHelp() =
        runComposeUiTest {
            host { UpdatesContent(availableState, UpdateTestBuild, target) }
            onNodeWithText("How to install")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
            assertEquals(RecordingAppNavigator.Call.Push(InstallHelpKey("ALLOW")), navigator.calls.last())
        }

    @Test fun noHandlerOffersCopyLink() =
        runComposeUiTest {
            host(result = OpenResult.NO_HANDLER) {
                UpdatesContent(availableState, UpdateTestBuild, target)
            }
            onNodeWithText("Open release on GitHub").performScrollTo().performClick()
            onNodeWithText("No app can open this link").assertExists()
            onNodeWithText("Copy link").performClick()
        }

    @Test fun verificationShowMeHowDismissesDurablyAndRoutesToGoogleSection() =
        runComposeUiTest {
            val notices = FakeUpdateNotices(listOf(UpdateNotice.VERIFICATION_ENFORCEMENT))
            host { VerificationNoticeDialog(notices) }
            onNodeWithText("Installing updates on phones with Google Play").assertExists()
            runOnIdle { notices.verificationPhase.value = VerificationPhase.POST_ENFORCEMENT }
            onNodeWithText("Keep updates working").assertExists()
            onNodeWithText("Show me how").performClick()
            waitForIdle()
            assertEquals(listOf(UpdateNotice.VERIFICATION_ENFORCEMENT), notices.dismissals)
            assertEquals(RecordingAppNavigator.Call.Push(InstallHelpKey("GOOGLE_PLAY")), navigator.calls.last())
        }

    @Test fun offlineHelpSelectsSectionAndPreservesAndroidAuto() =
        runComposeUiTest {
            host { InstallHelpPage(UpdateTestBuild, target, "ANDROID_AUTO", VerificationPhase.PRE_ENFORCEMENT) }
            onNodeWithText("Unknown sources", substring = true).assertExists()
            onNodeWithText("10 times", substring = true).assertExists()
            onNodeWithText("Installing and updating on macOS").assertDoesNotExist()
            assertEquals(emptyList(), opened)
        }

    @Test fun incompatibleReleaseHelpUsesMatchingInstalledTagAndFile() =
        runComposeUiTest {
            host {
                InstallHelpPage(
                    UpdateTestBuild,
                    target,
                    "CHECK",
                    VerificationPhase.PRE_ENFORCEMENT,
                    info.copy(minSdk = 37),
                )
            }
            val command =
                "gh release verify-asset v0.1.0 neutrodyne-0.1.0-arm64-v8a.apk" +
                    " --repo L-K-M/Neutrodyne"
            onNodeWithText(command).assertExists()
        }

    @Test fun developmentMacHelpNamesThePublishedZip() =
        runComposeUiTest {
            val build =
                UpdateTestDesktop.copy(
                    debug = true,
                    desktop = UpdateTestDesktop.desktop!!.copy(installKind = InstallKind.DEV),
                )
            host {
                InstallHelpPage(
                    build,
                    UpdateRuntimeTarget.Desktop("13"),
                    "CHECK",
                    VerificationPhase.PRE_ENFORCEMENT,
                )
            }
            val command = "gh attestation verify neutrodyne-0.1.0-macos-arm64.zip --repo L-K-M/Neutrodyne"
            onNodeWithText(command).assertExists()
        }

    @Test fun windowsArmHelpNamesThePublishedX64File() =
        runComposeUiTest {
            val build =
                UpdateTestDesktop.copy(
                    desktop =
                        UpdateTestDesktop.desktop!!.copy(
                            os = DesktopOs.WINDOWS,
                            arch = DesktopArch.ARM64,
                            installKind = InstallKind.ZIP,
                        ),
                )
            host {
                InstallHelpPage(
                    build,
                    UpdateRuntimeTarget.Desktop("10"),
                    "CHECK",
                    VerificationPhase.PRE_ENFORCEMENT,
                )
            }
            val command = "gh attestation verify neutrodyne-0.1.0-windows-x64.zip --repo L-K-M/Neutrodyne"
            onNodeWithText(command).assertExists()
        }

    @Test fun germanLargeTextRtlHelpKeepsDesktopInstructionsReachable() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            host {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    InstallHelpPage(
                        UpdateTestDesktop,
                        UpdateRuntimeTarget.Desktop("13"),
                        "WINDOWS",
                        VerificationPhase.PRE_ENFORCEMENT,
                    )
                }
            }
            onNodeWithText("Smart App Control", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText("Telefone mit Google Play").assertDoesNotExist()
        }

    private fun ComposeUiTest.host(
        result: OpenResult = OpenResult.OPENED,
        content: @Composable () -> Unit,
    ) {
        val actions =
            object : PlatformActions {
                override val urls =
                    ExternalUrlOpener {
                        opened += it
                        result
                    }
                override val notifications = null
                override val share = null
                override val reveal = null
                override val saver = FileSaver { _, _ -> null }
                override val files =
                    object : FilePicker {
                        override suspend fun pickFile(
                            mimeTypes: List<String>,
                            extensions: List<String>,
                        ): String? = null

                        override suspend fun pickFolder(title: String): String? = null
                    }
            }
        val composition: @Composable () -> Unit = {
            val snackbar = remember { SnackbarHostState() }
            CompositionLocalProvider(
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides actions,
                LocalSnackbarHost provides snackbar,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    Column {
                        androidx.compose.foundation.layout
                            .Box(Modifier.weight(1f)) { content() }
                        SnackbarHost(snackbar)
                    }
                }
            }
        }
        setContent(composition)
    }
}

@Composable
private fun TextProbe(value: Boolean) {
    rememberSaveable(value) { value }
}
