// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.Route
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ImportHostApplication::class, sdk = [34])
class DiscoveryIntentTest {
    @Test
    fun sharesAcceptOnlyBoundedPlainText() =
        runBlocking {
            val vm = MainIntentViewModel(SavedStateHandle())
            val apple = "https://podcasts.apple.com/us/podcast/id123"
            vm.receive(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, apple)
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://untrusted/file")),
                PACKAGE,
            )
            assertThat(vm.routes.first()).isEqualTo(Route.Push(AddPodcastKey(apple)))
            for (intent in listOf(
                Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_TEXT, apple),
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, apple),
                Intent(Intent.ACTION_SEND).setType("text/plain"),
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, 42),
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, " "),
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "é".repeat(2049)),
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x".repeat(4097)),
            )) {
                vm.receive(intent, PACKAGE)
                assertThat(withTimeoutOrNull(20) { vm.routes.first() }).isNull()
            }
        }

    @Test
    fun viewSchemesAndWrappersOnlyNavigateToAdd() =
        runBlocking {
            val vm = MainIntentViewModel(SavedStateHandle())
            for (url in listOf(
                "feed:https://example.test/feed",
                "pcast://example.test/feed",
                "podcast:https://example.test/feed",
                "itpc://example.test/feed",
                "neutrodyne://subscribe?url=https%3A%2F%2Fexample.test%2Ffeed",
                "https://podcasts.apple.com/us/podcast/id123",
            )) {
                vm.receive(Intent(Intent.ACTION_VIEW, Uri.parse(url)), PACKAGE)
                assertThat(vm.routes.first()).isEqualTo(Route.Push(AddPodcastKey(url)))
            }
            vm.receive(
                Intent(Intent.ACTION_VIEW, Uri.parse("neutrodyne://open/podcast/1"))
                    .putExtra(IMPORT_ERROR_EXTRA, ImportAcquisitionError.STORAGE.name),
                PACKAGE,
            )
            assertThat(withTimeoutOrNull(20) { vm.routes.first() }).isNull()
            assertThat(vm.error.value).isNull()
        }

    @Test
    fun coldAndWarmActivityDeliveryQueueSharedLinks() =
        runBlocking {
            val app = org.robolectric.RuntimeEnvironment.getApplication() as ImportHostApplication
            val apple = "https://podcasts.apple.com/us/podcast/id123"
            val cold = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, apple)
            val controller = Robolectric.buildActivity(MainActivity::class.java, cold).create()
            try {
                assertThat(
                    controller
                        .get()
                        .routing.routes
                        .first(),
                ).isEqualTo(Route.Push(AddPodcastKey(apple)))
                val warm = Intent(Intent.ACTION_VIEW, Uri.parse("feed:https://example.test/feed"))
                controller.newIntent(warm)
                assertThat(
                    controller
                        .get()
                        .routing.routes
                        .first(),
                ).isEqualTo(Route.Push(AddPodcastKey(warm.dataString)))
                assertThat(controller.get().intent).isEqualTo(warm)
            } finally {
                controller.destroy()
                app.productionGraph.appScope.coroutineContext[kotlinx.coroutines.Job]
                    ?.cancel()
            }
        }

    private companion object {
        const val PACKAGE = "ch.lkmc.neutrodyne.debug"
    }
}
