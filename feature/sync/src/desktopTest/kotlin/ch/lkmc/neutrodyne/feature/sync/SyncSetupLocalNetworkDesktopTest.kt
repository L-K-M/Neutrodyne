// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.sync.api.LinkState
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The desktop half of the boundary (10 Local-network gate): no OS prompt exists, so the real
 * [SyncSetupScreen] answers `grantLocalNetwork` straight from the tap — the pre-37 behaviour —
 * and the platform bridge stays `null`.
 */
@OptIn(ExperimentalTestApi::class)
class SyncSetupLocalNetworkDesktopTest {
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
    fun desktopTapAnswersDirectlyWithoutAnOsPrompt() =
        runComposeUiTest {
            val controller =
                FakeSyncController(
                    link = FakeLinkFlow(LinkState.NeedsLocalNetworkPermission("192.168.7.4")),
                )
            val actions =
                object : PlatformActions {
                    override val urls = ExternalUrlOpener { OpenResult.OPENED }
                    override val share = null
                    override val files =
                        object : FilePicker {
                            override suspend fun pickFile(
                                mimeTypes: List<String>,
                                extensions: List<String>,
                            ): String? = null

                            override suspend fun pickFolder(title: String): String? = null
                        }

                    override val saver = FileSaver { _, _ -> null }
                    override val reveal = null
                    override val notifications = null
                }
            var prompt: LocalNetworkPermissionPrompt? = LocalNetworkPermissionPrompt {}
            setContent {
                // collectAsStateWithLifecycle needs a started owner the bare scene lacks.
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalPlatformActions provides actions,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        prompt = rememberLocalNetworkPermissionPrompt()
                        SyncSetupScreen(controller, "http://192.168.7.4", FakeBackupRepository())
                    }
                }
            }
            waitForIdle()
            assertNull(prompt)
            onNodeWithText("Continue").performClick()
            waitForIdle()
            assertEquals(listOf(true), controller.localNetworkGrants)
        }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }
}
