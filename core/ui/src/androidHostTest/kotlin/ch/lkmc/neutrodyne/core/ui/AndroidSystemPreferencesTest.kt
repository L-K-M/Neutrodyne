// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ch.lkmc.neutrodyne.core.designsystem.rememberAndroidSystemUiState
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSystemPreferencesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun animatorPreferenceChangesReachTheRunningComposition() {
        val resolver = rule.activity.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val state = AtomicReference<SystemUiState>()
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            rule.setContent {
                val current = rememberAndroidSystemUiState()
                SideEffect { state.set(current) }
            }
            rule.waitForIdle()
            assertFalse(state.get().reducedMotion)
            // ShadowSettings stores values but does not emit the SettingsProvider notification.
            val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            rule.runOnIdle {
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                resolver.notifyChange(uri, null)
            }
            rule.waitForIdle()
            kotlin.test.assertTrue(state.get().reducedMotion)
            rule.runOnIdle {
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                resolver.notifyChange(uri, null)
            }
            rule.waitForIdle()
            assertFalse(state.get().reducedMotion)
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
        }
    }
}
