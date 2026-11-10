// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Source-dependent order fields must not strand the outbox after a later removal. */
class RemovedSyncSourcesTest {
    @Test
    fun aMembershipAddedThenRemovedBeforeSyncDrainsCompletely() =
        runTest {
            val db = fixture()
            try {
                db.withWriteTransaction {
                    db.groupDao().insertMember(memberEntity(groupId = 1, podcastId = 1, orderKey = "b", addedAt = 42))
                    db.groupDao().deleteMembers(1, listOf(1))
                }
                assertFalse(db.syncOutboxDao().rows().isEmpty())
                val device = device(db)

                device.engine.round(SyncRoundReason.SYNC_NOW)

                assertNull(db.groupDao().member(1, 1))
                assertEquals(emptyList(), db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun aQueueEntryAddedThenRemovedBeforeSyncDrainsCompletely() =
        runTest {
            val db = fixture()
            try {
                db.withWriteTransaction {
                    db.queueDao().insert(queueEntryEntity(episodeId = 1, orderKey = "b"))
                    db.queueDao().removeEpisodes(listOf(1))
                }
                assertFalse(db.syncOutboxDao().rows().isEmpty())
                val device = device(db)

                device.engine.round(SyncRoundReason.SYNC_NOW)

                assertNull(db.queueDao().entryByEpisode(1))
                assertEquals(emptyList(), db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnsyncedMembershipDoesNotSurviveItsGroupDeletion() =
        runTest {
            val db = fixture()
            try {
                db.withWriteTransaction {
                    db.groupDao().insertMember(memberEntity(groupId = 1, podcastId = 1, orderKey = "b", addedAt = 42))
                    db.groupDao().setGroupOrderKey(1, "b", System.currentTimeMillis())
                    db.groupDao().deleteGroup(1)
                }

                device(db).round()

                assertNull(db.groupDao().groupById(1))
                assertEquals(emptyList(), db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnconfirmedRemovalCannotDiscardCompanions() =
        runTest {
            val db = fixture()
            try {
                removeQueuedEntry(db)
                val before = db.syncOutboxDao().rows()

                val retained = device(db).effects.discardRemovedSourceFields(before, db.syncStateDao().get())

                assertEquals(before, retained)
                assertEquals(before, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun aLaterPendingClockIsPreserved() =
        runTest {
            val db = fixture()
            try {
                val device = acknowledgeQueueRemoval(db)
                val row = db.syncOutboxDao().rows().single()
                val removal =
                    SyncClockStore(db)
                        .get(row.coll, row.rid)!!
                        .confirmed()
                        .state.fields
                        .getValue("in")
                val stamp = removal.c!!.packed
                for (newer in listOf(row.copy(hlc = stamp + 1), row.copy(hlc = stamp, nodeId = "ffffffffffffffff"))) {
                    db.syncOutboxDao().upsert(newer)

                    val retained = device.effects.discardRemovedSourceFields(listOf(newer), db.syncStateDao().get())

                    assertEquals(listOf(newer), retained)
                    assertEquals(listOf(newer), db.syncOutboxDao().rows())
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun aRestoredSourceIsPreservedEvenWithAnOlderPendingClock() =
        runTest {
            val db = fixture()
            try {
                val device = acknowledgeQueueRemoval(db)
                val before = db.syncOutboxDao().rows()
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        db.queueDao().insert(queueEntryEntity(episodeId = 1, orderKey = "c"))
                    }
                }

                val retained = device.effects.discardRemovedSourceFields(before, db.syncStateDao().get())

                assertEquals(before, retained)
                assertEquals(before, db.syncOutboxDao().rows())
                assertEquals("c", db.queueDao().entryByEpisode(1)!!.orderKey)
            } finally {
                db.close()
            }
        }

    @Test
    fun replayRowsAndFrozenLiteralsAreNotDiscarded() =
        runTest {
            val db = fixture()
            try {
                val device = acknowledgeQueueRemoval(db)
                val row = db.syncOutboxDao().rows().single()
                for (preserved in listOf(row.copy(captureKind = SyncCaptureKind.REPLAY), row.copy(value = "\"b\""))) {
                    db.syncOutboxDao().upsert(preserved)

                    val retained = device.effects.discardRemovedSourceFields(listOf(preserved), db.syncStateDao().get())

                    assertEquals(listOf(preserved), retained)
                    assertEquals(listOf(preserved), db.syncOutboxDao().rows())
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun aChangedRowAtTheSameClockIsNotDiscarded() =
        runTest {
            val db = fixture()
            try {
                val device = acknowledgeQueueRemoval(db)
                val stale = db.syncOutboxDao().rows().single()
                val changed = stale.copy(value = "\"changed\"")
                db.syncOutboxDao().upsert(changed)

                device.effects.discardRemovedSourceFields(listOf(stale), db.syncStateDao().get())

                assertEquals(listOf(changed), db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun alreadyAcknowledgedOrphansDrainAfterRestart() =
        runTest {
            val db = fixture()
            try {
                acknowledgeQueueRemoval(db)
                assertTrue(db.syncOutboxDao().rows().isNotEmpty())

                device(db).round()

                assertEquals(emptyList(), db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun anAliasCannotProveItsCanonicalSourceWasRemoved() =
        runTest {
            val db = fixture()
            try {
                val device = acknowledgeQueueRemoval(db)
                val row = db.syncOutboxDao().rows().single()
                val clocks = SyncClockStore(db)
                val raw = clocks.get(row.coll, row.rid)!!
                clocks.put(row.coll, row.rid, raw.copy(alias = "${PODCAST}g:canonical"))

                val retained = device.effects.discardRemovedSourceFields(listOf(row), db.syncStateDao().get())

                assertEquals(listOf(row), retained)
                assertEquals(listOf(row), db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun aChangedProfileCannotDiscardRows() =
        runTest {
            val db = fixture()
            try {
                val device = acknowledgeQueueRemoval(db)
                val before = db.syncOutboxDao().rows()
                val profile = db.syncStateDao().get()!!
                db.syncStateDao().upsert(profile.copy(accountId = "other-account"))

                assertFailsWith<IllegalStateException> {
                    device.effects.discardRemovedSourceFields(before, profile)
                }

                assertEquals(before, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    private suspend fun removeQueuedEntry(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.queueDao().insert(queueEntryEntity(episodeId = 1, orderKey = "b"))
            db.queueDao().removeEpisodes(listOf(1))
        }
    }

    private suspend fun acknowledgeQueueRemoval(db: NeutrodyneDatabase): SyncEngineTest.Device {
        removeQueuedEntry(db)
        val device = device(db)
        val sent = ChangeBuilder(db, device.secrets) { false }.build(db.syncOutboxDao().rows())
        device.effects.acknowledge(sent, sent.mapIndexed { index, _ -> ChangeResultDto(index, "applied") })
        assertEquals(
            setOf("ok"),
            db
                .syncOutboxDao()
                .rows()
                .map { it.field }
                .toSet(),
        )
        return device
    }

    private fun device(db: NeutrodyneDatabase) =
        SyncEngineTest.Device(db, InMemorySyncClient(InMemorySyncServer { System.currentTimeMillis() }))

    private suspend fun fixture(): NeutrodyneDatabase {
        val db = TestDb.inMemory()
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.podcastDao().insertPodcast(podcastEntity(id = 1, syncId = PODCAST))
                db.ingestDao().insertEpisodes(listOf(episodeEntity(id = 1, podcastId = 1, identityKey = "g:one")))
                db.groupDao().insert(groupEntity(uuid = GROUP, name = "Group", orderKey = "a"))
            }
            db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001", hlc = 1L shl 16))
        }
        return db
    }

    private companion object {
        const val PODCAST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
