// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = ImportHostApplication::class, sdk = [34])
class AndroidImportDeliveryRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication() as ImportHostApplication

    private fun explicit(uri: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            // Public API available identically to an unrelated sender. This is the destination.
            component = ComponentName(app.packageName, MainActivity::class.java.name)
        }

    private fun internalIntent(uri: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            component = ComponentName(app.packageName, "ch.lkmc.neutrodyne.InternalMainActivity")
        }

    private fun nextRoute(model: MainIntentViewModel): Route? =
        runBlocking {
            withTimeoutOrNull(200) { model.routes.first() }
        }

    @Test fun foreignExplicitIntentCannotOpenInternalSettings() {
        val info =
            app.packageManager.getActivityInfo(ComponentName(app.packageName, MainActivity::class.java.name), 0)
        assertThat(info.exported).isTrue()
        val model = MainIntentViewModel(SavedStateHandle())
        model.receive(explicit("neutrodyne://open/settings/backup"), app.packageName)
        assertThat(nextRoute(model)).isNull()
    }

    @Test fun foreignExplicitIntentCannotForgeAcquisitionErrors() {
        val model = MainIntentViewModel(SavedStateHandle())
        model.receive(explicit("neutrodyne://invalid").putExtra(IMPORT_ERROR_EXTRA, "STORAGE"), app.packageName)
        assertThat(model.error.value).isNull()
    }

    @Test fun pendingImportSurvivesFreshActivityFromSavedState() {
        val delivery = internalIntent("neutrodyne://open/import/71")
        val first = Robolectric.buildActivity(MainActivity::class.java, delivery).create()
        val saved = Bundle()
        first.saveInstanceState(saved)
        val oldModel = first.get().routing
        // No root has collected the route: the host is still before STARTED / the startup gate.
        first.destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, delivery).create(saved)
        try {
            assertThat(restored.get().routing).isNotSameInstanceAs(oldModel)
            assertThat(nextRoute(restored.get().routing))
                .isEqualTo(Route.Navigate(LibraryKey, listOf(ImportKey("71"))))
        } finally {
            restored.destroy()
            app.productionGraph.appScope.cancel()
        }
    }

    @Test fun pendingAcquisitionErrorSurvivesFreshActivityFromSavedState() {
        val delivery = internalIntent("neutrodyne://invalid").putExtra(IMPORT_ERROR_EXTRA, "UNREADABLE")
        val first = Robolectric.buildActivity(MainActivity::class.java, delivery).create()
        assertThat(
            first
                .get()
                .routing.error.value,
        ).isEqualTo(ImportAcquisitionError.UNREADABLE)
        val saved = Bundle()
        first.saveInstanceState(saved)
        first.destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, delivery).create(saved)
        try {
            assertThat(
                restored
                    .get()
                    .routing.error.value,
            ).isEqualTo(ImportAcquisitionError.UNREADABLE)
        } finally {
            restored.destroy()
            app.productionGraph.appScope.cancel()
        }
    }

    @Test
    fun unrelatedInternalNotificationKeepsThePendingAcquisitionError() {
        val model = MainIntentViewModel(SavedStateHandle())
        model.receive(
            internalIntent("neutrodyne://invalid").putExtra(IMPORT_ERROR_EXTRA, "UNREADABLE"),
            app.packageName,
        )

        model.receive(internalIntent("neutrodyne://open/import/71"), app.packageName)

        assertThat(model.error.value).isEqualTo(ImportAcquisitionError.UNREADABLE)
    }

    @Test
    fun consumedImportAndDismissedErrorAreNotReplayedAfterRecreation() {
        val delivery =
            internalIntent("neutrodyne://open/import/71")
                .putExtra(IMPORT_ERROR_EXTRA, "UNREADABLE")
        val first = Robolectric.buildActivity(MainActivity::class.java, delivery).create()
        assertThat(nextRoute(first.get().routing)).isNotNull()
        first.get().routing.dismissError()
        val saved = Bundle()
        first.saveInstanceState(saved)
        first.destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, delivery).create(saved)
        try {
            assertThat(nextRoute(restored.get().routing)).isNull()
            assertThat(
                restored
                    .get()
                    .routing.error.value,
            ).isNull()
        } finally {
            restored.destroy()
            app.productionGraph.appScope.cancel()
        }
    }

    @Test
    fun exportedLauncherCannotSeedSavedStateFromIntentExtras() {
        val delivery =
            explicit("neutrodyne://invalid")
                .putExtra("pendingImportSession", 71L)
                .putExtra("pendingImportError", "STORAGE")
        val controller = Robolectric.buildActivity(MainActivity::class.java, delivery).create()
        try {
            assertThat(nextRoute(controller.get().routing)).isNull()
            assertThat(
                controller
                    .get()
                    .routing.error.value,
            ).isNull()
        } finally {
            controller.destroy()
            app.productionGraph.appScope.cancel()
        }
    }

    @Test
    fun internalAliasIsNotExportedAndTargetsTheLauncher() {
        val info = app.packageManager.getActivityInfo(ComponentName(app.packageName, INTERNAL_MAIN_ACTIVITY), 0)
        assertThat(info.exported).isFalse()
        assertThat(info.targetActivity).isEqualTo(MainActivity::class.java.name)
    }

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun leavingAfterCopyPostsReadyNotificationForPersistedSession() {
        setResourceReaderAndroidContext(app)
        val graph = app.productionGraph
        runBlocking(Dispatchers.IO) { graph.databaseOpener.awaitOpen() }
        val repository = runBlocking(Dispatchers.IO) { graph.importRepository }
        app.imports = repository
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertThat(manager.areNotificationsEnabled()).isTrue()
        val db = graph.databaseOpener.requireDatabase()
        val locked = CompletableDeferred<Unit>()
        val unlock = CompletableDeferred<Unit>()
        val blockerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val blocker =
            blockerScope.launch {
                db.withWriteTransaction {
                    locked.complete(Unit)
                    unlock.await()
                }
            }
        runBlocking { withTimeout(5000) { locked.await() } }
        val uri = Uri.parse("content://review/list.opml")
        val closed = AtomicBoolean(false)
        val opml =
            "<opml version=\"2.0\"><body>" +
                "<outline text=\"Show\" type=\"rss\" xmlUrl=\"https://example.org/feed\"/></body></opml>"
        shadowOf(app.contentResolver).registerInputStream(
            uri,
            object : ByteArrayInputStream(opml.toByteArray()) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            },
        )
        val existingJobs =
            graph.appScope.coroutineContext[Job]!!
                .children
                .toSet()
        val controller =
            Robolectric
                .buildActivity(
                    ExternalImportActivity::class.java,
                    Intent(Intent.ACTION_VIEW, uri),
                ).create()
        var destroyed = false
        try {
            pumpUntil {
                closed.get() &&
                    graph.appScope.coroutineContext[Job]!!
                        .children
                        .any { it is Deferred<*> && it !in existingJobs }
            }
            val stages =
                graph.appScope.coroutineContext[Job]!!
                    .children
                    .filter {
                        it is Deferred<*> &&
                            it !in existingJobs
                    }.toList()
            assertThat(stages).hasSize(1)
            assertThat(controller.get().isFinishing).isFalse()
            controller.get().finish()
            controller.destroy()
            destroyed = true
            unlock.complete(Unit)
            runBlocking { withTimeout(5000) { blocker.join() } }
            val session =
                runBlocking(Dispatchers.IO) {
                    withTimeout(10000) { repository.observeOpenSessions().first { it.isNotEmpty() }.single() }
                }
            assertThat(session.state).isEqualTo(ImportState.PREVIEW)
            runBlocking { withTimeout(5000) { stages.joinAll() } }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
            // Persistence and its detached observer complete on the application scope.
            pumpUntil { manager.activeNotifications.any { it.id == 3002 } }
            assertThat(manager.activeNotifications.map { it.id })
                .contains(3002)
            val posted = manager.activeNotifications.single()
            assertThat(posted.tag).isEqualTo(session.id.toString())
            assertThat(posted.notification.channelId).isEqualTo("import_backup")
            val tap = shadowOf(posted.notification.contentIntent).savedIntent
            assertThat(tap.component?.className).isEqualTo(INTERNAL_MAIN_ACTIVITY)
            val routing = MainIntentViewModel(SavedStateHandle())
            routing.receive(tap, app.packageName)
            assertThat(nextRoute(routing)).isEqualTo(
                Route.Navigate(LibraryKey, listOf(ImportKey(session.id.toString()))),
            )
        } finally {
            unlock.complete(Unit)
            if (!destroyed) controller.destroy()
            blockerScope.cancel()
            graph.appScope.cancel()
            db.close()
        }
    }
}
