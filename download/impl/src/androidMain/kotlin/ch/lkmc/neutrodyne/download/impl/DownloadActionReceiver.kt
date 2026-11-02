// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class DownloadActionReceiver : BroadcastReceiver() {
    @Inject internal lateinit var runtime: Lazy<AndroidDownloadRuntime>

    @Inject internal lateinit var opener: ch.lkmc.neutrodyne.core.database.DatabaseOpener

    @Inject internal lateinit var db: Lazy<NeutrodyneDatabase>

    @Inject internal lateinit var notifications: Lazy<DownloadNotifications>

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        ((context.applicationContext as GraphHolder).graph as DownloadComponentsInjector).inject(this)
        val pending = goAsync()
        appScope
            .launch {
                try {
                    withTimeout(ACTION_TIMEOUT_MS) {
                        when (intent.action) {
                            PAUSE_ALL -> {
                                awaitDownloadDatabase(opener)
                                runtime.value.controller().pauseAll()
                            }

                            RETRY_FAILED -> {
                                awaitDownloadDatabase(opener)
                                runtime.value.controller().retry(
                                    db.value
                                        .downloadDao()
                                        .allRows()
                                        .filter { it.lane == DownloadLane.MANUAL && it.state == DownloadState.FAILED }
                                        .map { it.episodeId },
                                )
                            }

                            DISMISS_ERRORS -> {
                                notifications.value.dismissErrors()
                            }
                        }
                    }
                } catch (
                    timeout: TimeoutCancellationException,
                ) {
                    Log.e("Downloads", timeout) { "Download action timed out" }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    Log.e("Downloads", failure) { "Download notification action failed" }
                }
            }.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val PAUSE_ALL = "ch.lkmc.neutrodyne.download.PAUSE_ALL"
        const val RETRY_FAILED = "ch.lkmc.neutrodyne.download.RETRY_FAILED"
        const val DISMISS_ERRORS = "ch.lkmc.neutrodyne.download.DISMISS_ERRORS"
        private const val ACTION_TIMEOUT_MS = 8_000L
        private val ACTIONS = setOf(PAUSE_ALL, RETRY_FAILED, DISMISS_ERRORS)
    }
}
