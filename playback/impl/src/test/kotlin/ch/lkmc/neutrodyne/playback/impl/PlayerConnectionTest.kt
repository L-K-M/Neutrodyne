// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerConnectionTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun belowStartedNeverBuildsAndObservationDoesNotConnect() =
        runTest {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            var builds = 0
            val connection =
                PlayerConnection(owner.lifecycle) {
                    builds++
                    SettableFuture.create()
                }
            assertNull(connection.controller)
            assertNull(connection.awaitController())
            assertEquals(0, builds)
        }

    @Test fun concurrentWaitersShareOneConnectionAndProcessStopReleasesIt() =
        runTest {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val future = SettableFuture.create<MediaController>()
            var builds = 0
            val connection =
                PlayerConnection(owner.lifecycle) {
                    builds++
                    future
                }
            val one = async { connection.awaitController() }
            val two = async { connection.awaitController() }
            runCurrent()
            assertEquals(1, builds)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            connection.onStop(owner)
            assertTrue(future.isCancelled)
            one.await()
            two.await()
            assertNull(connection.controller)
        }

    @Test fun timeoutReleasesThePendingBinding() =
        runTest {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val future = SettableFuture.create<MediaController>()
            val connection = PlayerConnection(owner.lifecycle) { future }
            assertNull(connection.awaitController(100))
            assertTrue(future.isCancelled)
            assertNull(connection.controller)
        }
}
