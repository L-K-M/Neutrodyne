// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.sync.api.LinkState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Android 17 local-network boundary of the sync setup, through the production
 * [SyncSetupScreen] and bridge (10 Local-network gate): Continue runs the real
 * `ActivityResultContracts.RequestPermission` launch for `ACCESS_LOCAL_NETWORK` and only the
 * OS answer — never the tap — reaches `grantLocalNetwork`. A fake registry stands in for the
 * OS, so no system dialog or network runs; the SDK seam pins 37 because Robolectric tops out at
 * 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncSetupLocalNetworkPermissionTest {
    @get:Rule val compose = createComposeRule()

    private val registry = FakeActivityResultRegistry()
    private val controller =
        FakeSyncController(
            link = FakeLinkFlow(LinkState.NeedsLocalNetworkPermission("192.168.7.4")),
        )

    @Before
    fun pinAndroid17() {
        testLocalNetworkSdkInt = 37
    }

    @After
    fun unpinSdk() {
        testLocalNetworkSdkInt = null
    }

    private fun showScreen() {
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides
                    object : ActivityResultRegistryOwner {
                        override val activityResultRegistry = registry
                    },
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncSetupScreen(controller, "http://192.168.7.4")
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tapContinue() {
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
    }

    /** Completes the last OS request the way the system would: RESULT_OK plus the grant array. */
    private fun answerRequest(grantResult: Int) {
        registry.dispatchResult(
            registry.launches.last().requestCode,
            Activity.RESULT_OK,
            Intent()
                .putExtra(
                    ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(grantResult),
                ),
        )
        compose.waitForIdle()
    }

    @Test
    fun compositionNeverLaunchesTheOsPrompt() {
        showScreen()
        assertTrue(registry.launches.isEmpty())
        assertTrue(controller.localNetworkGrants.isEmpty())
    }

    @Test
    fun continueLaunchesExactlyTheLocalNetworkPermission() {
        showScreen()
        tapContinue()
        val launch = registry.launches.single()
        assertIs<ActivityResultContracts.RequestPermission>(launch.contract)
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", launch.input)
        // The tap is not the answer: nothing reaches the controller before the OS result.
        assertTrue(controller.localNetworkGrants.isEmpty())
    }

    @Test
    fun grantedOsResultAnswersTheFlow() {
        showScreen()
        tapContinue()
        answerRequest(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf(true), controller.localNetworkGrants)
    }

    @Test
    fun deniedOsResultAnswersFalse() {
        showScreen()
        tapContinue()
        answerRequest(PackageManager.PERMISSION_DENIED)
        assertEquals(listOf(false), controller.localNetworkGrants)
    }

    @Test
    fun anUnansweredOsPromptAnswersFalse() {
        showScreen()
        tapContinue()
        // The delivery without a grant — the contract's own parse maps it to denied.
        registry.dispatchResult(registry.launches.last().requestCode, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(listOf(false), controller.localNetworkGrants)
    }

    @Test
    fun aSecondTapWhileThePromptIsOutLaunchesNothing() {
        showScreen()
        tapContinue()
        tapContinue()
        assertEquals(1, registry.launches.size)
        answerRequest(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf(true), controller.localNetworkGrants)
    }

    @Test
    fun cancelReportsFalseWithoutAnOsPrompt() {
        showScreen()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(registry.launches.isEmpty())
        assertEquals(listOf(false), controller.localNetworkGrants)
    }

    @Test
    fun belowAndroid17TheTapAnswersDirectly() {
        testLocalNetworkSdkInt = 36
        showScreen()
        tapContinue()
        assertTrue(registry.launches.isEmpty())
        assertEquals(listOf(true), controller.localNetworkGrants)
    }

    /** Records each OS request; [dispatchResult] answers it through the real contract's parse. */
    private class FakeActivityResultRegistry : ActivityResultRegistry() {
        class Launch(
            val requestCode: Int,
            val contract: ActivityResultContract<*, *>,
            val input: Any?,
        )

        val launches = mutableListOf<Launch>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += Launch(requestCode, contract, input)
        }
    }
}
