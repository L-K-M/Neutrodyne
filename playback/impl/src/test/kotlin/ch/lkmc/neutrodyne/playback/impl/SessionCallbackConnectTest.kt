// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SessionCallbackConnectTest {
    private fun callback(
        h: LifecycleHarness,
        facts: SigningFacts,
    ): SessionCallback =
        SessionCallback(
            { error("Connection must not resolve database ports") },
            ResumptionProvider {
                error("Connection must not resume")
            },
            h.tree,
            MediaButtons(h.prefs),
            h.scope,
            Dispatchers.IO,
            ControllerHostIdentity(h.app.packageManager, facts),
        )

    private fun notificationController(h: LifecycleHarness): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            h.app.packageName,
            1,
            HOST_UID,
            1,
            1,
            false,
            Bundle().apply {
                putBoolean(androidx.media3.session.MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true)
            },
            true,
        )

    private fun assertFull(
        result: MediaSession.ConnectionResult,
        controller: MediaSession.ControllerInfo,
    ) {
        assertTrue(result.isAccepted, controller.packageName)
        assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE), controller.packageName)
        assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT), controller.packageName)
        assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS), controller.packageName)
        assertTrue(
            result.availableSessionCommands.contains(CustomCommands.command(CustomCommands.PLAY_CONTEXT)),
            controller.packageName,
        )
    }

    private fun assertDenied(
        callback: SessionCallback,
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) {
        val result = callback.onConnectAsync(session, controller).get()
        assertFalse(result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE), controller.packageName)
        assertFalse(
            result.availableSessionCommands.contains(CustomCommands.command(CustomCommands.PLAY_CONTEXT)),
            controller.packageName,
        )
        assertEquals(
            SessionError.ERROR_PERMISSION_DENIED,
            callback
                .onCustomCommand(
                    session,
                    controller,
                    CustomCommands.command(CustomCommands.DISMISS),
                    Bundle.EMPTY,
                ).get()
                .resultCode,
        )
    }

    @Test fun trustedAndGenuineAutoHostsHaveAllCommands() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            bindPackagesToUid(h.app, AUTO_PACKAGE, CAR_MEDIA_PACKAGE, CAR_LAUNCHER_PACKAGE)
            installHostPackage(h.app, AUTO_PACKAGE)
            installHostPackage(h.app, CAR_MEDIA_PACKAGE, ApplicationInfo.FLAG_SYSTEM)
            installHostPackage(h.app, CAR_LAUNCHER_PACKAGE, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
            val callback = callback(h, FakeSigningFacts(lineage = setOf(PIN_RELEASE)))
            val exo = h.playerFactory.create()
            val session = MediaLibrarySession.Builder(h.app, exo, callback).build()
            try {
                val controllers =
                    listOf(
                        notificationController(h),
                        controllerInfo(),
                        namedController(AUTO_PACKAGE),
                        namedController(CAR_MEDIA_PACKAGE),
                        namedController(CAR_LAUNCHER_PACKAGE),
                    )
                for (controller in controllers) {
                    assertFull(callback.onConnectAsync(session, controller).get(), controller)
                }
                // The guarded nd.* path must actually reach the bridge for a genuine host;
                // the dispatch attempt fails at the database-free test bridge, not the gate.
                val dispatch =
                    assertFailsWith<ExecutionException> {
                        callback
                            .onCustomCommand(
                                session,
                                namedController(AUTO_PACKAGE),
                                CustomCommands.command(CustomCommands.DISMISS),
                                Bundle.EMPTY,
                            ).get()
                    }
                assertIs<IllegalStateException>(dispatch.cause)
                assertEquals(0, h.dbProviderReads)
            } finally {
                session.release()
                exo.release()
            }
        }
    }

    @Test fun unauthenticatedMatchingNamesRemainReadOnly() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            // Installed and uid-bound, but none carries a host credential: an ordinary
            // package may claim any of the recognized names and stays read-only.
            bindPackagesToUid(h.app, AUTO_PACKAGE, CAR_MEDIA_PACKAGE, CAR_LAUNCHER_PACKAGE)
            installHostPackage(h.app, AUTO_PACKAGE)
            installHostPackage(h.app, CAR_MEDIA_PACKAGE)
            installHostPackage(h.app, CAR_LAUNCHER_PACKAGE)
            val callback = callback(h, FakeSigningFacts())
            val exo = h.playerFactory.create()
            val session = MediaLibrarySession.Builder(h.app, exo, callback).build()
            try {
                for (spoofed in listOf(
                    namedController(AUTO_PACKAGE),
                    namedController(CAR_MEDIA_PACKAGE),
                    namedController(CAR_LAUNCHER_PACKAGE),
                    namedController("untrusted.app"),
                )) {
                    assertDenied(callback, session, spoofed)
                }
                // The standard-command guard rejects the same way, before media-id parsing.
                val items = mutableListOf(MediaItem.Builder().setMediaId("episode:1").build())
                val denied =
                    assertFailsWith<ExecutionException> {
                        callback
                            .onSetMediaItems(
                                session,
                                namedController(AUTO_PACKAGE),
                                items,
                                0,
                                C.TIME_UNSET,
                            ).get()
                    }
                assertIs<IllegalArgumentException>(denied.cause)
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
