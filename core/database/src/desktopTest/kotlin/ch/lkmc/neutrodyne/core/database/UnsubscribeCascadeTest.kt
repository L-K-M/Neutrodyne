// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.OwnerType
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 02 Unsubscribe and merge / `PodcastDao.deleteCascade`: the podcast, its episodes, its recorded
 * GUID provenance and every child row go away; `play_session` and `import_item` lose their
 * references via `SET NULL` / targeted clears; the unshared credential goes, a shared one and
 * `sync:`/`podcastindex` tokens survive; `sync_parked`/`sync_clock` rows for the podcast go while
 * its own clock stays as tombstone.
 */
class UnsubscribeCascadeTest {
    @Test
    fun deleteCascadeRemovesThePodcastAndEverythingItOwns() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val g = db.groupDao().insert(groupEntity(orderKey = "a0"))

                // Credentials: unshared feed credential, one shared by both podcasts, and the two
                // token kinds the sweep must never touch.
                val unshared =
                    db
                        .credentialDao()
                        .insert(
                            CredentialEntity(
                                origin = "https://a",
                                username = "u",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                val shared =
                    db
                        .credentialDao()
                        .insert(
                            CredentialEntity(
                                origin = "https://shared",
                                username = "u",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                val syncToken =
                    db
                        .credentialDao()
                        .insert(
                            CredentialEntity(
                                origin = "sync:sync.example.com",
                                username = "",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                val piToken =
                    db
                        .credentialDao()
                        .insert(
                            CredentialEntity(
                                origin = "podcastindex",
                                username = "",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )

                val p1 =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-1", syncId = SYNC_1) { copy(credentialId = unshared) },
                    )
                val p2 =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-2", syncId = SYNC_2) { copy(credentialId = shared) },
                    )
                db.podcastDao().insertAlias(
                    PodcastUrlAliasEntity(
                        url = "https://old/feed",
                        podcastId = p1,
                        reason = AliasReason.REDIRECT,
                        addedAt = 1,
                    ),
                )
                db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(podcastId = p1, o = ScopeOverrides()))
                db.groupDao().insertMember(memberEntity(g, p1, orderKey = "a1"))

                val e1 =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = p1, identityKey = "g:1")),
                        ).single()
                val e2 =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = p2, identityKey = "g:2")),
                        ).single()

                // p1's child tables: state, position, queue, download, description, transcript,
                // alt enclosure, chapter, person + funding, and its recorded GUID provenance.
                db.ingestDao().recordGuidKnowledge(p1, setOf("shared-guid"), setOf("solo-guid"), setOf("hinted-guid"))
                db.episodeStateDao().upsert(episodeStateEntity(e1, playedAt = 1))
                db
                    .positionDao()
                    .upsert(
                        EpisodePositionEntity(
                            e1,
                            positionMs = 1,
                            positionSource = PositionSource.STREAM,
                            updatedAt = 1,
                        ),
                    )
                db.queueDao().insert(queueEntryEntity(e1, orderKey = "a0"))
                db.downloadDao().insert(downloadEntity(e1))
                db.ingestDao().replaceChildren(
                    episodeId = e1,
                    description = byteArrayOf(0, 1),
                    transcripts = listOf(EpisodeTranscriptEntity(e1, "https://tr", "text/vtt")),
                    altEnclosures =
                        listOf(
                            EpisodeAltEnclosureEntity(
                                e1,
                                ordinal = 0,
                                type = "audio/mpeg",
                                sourcesJson = "[]",
                            ),
                        ),
                    persons = listOf(PersonEntity(ownerType = OwnerType.EPISODE, ownerId = e1, name = "Guest")),
                    funding = listOf(FundingEntity(ownerType = OwnerType.EPISODE, ownerId = e1, url = "https://fund")),
                    pscChapters = listOf(ChapterEntity(e1, ChapterSource.PSC, ordinal = 0, startMs = 0)),
                )
                db.ingestDao().replacePodcastChildren(
                    podcastId = p1,
                    persons = listOf(PersonEntity(ownerType = OwnerType.PODCAST, ownerId = p1, name = "Host")),
                    funding = listOf(FundingEntity(ownerType = OwnerType.PODCAST, ownerId = p1, url = "https://fund2")),
                )

                // play_session: current episode + podcast context both on p1.
                db.useWriterConnection { conn ->
                    conn.exec(
                        "UPDATE play_session SET currentEpisodeId = $e1," +
                            " contextType = 'PODCAST', contextId = $p1 WHERE id = 0",
                    )
                }

                // import_item holds a podcastId reference (SET NULL on unsubscribe).
                val session =
                    db.importDao().insertSession(
                        ImportSessionEntity(createdAt = 1, sourceFormat = ImportFormat.OPML, state = ImportState.DONE),
                    )
                db.importDao().insertItems(
                    listOf(
                        ImportItemEntity(
                            sessionId = session,
                            ordinal = 0,
                            originalUrl = "https://feed/1",
                            kind = ImportItemKind.RSS,
                            groupNamesJson = "[]",
                            selected = true,
                            status = ImportItemStatus.SUBSCRIBED,
                            podcastId = p1,
                        ),
                    ),
                )

                // Sync groundwork rows referencing p1's syncId.
                db
                    .syncParkedDao()
                    .park(
                        SyncParkedEntity(
                            podcastSyncId = SYNC_1,
                            identityKey = "ep-key",
                            record = "{}",
                            receivedAt = 1,
                        ),
                    )
                db.syncClockDao().upsert(SyncClockEntity(coll = "episode", rid = "$SYNC_1:ep-key", clocks = "{}"))
                db.syncClockDao().upsert(SyncClockEntity(coll = "upnext", rid = "$SYNC_1:ep-key", clocks = "{}"))
                db.syncClockDao().upsert(
                    SyncClockEntity(coll = "member", rid = "${"g".repeat(36)}$SYNC_1", clocks = "{}"),
                )
                db.syncClockDao().upsert(SyncClockEntity(coll = "podcast", rid = SYNC_1, clocks = "{}"))
                db.syncClockDao().upsert(SyncClockEntity(coll = "podcast", rid = SYNC_2, clocks = "{}"))

                db.podcastDao().deleteCascade(p1, now = 9)

                // The podcast and everything FK-cascaded or explicitly deleted is gone.
                assertNull(db.podcastDao().byId(p1))
                assertTrue(db.ingestDao().existing(p1).isEmpty())
                assertNull(db.episodeStateDao().byEpisode(e1))
                assertNull(db.positionDao().byEpisode(e1))
                assertTrue(db.queueDao().entries().isEmpty())
                assertNull(db.downloadDao().byEpisode(e1))
                assertTrue(db.chapterDao().ofSource(e1, ChapterSource.PSC).isEmpty())
                assertTrue(db.groupDao().membersOf(g).isEmpty())
                assertNull(db.scopeSettingsDao().forPodcast(p1))

                db.useWriterConnection { conn ->
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM podcast_url_alias"))
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM episode_description"))
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM episode_transcript"))
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM episode_alt_enclosure"))
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM episode_guid_provenance"))
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM person"))
                    assertEquals(0, conn.longQuery("SELECT COUNT(*) FROM funding"))
                }

                // Credentials: unshared gone; shared, sync: and podcastindex survive.
                assertTrue(db.credentialDao().byOrigin("https://a").isEmpty())
                assertNotNull(db.credentialDao().byOrigin("https://shared").firstOrNull())
                assertNotNull(db.credentialDao().byOrigin("sync:sync.example.com").firstOrNull())
                assertNotNull(db.credentialDao().byOrigin("podcastindex").firstOrNull())

                // play_session cleared: current episode SET NULL, context cleared, generation +1.
                val session2 = assertNotNull(db.playSessionDao().get())
                assertNull(session2.currentEpisodeId)
                assertNull(session2.contextType)
                assertNull(session2.contextId)
                assertEquals(1, session2.generation)
                assertEquals(9, session2.updatedAt)

                // import_item survived with podcastId = NULL.
                val item = db.importDao().itemsOf(session).single()
                assertNull(item.podcastId)

                // Sync groundwork: parked gone; episode/upnext/member clocks gone; p1's own clock
                // row stays as tombstone; p2 untouched.
                assertTrue(db.syncParkedDao().forPodcast(SYNC_1).isEmpty())
                assertNull(db.syncClockDao().get("episode", "$SYNC_1:ep-key"))
                assertNull(db.syncClockDao().get("upnext", "$SYNC_1:ep-key"))
                assertNull(db.syncClockDao().get("member", "${"g".repeat(36)}$SYNC_1"))
                assertNotNull(db.syncClockDao().get("podcast", SYNC_1))
                assertNotNull(db.syncClockDao().get("podcast", SYNC_2))

                // p2 and its episode are intact.
                assertNotNull(db.podcastDao().byId(p2))
                assertNotNull(db.episodeDao().byId(e2))
            } finally {
                db.close()
            }
        }

    private companion object {
        const val SYNC_1 = "11111111-1111-4111-8111-111111111111"
        const val SYNC_2 = "22222222-2222-4222-8222-222222222222"
    }
}
