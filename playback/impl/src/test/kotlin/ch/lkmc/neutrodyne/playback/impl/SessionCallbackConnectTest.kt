// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands
import dev.zacsweers.metro.Provider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SessionCallbackConnectTest {
    @Test fun trustedAndAutoHaveAllCommandsWhileUntrustedRemainsReadOnly() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            val exo = h.playerFactory.create()
            val callback =
                SessionCallback(
                    { error("Connection must not resolve database ports") },
                    ResumptionProvider {
                        error("Connection must not resume")
                    },
                    h.tree,
                    MediaButtons(h.prefs),
                    h.scope,
                    kotlinx.coroutines.Dispatchers.IO,
                )
            val session = MediaLibrarySession.Builder(h.app, exo, callback).build()
            try {
                val notificationHints =
                    android.os.Bundle().apply {
                        putBoolean(androidx.media3.session.MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true)
                    }
                val notification =
                    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                        h.app.packageName,
                        1,
                        12345,
                        1,
                        1,
                        false,
                        notificationHints,
                        true,
                    )
                for (controller in listOf(
                    notification,
                    controllerInfo(),
                    controllerInfo("com.google.android.projection.gearhead", false),
                    controllerInfo("com.android.car.media", false),
                )) {
                    val result = callback.onConnectAsync(session, controller).get()
                    assertTrue(result.isAccepted)
                    assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                    assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
                    assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
                    assertTrue(
                        result.availableSessionCommands.contains(CustomCommands.command(CustomCommands.PLAY_CONTEXT)),
                    )
                    assertEquals(CustomCommands.names.size, CustomCommands.names.distinct().size)
                    for (name in CustomCommands.names) {
                        assertTrue(result.availableSessionCommands.contains(CustomCommands.command(name)))
                    }

                    assertTrue(
                        result.availableSessionCommands.contains(
                            CustomCommands.command(PlaybackSessionCommands.CHAPTER_NEXT),
                        ),
                    )
                    assertTrue(
                        result.availableSessionCommands.contains(
                            CustomCommands.command(PlaybackSessionCommands.CHAPTER_PREV),
                        ),
                    )
                }
                val unknown = callback.onConnectAsync(session, controllerInfo("untrusted.app", false)).get()
                assertFalse(unknown.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                assertFalse(
                    unknown.availableSessionCommands.contains(CustomCommands.command(CustomCommands.PLAY_CONTEXT)),
                )
                assertFalse(
                    unknown.availableSessionCommands.contains(
                        CustomCommands.command(PlaybackSessionCommands.CHAPTER_NEXT),
                    ),
                )
                assertEquals(0, h.dbProviderReads)
            } finally {
                session.release()
                exo.release()
            }
        }
    }

    @Test fun notificationPreferencesKeepSeekSlotsAndEpisodeNext() {
        val buttons = MediaButtons(PlaybackPrefs(Provider { FakeSettingsRepository() })).current(1.5f)
        assertEquals(Player.COMMAND_SEEK_BACK, buttons[0].playerCommand)
        assertEquals(Player.COMMAND_SEEK_FORWARD, buttons[1].playerCommand)
        assertEquals(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, buttons[3].playerCommand)
    }
}
