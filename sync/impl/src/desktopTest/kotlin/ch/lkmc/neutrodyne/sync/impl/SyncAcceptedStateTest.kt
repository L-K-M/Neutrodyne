// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SyncAcceptedStateTest {
    private val node = NodeId("a000000000000001")
    private val old = Hlc.of(1_800_000_000_000L, 0, node)
    private val future = Hlc.of(old.millis + 21_600_000L, 0, node)

    @Test
    fun anOptimisticOverlayDoesNotBecomeConfirmedEvenOnItsOwnNode() {
        val received = RecordState(SyncCollection.GROUP, mapOf("name" to FieldState(JsonPrimitive("Remote"), old)))
        val optimistic = received.copy(fields = mapOf("name" to FieldState(JsonPrimitive("Pending"), future)))
        val raw = RawSyncRecord(optimistic).withConfirmed(null.confirm(received))
        assertEquals(old, raw.confirmed().state.maxClock)
        assertEquals(future, raw.state.maxClock)
        val echo = optimistic.copy()
        val confirmed = raw.copy(state = echo).withConfirmed(raw.confirm(echo))
        assertNull(confirmed.accepted, "an equal echo confirms the overlay without retaining duplicate state")
        assertEquals(future, confirmed.confirmed().state.maxClock)
    }

    @Test
    fun acceptedAuthClocksSurviveWithoutPersistingSecretBytes() {
        val incoming =
            RecordState(
                SyncCollection.PODCAST,
                mapOf(
                    "auth" to
                        FieldState(
                            JsonObject(
                                mapOf(
                                    "u" to JsonPrimitive("private-user"),
                                    "p" to JsonPrimitive("private-secret"),
                                ),
                            ),
                            future,
                        ),
                ),
            )
        val raw = RawSyncRecord(RecordState(SyncCollection.PODCAST)).withConfirmed(null.confirm(incoming))
        val encoded = SyncJson.encodeToString(raw)
        assertFalse("private-user" in encoded)
        assertFalse("private-secret" in encoded)
        assertEquals(future.packed, raw.confirmed().maxClockPacked)
        assertEquals(emptyMap(), raw.confirmed().state.fields)
    }

    @Test
    fun legacyRecordsKeepTheirConfirmedClockConservatively() {
        val raw =
            RawSyncRecord(
                RecordState(
                    SyncCollection.GROUP,
                    mapOf("name" to FieldState(JsonPrimitive("Known"), future)),
                ),
            )
        assertEquals(future.packed, raw.confirmed().maxClockPacked)
    }

    @Test
    fun corruptAcceptedMetadataCannotLowerTheConfirmedFrontier() {
        val state = RecordState(SyncCollection.GROUP, mapOf("name" to FieldState(JsonPrimitive("Known"), future)))
        assertFailsWith<IOException> { RawSyncRecord(state, accepted = AcceptedSyncRecord(state, 0L)).confirmed() }
    }
}
