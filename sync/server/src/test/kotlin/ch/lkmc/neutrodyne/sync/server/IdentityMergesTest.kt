// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityMergesTest {
    @Test
    fun podcastSurvivorAndChildFieldMergeAreIndependentOfArrivalOrder() =
        runBlocking {
            for (subscribedAt in listOf(1L, 2L)) {
                val snapshots =
                    listOf(false, true).map { reverse ->
                        val f = WriterFixture()
                        f.push(
                            f.write(episode(A), "fav", JsonPrimitive(true)),
                            f.write(episode(B), "playCount", JsonPrimitive(2)),
                            f.write(member(podcast = B), "in", JsonPrimitive(true)),
                            f.write(episode(B, coll = SyncCollection.UPNEXT), "in", JsonPrimitive(true)),
                        )
                        val podcasts =
                            listOf(
                                f.podcast(A, "HTTP://EXAMPLE.com:80/feed/", 1),
                                f.podcast(B, "https://example.com/feed", subscribedAt, f.clock(1)),
                            )
                        f.push(*(if (reverse) podcasts.reversed() else podcasts).toTypedArray())
                        assertEquals(podcastA.id, f.raw(key(SyncCollection.PODCAST, B)).mergedInto)
                        assertEquals(episode(A).id, f.raw(episode(B)).mergedInto)
                        assertTrue(
                            f
                                .raw(episode(A))
                                .fields.keys
                                .containsAll(listOf("fav", "playCount")),
                        )
                        assertEquals(member().id, f.raw(member(podcast = B)).mergedInto)
                        assertEquals(episode(A).id, f.raw(episode(B, coll = SyncCollection.UPNEXT)).mergedInto)
                        f.store.read(1) { records().associate { it.key to it.raw } }
                    }
                assertEquals(snapshots[0], snapshots[1])
            }
        }

    @Test
    fun aliasesAndFeedMovesCanTriggerTransitiveCollisions() =
        runBlocking {
            val f = WriterFixture()
            f.push(
                f.podcast(A),
                f.podcast(B, "https://two.test/", 2, f.clock(1)),
                f.podcast(C, "https://three.test/", 3, f.clock(2)),
            )
            val keys = FieldValue(add = listOf("two.test/", "three.test/"))
            val change =
                f
                    .write(podcastA, "title", JsonPrimitive("moved"), f.clock(1))
                    .copy(fields = mapOf("feedKeys" to keys))
            f.push(change)
            listOf(B, C).forEach {
                assertEquals(podcastA.id, f.raw(key(SyncCollection.PODCAST, it)).mergedInto)
            }
            f.store.read(1) {
                assertTrue(podcastOwners("three.test/").contains(podcastA))
                assertEquals(2, redirects().size)
            }
        }

    @Test
    fun groupNamesUseSharedNfcRulesSurvivorAndMembershipUnionInBothOrders() =
        runBlocking {
            val snapshots =
                listOf(false, true).map { reverse ->
                    val f = WriterFixture()
                    f.push(
                        f.write(member(G, A), "in", JsonPrimitive(true)),
                        f.write(member(H, B), "in", JsonPrimitive(true)),
                    )
                    val groups = listOf(f.group(G, "Café", 1), f.group(H, "CAFE\u0301", 2, f.clock(1)))
                    f.push(*(if (reverse) groups.reversed() else groups).toTypedArray())
                    assertEquals(groupG.id, f.raw(key(SyncCollection.GROUP, H)).mergedInto)
                    assertEquals(member(G, B).id, f.raw(member(H, B)).mergedInto)
                    assertFalse(RecordMerger.isDead(f.raw(member(G, A))))
                    assertFalse(RecordMerger.isDead(f.raw(member(G, B))))
                    f.store.read(1) {
                        assertEquals(groupG, groupOwner("café"))
                        records().associate { it.key to it.raw }
                    }
                }
            assertEquals(snapshots[0], snapshots[1])
        }

    @Test
    fun groupRenameCollisionReleasesOldNamesAndRevivalResequencesMembers() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.group(G, "News", 1), f.group(H, "Tech", 2))
            f.push(f.write(key(SyncCollection.GROUP, H), "name", JsonPrimitive("NEWS"), f.clock(1)))
            f.store.read(1) {
                assertNull(groupOwner("tech"))
                assertEquals(groupG, groupOwner("news"))
            }
            f.push(
                f.write(member(), "in", JsonPrimitive(true)),
                f.write(groupG, "deleted", JsonPrimitive(true), f.clock(2)),
            )
            val cutoff = f.record(member()).seq
            f.push(f.write(groupG, "name", JsonPrimitive("Edited"), f.clock(3)))
            assertTrue(RecordMerger.isDead(f.raw(groupG)))
            f.push(f.write(groupG, "deleted", JsonPrimitive(false), f.clock(4)))
            assertTrue(f.record(member()).seq > cutoff)
            f.store.read(1) { assertEquals(groupG, groupOwner("edited")) }
        }

    @Test
    fun lateUnseenChildrenRemapBothParentHalvesIncludingAfterParentGc() =
        runBlocking {
            val f = WriterFixture()
            f.push(
                f.podcast(A),
                f.podcast(B, subscribedAt = 2, at = f.clock(1)),
                f.group(G),
                f.group(H, createdAt = 2, at = f.clock(1)),
            )
            f.store.write(1) {
                remove(key(SyncCollection.PODCAST, B))
                remove(key(SyncCollection.GROUP, H))
            }
            val old = episode(B, "g:late")
            val new = episode(A, "g:late")
            val memberOld = member(H, B)
            val result = f.push(f.write(old, "fav", JsonPrimitive(true)), f.write(memberOld, "in", JsonPrimitive(true)))
            assertEquals(listOf("merged", "merged"), result.results.map { it.status })
            assertEquals(new.id.toWire(), result.results[0].mergedInto)
            assertEquals(member().id.toWire(), result.results[1].mergedInto)
            assertEquals(new.id, f.raw(old).mergedInto)
            assertEquals(member().id, f.raw(memberOld).mergedInto)
            assertTrue(
                f
                    .raw(new)
                    .fields
                    .containsKey("fav"),
            )
            assertTrue(
                f
                    .raw(member())
                    .fields
                    .containsKey("in"),
            )
        }

    @Test
    fun pairedRekeyMergesExistingTargetsAndRetriesWithoutChurn() =
        runBlocking {
            val f = WriterFixture()
            val old = episode()
            val new = episode(episode = "g:new")
            val queueOld = old.copy(collection = SyncCollection.UPNEXT)
            val queueNew = new.copy(collection = SyncCollection.UPNEXT)
            f.push(
                f.write(old, "fav", JsonPrimitive(true)),
                f.write(new, "playCount", JsonPrimitive(2)),
                f.write(queueOld, "in", JsonPrimitive(true)),
                f.write(queueNew, "ok", JsonPrimitive("a0")),
            )
            val change = ChangeDto("episode", old.id.toWire(), op = "rekey", to = new.id.toWire())
            val result = f.push(change)
            assertEquals("merged", result.results.single().status)
            assertEquals(new.id, f.raw(old).mergedInto)
            assertEquals(new.id, f.raw(queueOld).mergedInto)
            assertEquals(
                setOf("in", "ok"),
                f.raw(queueNew).fields.keys,
            )
            assertEquals(
                setOf("fav", "playCount"),
                f.raw(new).fields.keys,
            )
            val cursor = f.cursor()
            f.push(change)
            assertEquals(cursor, f.cursor())
            f.push(f.write(old, "fav", JsonPrimitive(false), f.clock(1)))
            assertEquals(
                JsonPrimitive(false),
                f
                    .raw(new)
                    .fields["fav"]!!
                    .v,
            )
        }

    @Test
    fun conflictingRekeyCannotReassignAnAuthoritativeIdentity() =
        runBlocking {
            val f = WriterFixture()
            val old = episode()
            val target = episode(episode = "g:one")
            val other = episode(episode = "g:two")

            fun rekey(to: RecordKey) = ChangeDto("episode", old.id.toWire(), op = "rekey", to = to.id.toWire())
            f.push(f.write(old, "fav", JsonPrimitive(true)), rekey(target))
            val cursor = f.cursor()
            assertEquals(
                "invalid",
                f
                    .push(rekey(other))
                    .results
                    .single()
                    .code,
            )
            assertEquals(cursor, f.cursor())
            assertEquals(target.id, f.raw(old).mergedInto)
            f.store.write(1) { remove(old) }
            assertEquals(
                "invalid",
                f
                    .push(rekey(other))
                    .results
                    .single()
                    .code,
            )
        }

    @Test
    fun childConflictDuringDedupeRollsBackParentChildrenAndIndexesOnlyForThatChange() =
        runBlocking {
            val f = WriterFixture()
            f.push(
                f.podcast(A),
                f.write(episode(A), "fav", JsonPrimitive(true)),
                f.write(episode(B), "fav", JsonPrimitive(false)),
            )
            val before = f.store.read(1) { records().associate { it.key to it } }
            val result =
                f.push(
                    f.podcast(B, subscribedAt = 2, at = f.clock(1)),
                    f.write(setting, "v", JsonPrimitive(1)),
                )
            assertEquals(listOf("rejected", "applied"), result.results.map { it.status })
            f.store.read(1) {
                before.forEach { (key, value) -> assertEquals(value, record(key)) }
                assertNull(record(key(SyncCollection.PODCAST, B)))
                assertTrue(redirects().isEmpty())
                assertEquals(setOf(podcastA), podcastOwners("example.com/feed"))
            }
        }

    @Test
    fun redirectHopBoundaryAndCyclesAreDiagnosedWithoutMutation() =
        runBlocking {
            val f = WriterFixture()
            val chain = (0..ProtocolLimits.MAX_REDIRECT_HOPS + 1).map { episode(episode = "g:$it") }
            f.store.write(1) {
                chain.zipWithNext().forEach { (a, b) -> assignRedirect(a, b) }
            }
            val result =
                f.push(
                    f.write(chain[0], "fav", JsonPrimitive(true)),
                    f.write(chain[1], "fav", JsonPrimitive(true)),
                )
            assertEquals(listOf("rejected", "merged"), result.results.map { it.status })
            assertEquals(
                JsonPrimitive(true),
                f
                    .raw(chain.last())
                    .fields["fav"]!!
                    .v,
            )
            val cycleA = episode(episode = "g:cycleA")
            val cycleB = episode(episode = "g:cycleB")
            f.store.write(1) {
                assignRedirect(cycleA, cycleB)
                assignRedirect(cycleB, cycleA)
            }
            val cursor = f.cursor()
            assertEquals(
                "invalid",
                f
                    .push(f.write(cycleA, "fav", JsonPrimitive(true)))
                    .results
                    .single()
                    .code,
            )
            assertEquals(cursor, f.cursor())
        }

    @Test
    fun expiredDeadOwnerDoesNotReviveAndDeathTimeIsNotExtendedByEdits() =
        runBlocking {
            val f = WriterFixture()
            f.push(f.podcast(), f.write(podcastA, "subscribed", JsonPrimitive(false), f.clock(1)))
            val death = f.record(podcastA).deadAtMs
            f.now += 180L * DAY
            f.push(f.write(podcastA, "title", JsonPrimitive("edited")))
            assertEquals(death, f.record(podcastA).deadAtMs)
            f.push(f.podcast(B, subscribedAt = 2))
            assertNull(f.raw(key(SyncCollection.PODCAST, B)).mergedInto)
            assertTrue(RecordMerger.isDead(f.raw(podcastA)))
        }

    @Test
    fun directNewerSubscribeRestoresHistoryButNeverQueue() =
        runBlocking {
            val f = WriterFixture()
            val queue = episode(coll = SyncCollection.UPNEXT)
            f.push(
                f.podcast(),
                f.write(episode(), "fav", JsonPrimitive(true)),
                f.write(member(), "in", JsonPrimitive(true)),
                f.write(queue, "in", JsonPrimitive(true)),
            )
            f.push(f.write(podcastA, "subscribed", JsonPrimitive(false), f.clock(1)))
            val deathCursor = f.cursor()
            val deadQueue = f.record(queue)
            assertTrue(RecordMerger.isDead(deadQueue.raw))
            f.push(f.write(podcastA, "title", JsonPrimitive("edit"), f.clock(2)))
            assertTrue(RecordMerger.isDead(f.raw(podcastA)))
            val result = f.push(f.write(podcastA, "subscribed", JsonPrimitive(true), f.clock(3)), since = deathCursor)
            assertTrue(
                result.page.records
                    .map { it.key }
                    .containsAll(listOf(episode(), member())),
            )
            assertEquals(deadQueue, f.record(queue))
        }

    private companion object {
        const val DAY = 86_400_000L
    }
}
