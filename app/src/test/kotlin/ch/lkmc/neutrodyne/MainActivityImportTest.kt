// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ImportHostApplication::class, sdk = [34])
class MainActivityImportTest {
    @Test
    fun warmDeliveryReplacesTheConsumedIntent() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as ImportHostApplication
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val delivery = Intent(Intent.ACTION_VIEW, Uri.parse("neutrodyne://open/import/42"))
        controller.newIntent(delivery)
        assertThat(controller.get().intent).isEqualTo(delivery)
        controller.destroy()
        app.productionGraph.appScope.coroutineContext[kotlinx.coroutines.Job]
            ?.cancel()
    }

    @Test
    fun coldDeliveryQueuesImportKeyUntilTheRootIsReady() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as ImportHostApplication
        val delivery =
            Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("neutrodyne://open/import/71")
            }
        val controller = Robolectric.buildActivity(MainActivity::class.java, delivery).create()
        assertThat(
            runBlocking {
                controller
                    .get()
                    .routing.routes
                    .first()
            },
        ).isEqualTo(Route.Navigate(LibraryKey, listOf(ImportKey("71"))))
        controller.destroy()
        app.productionGraph.appScope.coroutineContext[kotlinx.coroutines.Job]
            ?.cancel()
    }

    @Test
    fun warmDeliveryRoutesEveryNewSessionThroughTheSharedRouter() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as ImportHostApplication
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        for (id in listOf("42", "43")) {
            val delivery =
                Intent(app, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("neutrodyne://open/import/$id")
                }
            controller.newIntent(delivery)
            val expected = IntentRouter.route(RouteInput(RouteInput.Action.VIEW, delivery.dataString, internal = true))
            assertThat(
                runBlocking {
                    controller
                        .get()
                        .routing.routes
                        .first()
                },
            ).isEqualTo(expected)
            assertThat(expected).isEqualTo(Route.Navigate(LibraryKey, listOf(ImportKey(id))))
        }
        controller.destroy()
        app.productionGraph.appScope.coroutineContext[kotlinx.coroutines.Job]
            ?.cancel()
    }

    @Test
    fun preSessionErrorIsRetainedForTheMainActivityDialog() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as ImportHostApplication
        val delivery =
            Intent(app, MainActivity::class.java)
                .putExtra(IMPORT_ERROR_EXTRA, ImportAcquisitionError.UNREADABLE.name)
        val controller = Robolectric.buildActivity(MainActivity::class.java, delivery).create()
        assertThat(
            controller
                .get()
                .routing.error.value,
        ).isEqualTo(ImportAcquisitionError.UNREADABLE)
        assertThat(
            app.getString(
                controller
                    .get()
                    .routing.error.value!!
                    .message,
            ),
        ).isEqualTo("Couldn't open this file. Try choosing it from inside Neutrodyne.")
        controller.destroy()
        app.productionGraph.appScope.coroutineContext[kotlinx.coroutines.Job]
            ?.cancel()
    }

    @Test
    fun externalImportHasAnAppCompatHost() {
        val host = runCatching { Class.forName("ch.lkmc.neutrodyne.ExternalImportActivity") }.getOrNull()
        assertThat(host).isNotNull()
        assertThat(AppCompatActivity::class.java.isAssignableFrom(host!!)).isTrue()
    }
}
