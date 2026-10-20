// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.lkmc.neutrodyne.core.common.AppScope
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeout

/** Main-thread only. A read of state, or a background command, never creates the service. */
@SingleIn(AppScope::class)
internal class PlayerConnection internal constructor(
    private val lifecycle: Lifecycle,
    private val build: () -> ListenableFuture<MediaController>,
) : DefaultLifecycleObserver {
    @Inject
    constructor(application: Application) : this(
        ProcessLifecycleOwner.get().lifecycle,
        {
            MediaController
                .Builder(
                    application,
                    SessionToken(application, ComponentName(application, NeutrodynePlaybackService::class.java)),
                ).buildAsync()
        },
    )

    private var connecting: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    suspend fun awaitController(timeoutMs: Long = CONNECTION_TIMEOUT_MS): MediaController? {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return null
        // A first UI action can beat initializer 300; never miss that process's stop.
        lifecycle.addObserver(this)
        controller?.let {
            if (it.isConnected) return it
            release()
        }
        val future = connecting ?: build().also { connecting = it }
        return try {
            // Do not let one cancelled waiter cancel a connection shared with another command.
            val connected =
                withTimeout(timeoutMs) {
                    com.google.common.util.concurrent.Futures
                        .nonCancellationPropagating(future)
                        .await()
                }
            if (connecting !== future || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                null
            } else {
                connected.also { controller = it }
            }
        } catch (_: TimeoutCancellationException) {
            if (connecting === future) release()
            null
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (connecting === future) release()
            null
        } catch (_: Exception) {
            if (connecting === future) release()
            null
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        release()
    }

    private fun release() {
        connecting?.let(MediaController::releaseFuture)
        connecting = null
        controller = null
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 5_000L
    }
}
