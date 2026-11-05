// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.native.NativeSessionEventKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native event classification of [NativeSystemMediaSession]'s poll loop: every command
 * kind maps to its [RemoteCommand], every system-event kind stays a system event, and the
 * `when` is exhaustive over the enum so a kind added natively without a decision here fails
 * compilation instead of silently dropping events (the MD0 review's unknown-event finding).
 */
class NativeEventRoutingTest {
    @Test
    fun commandKindsMapToTheirRemoteCommands() {
        assertEquals(NativeEventRouting.Command(RemoteCommand.Play), routeNativeEvent(NativeSessionEventKind.PLAY))
        assertEquals(NativeEventRouting.Command(RemoteCommand.Pause), routeNativeEvent(NativeSessionEventKind.PAUSE))
        assertEquals(NativeEventRouting.Command(RemoteCommand.Toggle), routeNativeEvent(NativeSessionEventKind.TOGGLE))
        assertEquals(NativeEventRouting.Command(RemoteCommand.Next), routeNativeEvent(NativeSessionEventKind.NEXT))
        assertEquals(
            NativeEventRouting.Command(RemoteCommand.Previous),
            routeNativeEvent(NativeSessionEventKind.PREVIOUS),
        )
        assertEquals(
            NativeEventRouting.Command(RemoteCommand.SeekTo(12_345)),
            routeNativeEvent(NativeSessionEventKind.SEEK, seekValueMs = 12_345),
        )
    }

    @Test
    fun systemEventKindsStaySystemEvents() {
        for (kind in listOf(
                 NativeSessionEventKind.SUSPEND,
                 NativeSessionEventKind.RESUME,
                 NativeSessionEventKind.REMOVED,
                 NativeSessionEventKind.DEFAULT_CHANGED,
             )) {
            assertEquals(NativeEventRouting.SystemEvent, routeNativeEvent(kind), "$kind")
        }
    }

    @Test
    fun everyEnumKindHasAKnownRoute() {
        // Guards the classifier against a future kind that gains no branch: the exhaustive
        // `when` makes this set complete at compile time, and this loop proves the entries.
        for (kind in NativeSessionEventKind.entries) {
            routeNativeEvent(kind)
        }
    }
}
