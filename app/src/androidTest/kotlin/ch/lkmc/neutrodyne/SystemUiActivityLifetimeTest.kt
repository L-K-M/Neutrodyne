// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/** System contrast observation must not keep destroyed activities alive across recreation. */
@RunWith(AndroidJUnit4::class)
class SystemUiActivityLifetimeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun systemUiObserversReleaseRecreatedActivities() {
        val destroyed = captureRecreatedActivities()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (destroyed.any { it.get() != null } && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Runtime.getRuntime().gc()
            System.runFinalization()
            SystemClock.sleep(100)
        }
        assertTrue(
            "Destroyed activities remain reachable after their scenarios close",
            destroyed.all { it.get() == null },
        )
    }

    private fun captureRecreatedActivities(): List<WeakReference<MainActivity>> {
        val destroyed = mutableListOf<WeakReference<MainActivity>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(hasTestTag("nav_feeds"))
                        .fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .isNotEmpty()
                }
                scenario.onActivity { destroyed.add(WeakReference(it)) }
                scenario.recreate()
            }
        }
        return destroyed
    }
}
