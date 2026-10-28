// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** API34+ ATF negative control. Only this test replaces activity content with a broken fixture. */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun enabledChecksFailAnUnlabelledTinyTargetBeforeTheClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            compose.enableAccessibilityChecks()
        }
        val clicked = AtomicBoolean()
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.testTag(PROBE_TAG).size(8.dp).clickable { clicked.set(true) })
            }
        }
        compose.waitForIdle()

        val thrown =
            assertThrows(AccessibilityViewCheckException::class.java) {
                compose.onNodeWithTag(PROBE_TAG).performClick()
            }
        assertThat(clicked.get()).isFalse()
        assertThat(thrown.results).isNotEmpty()
        assertThat(thrown.results.map { it.sourceCheckClass.simpleName })
            .containsAnyOf("TouchTargetSizeCheck", "SpeakableTextPresentCheck")
    }

    private companion object {
        const val PROBE_TAG = "atfNegativeProbe"
    }
}
