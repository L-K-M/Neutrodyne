// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.source
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountExportIntegrationTest {
    @Test fun sessionResolvesRetainedAuthorityAfterRedirectRecordGc() =
        authServices { f ->
            val owner = f.account()
            val actor = f.principal(f.grant(owner))
            val fixture = WriterFixture()
            val reference = JsonObject(mapOf("p" to JsonPrimitive(A), "k" to JsonPrimitive("g:ep")))
            val context = JsonObject(mapOf("type" to JsonPrimitive("PODCAST"), "podcast" to JsonPrimitive(A)))
            f.services.records.push(
                actor,
                SyncRequest(
                    null,
                    listOf(
                        fixture.podcast(B),
                        fixture.write(session, "episode", reference),
                        fixture.write(session, "context", context),
                    ),
                ),
            )
            f.store.write(owner.id) { assignRedirect(podcastA, key(SyncCollection.PODCAST, B)) }
            // No source record remains; only the durable ledger can resolve this embedded reference.
            val path = f.services.backups.export(owner.id)
            ZipFile(path.toFile()).use { zip ->
                val codec = BackupCodec()
                val queue = zip.getInputStream(zip.getEntry("queue.json")).use { codec.decodeQueue(it.source()) }
                assertEquals("example.com/feed", queue.session?.current?.p)
                assertEquals("example.com/feed", queue.session?.contextPodcastKey)
                assertEquals("g:ep", queue.session?.current?.k)
            }
        }

    @Test fun largeAccountStreamsEveryEpisodeIntoTheAppFormat() =
        authServices { f ->
            val owner = f.account()
            val actor = f.principal(f.grant(owner))
            val fixture = WriterFixture()
            f.services.records.push(actor, SyncRequest(null, listOf(fixture.podcast())))
            val raw = fixture.state(episode(), "fav", JsonPrimitive(true))
            f.store.write(owner.id) {
                repeat(EPISODES) { index ->
                    val address = episode(episode = "g:stream-$index")
                    put(StoredRecord(address, raw, nextSequence(), null, f.now, null))
                }
            }
            val path = f.services.backups.export(owner.id)
            ZipFile(path.toFile()).use { zip ->
                val codec = BackupCodec()
                val manifestEntry = zip.getEntry("manifest.json")
                val manifest = zip.getInputStream(manifestEntry).use { codec.decodeManifest(it.source()) }
                assertEquals(EPISODES, manifest.counts.episodeLines)
                zip.getInputStream(zip.getEntry("episodes.jsonl")).use { input ->
                    codec.episodeLines(input.source()).use { lines ->
                        var count = 0
                        while (lines.hasNext()) {
                            assertTrue(lines.next().fav)
                            count++
                        }
                        assertEquals(EPISODES, count)
                        assertEquals(0, lines.skippedLines)
                    }
                }
            }
        }

    private companion object {
        const val EPISODES = 10_000
    }
}
