// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 10 Push: `*` expansion, frozen literals, reset promotion, grow sets and rekeys. */
class ChangeBuilderTest {
    private val node = "0000000000000001"
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val groupUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val episodeRid = "${podcastSyncId}g:one"

    @Test
    fun starExpandsToEveryPodcastField() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.syncOutboxDao().upsert(row("podcast", podcastSyncId, "*", null, 1L))
                val change = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows()).single()

                assertEquals("podcast", change.coll)
                assertEquals(podcastSyncId, (change.id as kotlinx.serialization.json.JsonPrimitive).content)
                assertEquals(
                    setOf(
                        "feedUrl",
                        "subscribed",
                        "subscribedAt",
                        "sourceType",
                        "title",
                        "includeInAll",
                        "youtubeVariants",
                        "needsCredentials",
                        "feedKeys",
                    ),
                    change.fields.keys,
                )
                assertEquals(
                    "https://example.com/feed",
                    change.fields
                        .getValue("feedUrl")
                        .v
                        .content(),
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun playedLiteralExpandsToOneClockAndResetIsAReset() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.syncOutboxDao().upsert(row("episode", episodeRid, "played", "{\"played\":true,\"playedAt\":42}", 7L))
                db.syncOutboxDao().upsert(row("episode", episodeRid, "pos", "{\"ms\":0,\"reset\":true}", 8L))
                val change = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows()).single()

                assertEquals(
                    true,
                    change.fields
                        .getValue("played")
                        .v
                        .boolean(),
                )
                assertEquals(
                    42L,
                    change.fields
                        .getValue("playedAt")
                        .v
                        .long(),
                )
                assertEquals(change.fields.getValue("played").c, change.fields.getValue("playedAt").c)
                assertEquals(true, change.fields.getValue("pos").reset)
                assertEquals(
                    0L,
                    change.fields
                        .getValue("pos")
                        .v
                        .jsonObject()["ms"]!!
                        .long(),
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun feedKeysSendsTheWholeSet() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.podcastDao().insertAlias(
                    ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity(
                        url = "https://old.example.com/feed",
                        podcastId = 1,
                        reason = ch.lkmc.neutrodyne.core.model.AliasReason.SYNC,
                        addedAt = 1,
                    ),
                )
                db.syncOutboxDao().upsert(row("podcast", podcastSyncId, "feedKeys", null, 1L))
                val change = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows()).single()

                assertEquals(
                    listOf("https://example.com/feed", "https://old.example.com/feed"),
                    change.fields.getValue("feedKeys").add,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun rekeyBecomesARekeyChange() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.syncOutboxDao().upsert(
                    row("episode", episodeRid, "~rekey", "{\"to\":{\"p\":\"$podcastSyncId\",\"k\":\"g:two\"}}", 1L),
                )
                val change = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows()).single()

                assertEquals("rekey", change.op)
                assertEquals("g:two", change.to.jsonObject()["k"]!!.content())
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnparseableRekeyDoesNotSinkTheRecordsOtherFields() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.syncOutboxDao().upsert(row("episode", episodeRid, "~rekey", "{\"to\":garbage", 1L))
                db.syncOutboxDao().upsert(row("episode", episodeRid, "fav", "true", 2L))
                val change = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows()).single()

                assertEquals(null, change.op)
                assertEquals(
                    true,
                    change.fields
                        .getValue("fav")
                        .v
                        .boolean(),
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun authIsOmittedUnlessSharingIsOn() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                seed(db)
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        db.credentialDao().insert(
                            ch.lkmc.neutrodyne.core.database.CredentialEntity(
                                origin = "example.com",
                                username = "u",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                        db.podcastDao().setCredentialId(1, 1)
                    }
                }
                secrets.podcastCredentialIds.value = mapOf(1L to 1L)
                secrets.put("example.com", "u", "p".toCharArray())
                db.syncOutboxDao().upsert(row("podcast", podcastSyncId, "auth", null, 1L))

                // Sharing off omits the field entirely; a lone auth row then builds no change.
                val off = ChangeBuilder(db, secrets) { false }.build(db.syncOutboxDao().rows())
                assertTrue(off.isEmpty())

                val on = ChangeBuilder(db, secrets) { true }.build(db.syncOutboxDao().rows()).single()
                val auth = assertNotNull(on.fields["auth"]).v.jsonObject()
                assertEquals("u", auth["u"]!!.content())
                assertEquals("p", auth["p"]!!.content())
            } finally {
                db.close()
            }
        }

    @Test
    fun aStarRowCarriesTheCredentialOriginAndAuthWhileSharingIsOn() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                seed(db)
                linkCredential(db)
                secrets.podcastCredentialIds.value = mapOf(1L to 1L)
                secrets.put("https://example.com/feed", "anna", "hunter2".toCharArray())
                db.syncOutboxDao().upsert(row("podcast", podcastSyncId, "*", null, 1L))

                val on = ChangeBuilder(db, secrets) { true }.build(db.syncOutboxDao().rows()).single()
                assertEquals(
                    "https://example.com/feed",
                    on.fields
                        .getValue("credentialOrigin")
                        .v
                        .content(),
                )
                val auth = assertNotNull(on.fields["auth"]).v.jsonObject()
                assertEquals("anna", auth["u"]!!.content())
                assertEquals("hunter2", auth["p"]!!.content())

                // Sharing off: the origin still travels; the secret never does.
                val off = ChangeBuilder(db, secrets) { false }.build(db.syncOutboxDao().rows()).single()
                assertEquals(
                    "https://example.com/feed",
                    off.fields
                        .getValue("credentialOrigin")
                        .v
                        .content(),
                )
                assertFalse("auth" in off.fields)
            } finally {
                db.close()
            }
        }

    @Test
    fun aReplayedAuthMarkerIsHydratedAtPushTime() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                seed(db)
                val storeKey = pendingAuthOrigin("acc", podcastSyncId, "01a3185c5000000a-0000000000000002")
                secrets.put(storeKey, "anna", "replayed".toCharArray())
                db.syncOutboxDao().upsert(
                    row("podcast", podcastSyncId, "auth", authMarker(storeKey).toString(), 1L)
                        .copy(captureKind = ch.lkmc.neutrodyne.core.database.SyncCaptureKind.REPLAY),
                )

                val on = ChangeBuilder(db, secrets) { true }.build(db.syncOutboxDao().rows()).single()
                val auth = assertNotNull(on.fields["auth"]).v.jsonObject()
                assertEquals("anna", auth["u"]!!.content())
                assertEquals("replayed", auth["p"]!!.content())

                // A marker whose snapshot is gone, or sharing off: the field is omitted.
                secrets.remove(storeKey)
                assertTrue(ChangeBuilder(db, secrets) { true }.build(db.syncOutboxDao().rows()).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun groupMemberAndUpNextFieldsReadTheirSources() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.syncOutboxDao().upsert(row("group", groupUuid, "name", null, 1L))
                db.syncOutboxDao().upsert(row("member", "$groupUuid$podcastSyncId", "ok", null, 2L))
                db.syncOutboxDao().upsert(row("upnext", episodeRid, "ok", null, 3L))
                val changes = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows())

                assertEquals(
                    "Group",
                    changes
                        .single { it.coll == "group" }
                        .fields
                        .getValue("name")
                        .v
                        .content(),
                )
                assertEquals(
                    "a",
                    changes
                        .single { it.coll == "member" }
                        .fields
                        .getValue("ok")
                        .v
                        .content(),
                )
                assertEquals(
                    "a",
                    changes
                        .single { it.coll == "upnext" }
                        .fields
                        .getValue("ok")
                        .v
                        .content(),
                )
                assertFalse(changes.any { it.coll == "session" })
            } finally {
                db.close()
            }
        }

    private fun row(
        coll: String,
        rid: String,
        field: String,
        value: String?,
        hlc: Long,
    ) = SyncOutboxEntity(coll, rid, field, hlc, node, value = value)

    /** Links podcast 1 to a credential row for the feed origin, with triggers silenced. */
    private suspend fun linkCredential(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                val id =
                    db
                        .credentialDao()
                        .putByOrigin(
                            ch.lkmc.neutrodyne.core.database.CredentialEntity(
                                origin = "https://example.com/feed",
                                username = "anna",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                db.podcastDao().setCredentialId(1, id)
            }
        }
    }

    private suspend fun seed(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        id = 1,
                        syncId = podcastSyncId,
                        feedUrl = "https://example.com/feed",
                        feedKey = "https://example.com/feed",
                    ),
                )
                db.ingestDao().insertEpisodes(listOf(episodeEntity(id = 1, podcastId = 1, identityKey = "g:one")))
                db.groupDao().insert(groupEntity(uuid = groupUuid, name = "Group", orderKey = "a"))
                db.groupDao().insertMember(memberEntity(groupId = 1, podcastId = 1, orderKey = "a"))
                db.scopeSettingsDao().upsertPodcast(
                    ch.lkmc.neutrodyne.core.database
                        .PodcastSettingsEntity(1, ScopeOverrides()),
                )
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = 1, updatedAt = 1))
                db
                    .positionDao()
                    .insertIfAbsent(
                        EpisodePositionEntity(
                            episodeId = 1,
                            positionMs = 0,
                            positionSource = PositionSource.STREAM,
                            updatedAt = 1,
                        ),
                    )
                db.queueDao().insert(queueEntryEntity(episodeId = 1, orderKey = "a"))
            }
            db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = node, hlc = 1L shl 16))
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.content(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content

private fun kotlinx.serialization.json.JsonElement?.boolean(): Boolean =
    (this as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrict()

private fun kotlinx.serialization.json.JsonElement?.long(): Long =
    (this as kotlinx.serialization.json.JsonPrimitive).content.toLong()

private fun kotlinx.serialization.json.JsonElement?.jsonObject(): kotlinx.serialization.json.JsonObject =
    this as kotlinx.serialization.json.JsonObject
