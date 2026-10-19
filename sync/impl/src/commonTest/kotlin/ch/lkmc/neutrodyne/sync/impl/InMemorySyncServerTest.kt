// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.core.testing.sync.canonicalSyncSnapshot
import ch.lkmc.neutrodyne.sync.protocol.EpisodeKey
import ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.GroupId
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.PodcastId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InMemorySyncServerTest {
    @Test
    fun cursorExpiresAndOriginalClockReplayRestoresLostTombstoneAndExtrema() {
        val server = server()
        val p = podcast(1)
        subscribe(server, p, 1, 10)
        val backup = server.snapshot()
        val cursor = server.page(null, 1).cursor
        push(server, p, 2, "subscribed" to JsonPrimitive(false))
        val tombstone = server.snapshot().getValue(p)
        server.expire(backup)
        assertEquals(SyncErrorCode.CURSOR_EXPIRED, assertFailsWith<ProtocolException> { server.page(cursor, 1) }.code)
        server.push(p, tombstone)
        assertEquals(tombstone, server.snapshot().getValue(p))
        val e = episode()
        push(server, e, 2, "playCount" to JsonPrimitive(9), "lastPlayedAt" to JsonPrimitive(NOW))
        push(server, e, 7, "playCount" to JsonPrimitive(2), "lastPlayedAt" to JsonNull)
        val raw = server.snapshot().getValue(e)
        assertEquals(JsonPrimitive(9), raw.fields.getValue("playCount").v)
        assertEquals(clock(7), raw.fields.getValue("playCount").c)
        server.expire(emptyMap())
        server.push(e, raw)
        assertEquals(raw, server.snapshot().getValue(e))
    }

    @Test
    fun lateChildWriteFollowsRekeyAtAnIntermediateParent() {
        val server = server()
        subscribe(server, podcast(1), 1, 30)
        subscribe(server, podcast(2), 2, 20)
        val middle = episode(2).id as RecordId.Episode
        val stable = middle.copy(key = EpisodeKey("g:stable"))
        server.rekey(middle, stable)
        subscribe(server, podcast(3), 3, 10)
        push(server, episode(1), 4, "fav" to JsonPrimitive(true))
        val target = SyncAddress(SyncCollection.EPISODE, stable.copy(podcast = PodcastId(uuid(3))))
        val raw = server.snapshot()
        val snapshot = canonicalSyncSnapshot(raw, raw.keys + episode(1))
        assertTrue(snapshot.episodes.getValue(target).favourite)
        assertEquals(target, snapshot.redirects[episode(1)])
    }

    @Test
    fun identityRollbackCanViolateTheSingleAuthorityDomain() {
        val server = server()
        val a = podcast(1)
        subscribe(server, a, 1, 30)
        subscribe(server, podcast(2), 2, 20)
        val assigned = server.snapshot().getValue(a)
        server.expire(emptyMap())
        subscribe(server, a, 1, 30)
        subscribe(server, podcast(3), 3, 10)
        val reassigned = server.snapshot().getValue(a)
        assertNotEquals(assigned.mergedInto, reassigned.mergedInto)
        assertEquals(
            SyncErrorCode.INVALID,
            assertFailsWith<ProtocolException> { RecordMerger.merge(assigned, reassigned) }.code,
        )
    }

    private fun server() = InMemorySyncServer { NOW }

    private fun clock(counter: Long) = Hlc.of(NOW, counter, NodeId("0000000000000001"))

    private fun uuid(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun podcast(index: Int) = SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(PodcastId(uuid(index))))

    private fun episode(index: Int = 1) =
        SyncAddress(SyncCollection.EPISODE, RecordId.Episode(PodcastId(uuid(index)), EpisodeKey("g:legacy")))

    private fun subscribe(
        server: InMemorySyncServer,
        address: SyncAddress,
        counter: Long,
        at: Long,
    ) {
        push(
            server,
            address,
            counter,
            "subscribed" to JsonPrimitive(true),
            "subscribedAt" to JsonPrimitive(at),
            "feedUrl" to JsonPrimitive("https://feeds.test/feed"),
        )
    }

    private fun push(
        server: InMemorySyncServer,
        address: SyncAddress,
        counter: Long,
        vararg fields: Pair<String, JsonElement>,
    ) {
        server.push(
            address,
            RecordState(
                address.coll,
                fields.associate { (name, value) ->
                    name to
                        FieldState(value, clock(counter))
                },
            ),
        )
    }

    private companion object {
        const val NOW = 1_791_072_000_000L
    }
}
