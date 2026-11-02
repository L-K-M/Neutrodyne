// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import ch.lkmc.neutrodyne.core.navigation.InstallHelpKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class UpdateIntentRoutingTest {
    @Test fun explicitUpdateActionReachesReleaseEntry() =
        runBlocking {
            val model = MainIntentViewModel(SavedStateHandle())
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse("neutrodyne://open/settings/updates/release"))
                    .setClassName("ch.lkmc.neutrodyne", INTERNAL_MAIN_ACTIVITY)
            model.receive(intent, "ch.lkmc.neutrodyne")
            val expected = Route.Push(SettingsKey(SettingsPage.UPDATES, openRelease = true))
            assertThat(model.routes.first()).isEqualTo(expected)
        }

    @Test fun explicitHelpLinkReachesBundledPage() =
        runBlocking {
            val model = MainIntentViewModel(SavedStateHandle())
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse("neutrodyne://open/help/install"))
                    .setClassName("ch.lkmc.neutrodyne", INTERNAL_MAIN_ACTIVITY)
            model.receive(intent, "ch.lkmc.neutrodyne")
            assertThat(model.routes.first()).isEqualTo(Route.Push(InstallHelpKey()))
        }
}
