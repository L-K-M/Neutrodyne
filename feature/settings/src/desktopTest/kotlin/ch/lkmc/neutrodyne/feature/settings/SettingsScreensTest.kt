// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The M0a Settings screens driven through `NeutrodyneRoot` on the desktop JVM (01 S9 method): the
 * gear opens the home rows, About renders the `BuildInfo` identity and the Licences screen renders
 * the fake `LicencesSource`'s data. The five destinations are stubs — only `:feature:settings`'s
 * own installer is under test.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreensTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun settingsHomeShowsM0aRows() =
        runComposeUiTest {
            setSettingsRoot()
            openSettings()
            // "Appearance" is also the detail placeholder's title, so the row is told by its summary.
            onAllNodesWithText("Appearance").onFirst().assertExists()
            onNodeWithText("Theme, dynamic colour and contrast").assertExists()
            onNodeWithText("About").assertExists()
            onNodeWithText("Licences").assertExists()
        }

    @Test
    fun aboutShowsVersionIdentityAndLicence() =
        runComposeUiTest {
            setSettingsRoot()
            openSettings()
            onAllNodesWithText("About").onFirst().performClick()

            onNodeWithText("Neutrodyne").assertExists()
            onNodeWithText("Version 1.2.3 · macOS arm64 · DMG").assertIsDisplayed()
            onNodeWithText("Debug build").assertExists()
            onNodeWithText("GNU LGPL 2.1", substring = true).assertExists()
            onNodeWithText("Source code and releases").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun licencesListsLibrariesFromSource() =
        runComposeUiTest {
            setSettingsRoot()
            openSettings()
            onAllNodesWithText("Licences").onFirst().performClick()
            waitForIdle()
            onNodeWithText("kotlinx-coroutines-core").assertExists()
            onNodeWithText("Libraries · 1 library").assertExists()
            onNodeWithText("kotlinx-coroutines-core").performClick()
            waitForIdle()
            onNodeWithText("Apache License test body", substring = true).assertExists()
            onNodeWithText("Close").performClick()
            onNodeWithText("Apache License test body", substring = true).assertDoesNotExist()
        }

    @Test
    fun licencesEmptyWithoutSource() =
        runComposeUiTest {
            setSettingsRoot(licencesSource = null)
            openSettings()
            onAllNodesWithText("Licences").onFirst().performClick()

            onNodeWithText("No licence information in this build.").assertIsDisplayed()
        }

    @Test
    fun licencesDisclosesEngineAbiOnEngineFreeApk() =
        runComposeUiTest {
            setSettingsRoot(
                licencesSource = BundledLicencesSource,
                buildInfo =
                    FakeBuildInfo.copy(
                        platform = BuildInfo.Platform.ANDROID,
                        apkAbi = "armeabi-v7a",
                        youTubeEngineBundled = false,
                    ),
            )
            openSettings()
            onAllNodesWithText("Licences").onFirst().performClick()
            waitForIdle()

            onNodeWithText("Bundled components", substring = true).assertExists()
            onNodeWithText("Used by the YouTube engine of the 64-bit versions").assertExists()
        }

    @Test
    fun aboutLinkWithoutHandlerOffersCopyLink() =
        runComposeUiTest {
            setSettingsRoot(platformActions = NoHandlerActions)
            openSettings()
            onAllNodesWithText("About").onFirst().performClick()
            onNodeWithText("Source code and releases").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText("No app can open this link").assertIsDisplayed()
            onNodeWithText("Copy link").assertIsDisplayed()
        }

    @Test
    fun appearanceChoosesAndPersistsTheme() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            setSettingsRoot(settingsRepository = settings)
            openSettings()
            onAllNodesWithText("Appearance").onFirst().performClick()
            waitForIdle()

            onNodeWithText("System default").assertIsDisplayed()
            onNodeWithText("Theme").performClick()
            waitForIdle()
            onNodeWithText("Dark").performClick()
            waitForIdle()

            onNodeWithText("Dark").assertIsDisplayed()
            assertEquals(ThemeMode.DARK, runBlocking { settings.get(AppearanceSettingKeys.THEME) })
        }

    @Test
    fun appearanceHidesWallpaperRowWithoutPlatformSupport() =
        runComposeUiTest {
            setSettingsRoot()
            openSettings()
            onAllNodesWithText("Appearance").onFirst().performClick()
            waitForIdle()

            onNodeWithText("Use wallpaper colours").assertDoesNotExist()
        }

    @Test
    fun appearanceWallpaperRowWritesOnSupportedAndroid() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            setSettingsRoot(
                settingsRepository = settings,
                platformInfo = FakePlatformInfo.ANDROID,
            )
            openSettings()
            onAllNodesWithText("Appearance").onFirst().performClick()
            waitForIdle()

            onNodeWithText("Use wallpaper colours").performClick()
            waitForIdle()

            assertEquals(false, runBlocking { settings.get(AppearanceSettingKeys.DYNAMIC_COLOR) })
        }

    private fun ComposeUiTest.openSettings() {
        onAllNodesWithContentDescription("Settings").onFirst().performClick()
        onNodeWithText("About").assertExists()
    }

    private fun ComposeUiTest.setSettingsRoot(
        licencesSource: LicencesSource? = FakeLicencesSource,
        buildInfo: BuildInfo = FakeBuildInfo,
        settingsRepository: FakeSettingsRepository = FakeSettingsRepository(),
        platformInfo: PlatformInfo = FakePlatformInfo.DESKTOP,
        platformActions: PlatformActions = TestPlatformActions,
    ) {
        setContent {
            CompositionLocalProvider(LocalPlatformActions provides platformActions) {
                NeutrodyneRoot(
                    state = RootUiState.READY,
                    actions =
                        RootActions(
                            retryStartup = {},
                            dismissNotice = {},
                            continueHere = {},
                            dismissRemoteSession = {},
                            playbackKey = { false },
                        ),
                    slots = RootSlots(player = {}, userMessages = emptyFlow()),
                    installers =
                        StubInstallers +
                            SettingsNavigation.entries(
                                buildInfo,
                                licencesSource,
                                settingsRepository,
                                platformInfo,
                            ),
                    platform = BuildInfo.Platform.DESKTOP,
                )
            }
        }
    }
}

private val FakeBuildInfo =
    BuildInfo(
        versionName = "1.2.3",
        versionCode = 42,
        debug = true,
        platform = BuildInfo.Platform.DESKTOP,
        repoUrl = "https://github.com/lkmc/neutrodyne",
        updateManifestUrl = "https://example.invalid/updates.json",
        engineManifestUrl = "https://example.invalid/engine.json",
        youTubeEngineBundled = false,
        desktop =
            BuildInfo.Desktop(
                os = DesktopOs.MACOS,
                arch = DesktopArch.ARM64,
                installKind = InstallKind.DMG,
                runtime = "bundled",
            ),
        shippedLocales = persistentListOf("en", "de"),
        podcastIndexKey = "",
        podcastIndexSecret = "",
    )

private val Apache =
    License(
        name = "Apache-2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0",
        year = "",
        spdxId = "Apache-2.0",
        licenseContent = "Apache License test body",
        hash = "",
    )

private val FakeLicencesSource =
    LicencesSource {
        Libs(
            libraries =
                persistentListOf(
                    Library(
                        uniqueId = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
                        artifactVersion = "1.10.2",
                        name = "kotlinx-coroutines-core",
                        description = "",
                        website = "https://github.com/Kotlin/kotlinx.coroutines",
                        developers = persistentListOf(),
                        organization = null,
                        scm = null,
                        licenses = persistentSetOf(Apache),
                        funding = persistentSetOf(),
                        tag = "",
                        targets = persistentSetOf(),
                    ),
                ),
            licenses = persistentSetOf(Apache),
        )
    }

/** One `bundled`-tagged library so the engine section renders (F10). */
private val BundledLicencesSource =
    LicencesSource {
        Libs(
            libraries =
                persistentListOf(
                    Library(
                        uniqueId = "org.example:engine-stub:1.0",
                        artifactVersion = "1.0",
                        name = "engine-stub",
                        description = "",
                        website = "",
                        developers = persistentListOf(),
                        organization = null,
                        scm = null,
                        licenses = persistentSetOf(Apache),
                        funding = persistentSetOf(),
                        tag = "bundled",
                        targets = persistentSetOf(),
                    ),
                ),
            licenses = persistentSetOf(Apache),
        )
    }

private object FakePlatformInfo {
    val DESKTOP = platformInfo(PlatformKind.DESKTOP)
    val ANDROID = platformInfo(PlatformKind.ANDROID, sdkInt = 34)

    private fun platformInfo(
        kind: PlatformKind,
        sdkInt: Int? = null,
    ) = object : PlatformInfo {
        override val kind = kind
        override val userAgentPlatform = kind.name.lowercase()
        override val androidSdkInt = sdkInt
        override val regionCode = "CH"
    }
}

/** The minimal `PlatformActions` the settings screens read (share = null means desktop, D83). */
private val TestPlatformActions =
    object : PlatformActions {
        override val urls: ExternalUrlOpener = ExternalUrlOpener { OpenResult.OPENED }
        override val share = null
        override val files: FilePicker =
            object : FilePicker {
                override suspend fun pickFile(
                    mimeTypes: List<String>,
                    extensions: List<String>,
                ): String? = null

                override suspend fun pickFolder(title: String): String? = null
            }
        override val saver: FileSaver = FileSaver { _, _ -> null }
        override val reveal = null
        override val notifications = null
    }

/** Every external-open call reports `NO_HANDLER` (F11: About's copy-link fallback). */
private val NoHandlerActions =
    object : PlatformActions by TestPlatformActions {
        override val urls: ExternalUrlOpener = ExternalUrlOpener { OpenResult.NO_HANDLER }
    }

private val TOP_LEVEL_STUB_TABS: List<TopLevelKey> =
    listOf(FeedsKey, LibraryKey, UpNextKey, DownloadsKey, DiscoverKey)

private val StubInstallers: Set<EntryProviderInstaller> =
    TOP_LEVEL_STUB_TABS
        .map { tab ->
            val installer: EntryProviderInstaller = {
                entry(tab, metadata = NdSceneMetadata.paneList()) {
                    TopBarStub(tab)
                }
            }
            installer
        }.toSet()

@Composable
private fun TopBarStub(tab: TopLevelKey) {
    val title = if (tab == FeedsKey) stringResource(Res.string.nav_feeds) else tab.toString()
    NdTopAppBar(title = title)
}
