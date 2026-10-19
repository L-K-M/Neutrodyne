// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import kotlinx.coroutines.guava.await
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands as Wire

@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class SleepTimerCommandsTest {
    @Test fun strictBundlesAndTrustPolicyPreserveTimerOnRejectionAndTeardownClearsIt() {
        val app = RuntimeEnvironment.getApplication() as PlaybackTestApplication
        LifecycleHarness(app).use { h ->
            app.graph =
                object : PlaybackServiceInjector {
                    override fun inject(service: NeutrodynePlaybackService) {
                        service.access = h.access
                    }
                }
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            try {
                val session = service.get().onGetSession(controllerInfo()) as MediaLibrarySession
                val client = h.run { MediaController.Builder(app, session.token).buildAsync().await() }
                try {
                    h.run {
                        assertEquals(
                            SessionResult.RESULT_SUCCESS,
                            client
                                .sendCustomCommand(
                                    CustomCommands.command(Wire.SLEEP_SET),
                                    SleepTimerCommands.encode(SleepTimerMode.Minutes(1)),
                                ).await()
                                .resultCode,
                        )
                    }
                } finally {
                    client.release()
                }
                val chapterActions = mutableListOf<String>()
                val callback =
                    SessionCallback(
                        { checkNotNull(h.hub.bridge) },
                        ResumptionProvider { checkNotNull(h.hub.bridge) },
                        h.tree,
                        MediaButtons(h.prefs),
                        h.scope,
                        kotlinx.coroutines.Dispatchers.IO,
                        chapterCommand = { action ->
                            if (action == Wire.CHAPTER_NEXT || action == Wire.CHAPTER_PREV) {
                                chapterActions += action
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            } else {
                                null
                            }
                        },
                    )

                fun command(
                    name: String,
                    args: Bundle,
                    trusted: Boolean = true,
                ): Int =
                    h.run {
                        callback
                            .onCustomCommand(
                                session,
                                controllerInfo(trusted = trusted),
                                CustomCommands.command(name),
                                args,
                            ).await()
                            .resultCode
                    }
                assertEquals(
                    SessionResult.RESULT_SUCCESS,
                    command(Wire.SLEEP_SET, SleepTimerCommands.encode(SleepTimerMode.Minutes(1))),
                )
                assertEquals(SessionResult.RESULT_SUCCESS, command(Wire.CHAPTER_NEXT, Bundle.EMPTY))
                assertEquals(SessionResult.RESULT_SUCCESS, command(Wire.CHAPTER_PREV, Bundle.EMPTY))
                assertEquals(listOf(Wire.CHAPTER_NEXT, Wire.CHAPTER_PREV), chapterActions)
                assertEquals(SessionError.ERROR_NOT_SUPPORTED, command("nd.UNKNOWN", Bundle.EMPTY))
                val before = assertIs<SleepTimerState.Running>(h.hub.sleepTimer.value)
                val invalid =
                    listOf(
                        Bundle(),
                        Bundle().apply { putString(Wire.MODE, "unknown") },
                        Bundle().apply { putInt(Wire.MODE, 3) },
                        Bundle().apply {
                            putString(Wire.MODE, Wire.MODE_MINUTES)
                            putString(Wire.MINUTES, "1")
                        },
                        Bundle().apply {
                            putString(Wire.MODE, Wire.MODE_MINUTES)
                            putLong(Wire.MINUTES, 1)
                        },
                        SleepTimerCommands.encode(SleepTimerMode.Minutes(Int.MAX_VALUE)),
                        SleepTimerCommands.encode(SleepTimerMode.Minutes(0)),
                    )
                for (args in invalid) {
                    assertEquals(SessionError.ERROR_BAD_VALUE, command(Wire.SLEEP_SET, args))
                    assertEquals(before, h.hub.sleepTimer.value)
                }
                assertEquals(
                    SessionError.ERROR_BAD_VALUE,
                    command(Wire.SLEEP_EXTEND, Bundle().apply { putInt(Wire.MINUTES, 241) }),
                )
                assertEquals(
                    SessionError.ERROR_PERMISSION_DENIED,
                    command(Wire.SLEEP_SET, SleepTimerCommands.encode(SleepTimerMode.Off), false),
                )
                assertEquals(before, h.hub.sleepTimer.value)
                assertEquals(
                    SessionResult.RESULT_SUCCESS,
                    command(Wire.SLEEP_EXTEND, Bundle().apply { putInt(Wire.MINUTES, 5) }),
                )
                assertEquals(360_000, assertIs<SleepTimerState.Running>(h.hub.sleepTimer.value).remainingMs)
            } finally {
                service.destroy()
            }
            assertEquals(SleepTimerState.Off, h.hub.sleepTimer.value)
        }
    }
}
