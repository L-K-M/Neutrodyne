// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.Application
import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadComponentTestApplication :
    Application(),
    GraphHolder {
    override lateinit var graph: Any
}

@Implements(JobService::class)
class RecordingDownloadJobShadow : ShadowJobService() {
    var notifications = 0

    @Implementation(minSdk = 34)
    override fun setNotification(
        params: JobParameters,
        id: Int,
        notification: Notification,
        policy: Int,
    ) {
        notifications++
        super.setNotification(params, id, notification, policy)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    application = DownloadComponentTestApplication::class,
    sdk = [34, 36],
    shadows = [RecordingDownloadJobShadow::class],
)
class ManualDownloadJobServiceTest {
    private val fixture = SchedulerFixture()
    private val app = RuntimeEnvironment.getApplication() as DownloadComponentTestApplication
    private val stops = CopyOnWriteArrayList<StopIntent>()
    private val entered = CompletableDeferred<Unit>()
    private val databaseOpen = CompletableDeferred<Unit>()
    private val runtime =
        AndroidDownloadRuntime(
            drain = { request ->
                request.exit.register(request.runnerToken, request.lanes)
                request.exit.tryClose(request.runnerToken, DrainOutcome.Empty)
                DrainOutcome.Empty
            },
            reconcile = {
                entered.complete(Unit)
                databaseOpen.await()
            },
            stop = { _, intent -> stops += intent },
            wake = {},
            controller = { FakeDownloadController() },
        )

    @Test fun notificationIsSynchronousWhileDatabaseOpenIsHeldAndPermissionDenied() =
        runBlocking<Unit> {
            org.robolectric.Shadows
                .shadowOf(app)
                .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val controller = createService()
            val service = controller.get()
            val params = parameters()
            assertTrue(service.onStartJob(params))
            val shadow = Shadow.extract<RecordingDownloadJobShadow>(service)
            assertEquals(1, shadow.notifications)
            assertTrue(fixture.registry.uidtRunning())
            withTimeout(AWAIT_MS) { entered.await() }
            assertFalse(shadow.getIsJobFinished())
            databaseOpen.complete(Unit)
            withTimeout(AWAIT_MS) { while (!shadow.getIsJobFinished()) delay(5) }
            assertFalse(shadow.getIsRescheduleNeeded())
            assertFalse(fixture.registry.uidtRunning())
            controller.destroy()
        }

    @Test fun callbackUserStopPausesAndDoesNotRequestReschedule() =
        runBlocking<Unit> {
            val controller = createService()
            val service = controller.get()
            val params = parameters()
            service.onStartJob(params)
            withTimeout(AWAIT_MS) { entered.await() }
            ReflectionHelpers.setField(params, "mStopReason", JobParameters.STOP_REASON_USER)
            assertFalse(service.onStopJob(params))
            assertEquals(StopIntent.RunnerStopped(JobParameters.STOP_REASON_USER, RunnerStopMode.PAUSE), stops.single())
            withTimeout(AWAIT_MS) { while (fixture.registry.uidtRunning()) delay(5) }
            controller.destroy()
        }

    @Test fun connectivityStopRequeuesAndRequestsPersistedJobReschedule() =
        runBlocking<Unit> {
            val controller = createService()
            val service = controller.get()
            val params = parameters()
            service.onStartJob(params)
            withTimeout(AWAIT_MS) { entered.await() }
            ReflectionHelpers.setField(params, "mStopReason", JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            assertTrue(service.onStopJob(params))
            assertEquals(
                StopIntent.Requeue(WaitReason.NETWORK, JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY),
                stops.single(),
            )
            withTimeout(AWAIT_MS) { while (fixture.registry.uidtRunning()) delay(5) }
            controller.destroy()
        }

    private fun createService(): org.robolectric.android.controller.ServiceController<ManualDownloadJobService> {
        app.graph =
            object : DownloadComponentsInjector {
                override fun inject(service: ManualDownloadJobService) {
                    service.scheduler = lazy { fixture.scheduler }
                    service.notifications = lazy { DownloadNotifications(app, fixture.clock) }
                    service.runtime = lazy { runtime }
                    service.clock = fixture.clock
                }

                override fun inject(receiver: DownloadActionReceiver) = error("unused")
            }
        return Robolectric.buildService(ManualDownloadJobService::class.java).create()
    }

    private fun parameters(): JobParameters = Shadow.newInstanceOf(JobParameters::class.java)

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
