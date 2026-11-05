// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = DownloadComponentTestApplication::class, sdk = [30, 33, 34, 36])
class DownloadActionReceiverTest {
    @Test fun heldDatabaseTimesOutAndFinishesBroadcastBeforeTenSeconds() =
        runTest {
            val result = receive(this, DownloadActionReceiver.PAUSE_ALL)
            assertFalse(Shadow.extract<ShadowBroadcastPendingResult>(result).future.isDone)
            advanceUntilIdle()
            assertTrue(testScheduler.currentTime < 10_000)
            assertTrue(Shadow.extract<ShadowBroadcastPendingResult>(result).future.isDone)
        }

    @Test fun cancelledApplicationScopeStillFinishesBroadcast() =
        runTest {
            val job = Job().also { it.cancel() }
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
            val result = receive(scope, DownloadActionReceiver.DISMISS_ERRORS)
            advanceUntilIdle()
            assertTrue(Shadow.extract<ShadowBroadcastPendingResult>(result).future.isDone)
        }

    private fun receive(
        scope: CoroutineScope,
        action: String,
    ): BroadcastReceiver.PendingResult {
        val app = RuntimeEnvironment.getApplication() as DownloadComponentTestApplication
        val clock = TestClock()
        val opener =
            DatabaseOpener(
                AndroidDatabaseFactory(app),
                object : SQLiteDriver {
                    override fun open(fileName: String): SQLiteConnection = error("Must not open database")
                },
                kotlinx.coroutines.Dispatchers.IO,
                clock,
                true,
            )
        app.graph =
            object : DownloadComponentsInjector {
                override fun inject(service: ManualDownloadJobService) = error("unused")

                override fun inject(receiver: DownloadActionReceiver) {
                    receiver.appScope = scope
                    receiver.opener = opener
                    receiver.runtime = lazy { error("Database must be ready before controller resolves") }
                    receiver.db = lazy { error("Database is held") }
                    receiver.notifications = lazy { DownloadNotifications(app, clock) }
                }
            }
        val pending: BroadcastReceiver.PendingResult =
            ReflectionHelpers.callStaticMethod(
                ShadowBroadcastPendingResult::class.java,
                "create",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
                ReflectionHelpers.ClassParameter.from(String::class.java, null),
                ReflectionHelpers.ClassParameter.from(Bundle::class.java, null),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
            )
        val receiver = DownloadActionReceiver()
        ReflectionHelpers.callInstanceMethod<Unit>(
            receiver,
            "setPendingResult",
            ReflectionHelpers.ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending),
        )
        receiver.onReceive(app, Intent(action))
        return pending
    }
}
