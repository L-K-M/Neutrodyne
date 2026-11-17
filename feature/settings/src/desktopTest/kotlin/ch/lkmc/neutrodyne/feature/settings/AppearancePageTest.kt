// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class AppearancePageTest {
    private lateinit var previous: Locale

    @BeforeTest fun english() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restore() {
        Locale.setDefault(previous)
    }

    /** The retired Theme row is gone; the cover-size chooser writes `LIBRARY_DENSITY`. */
    @Test
    fun coverSizeChooserWritesLargeAndThemeRowIsGone() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            setAppearance(settings)

            onNodeWithText("Theme").assertDoesNotExist()
            onNodeWithText("Cover size").performClick()
            onNodeWithText("Large").performClick()
            waitForIdle()

            assertEquals(
                LibraryDensity.LARGE,
                runBlocking { settings.get(AppearanceSettingKeys.LIBRARY_DENSITY) },
            )
        }

    /** A failed write keeps the stored value and says so, as the retired theme rows did. */
    @Test
    fun aFailedWriteSaysSoAndKeepsTheStoredCoverSize() =
        runComposeUiTest {
            val settings = FakeSettingsRepository().apply { failNextSet = SettingsError.WriteFailed }
            val snackbar = SnackbarHostState()
            setAppearance(settings, snackbar)

            onNodeWithText("Cover size").performClick()
            onNodeWithText("Large").performClick()
            waitUntil { snackbar.currentSnackbarData != null }

            assertEquals("Couldn’t save the setting", snackbar.currentSnackbarData?.visuals?.message)
            assertNotEquals(
                LibraryDensity.LARGE,
                runBlocking { settings.get(AppearanceSettingKeys.LIBRARY_DENSITY) },
            )
        }

    private fun ComposeUiTest.setAppearance(
        settings: FakeSettingsRepository,
        snackbar: SnackbarHostState = SnackbarHostState(),
    ) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply {
                        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    }
            }
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalAppNavigator provides RecordingAppNavigator(),
                LocalSnackbarHost provides snackbar,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    AppearancePage(settings, PlatformKind.DESKTOP)
                }
            }
        }
        waitForIdle()
    }
}
