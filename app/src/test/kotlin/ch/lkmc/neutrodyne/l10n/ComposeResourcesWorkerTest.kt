// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.l10n

import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.remote_session_detail
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * S11's worker leg (01 S11): the suspend `getString` resolves Compose resources outside
 * composition, the way a WorkManager worker would use it. Robolectric cannot run the resources
 * library's `AndroidContextProvider`, so the test installs the application context itself — the
 * escape hatch `setResourceReaderAndroidContext` exists for. Outside composition the resource
 * environment reads `Locale.getDefault()`, which is what `AppCompatDelegate.setApplicationLocales`
 * updates on the worker path; the test pins it explicitly.
 */
@OptIn(ExperimentalResourceApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeResourcesWorkerTest {
    private lateinit var previousLocale: Locale

    @Before
    fun installReaderContext() {
        previousLocale = Locale.getDefault()
        setResourceReaderAndroidContext(RuntimeEnvironment.getApplication())
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun suspendGetStringResolvesOutsideComposition() =
        runBlocking {
            assertThat(getString(Res.string.nav_feeds)).isEqualTo("Feeds")
        }

    @Test
    fun suspendGetStringFollowsTheEnvironmentLocale() =
        runBlocking {
            Locale.setDefault(Locale.ENGLISH)
            assertThat(getString(Res.string.nav_library)).isEqualTo("Library")

            Locale.setDefault(Locale.GERMAN)
            assertThat(getString(Res.string.nav_library)).isEqualTo("Bibliothek")
        }

    @Test
    fun suspendGetStringFormatsArguments() =
        runBlocking {
            Locale.setDefault(Locale.GERMAN)
            assertThat(getString(Res.string.remote_session_detail, "Show", "01:02", "desk"))
                .isEqualTo("Show · 01:02 · von desk")
        }
}
