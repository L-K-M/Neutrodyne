// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.source
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountExportIntegrationTest {
    @Test fun sparseQueueExportsCarryMediaHintsIntoTheEpisodeLine() =
        authServices { f ->
            val owner = f.account()
            val actor = f.principal(f.grant(owner))
            val fixture = WriterFixture()
            f.services.records.push(
                actor,
                SyncRequest(
                    null,
                    listOf(
                        fixture.podcast(),
                        fixture
                            .write(
                                episode(episode = "g:queued", coll = SyncCollection.UPNEXT),
                                "in",
                                JsonPrimitive(true),
                            ).copy(
                                match =
                                    MatchHints(
                                        guid = "guid-queued",
                                        title = "Queued episode",
                                        date = "2026-10-01T00:00:00Z",
                                        enc = "https://cdn.example.com/queued.mp3",
                                        ytId = "vid123",
                                        link = "https://example.com/queued",
                                    ),
                            ),
                    ),
                ),
            )
            val path = f.services.backups.export(owner.id)
            ZipFile(path.toFile()).use { zip ->
                val codec = BackupCodec()
                val line =
                    zip.getInputStream(zip.getEntry("episodes.jsonl")).use { input ->
                        codec.episodeLines(input.source()).use { lines ->
                            generateSequence { if (lines.hasNext()) lines.next() else null }
                                .single { it.k == "g:queued" }
                        }
                    }
                assertEquals("guid-queued", line.g)
                assertEquals("Queued episode", line.t)
                assertEquals("https://cdn.example.com/queued.mp3", line.u)
                assertEquals("vid123", line.yt)
                assertEquals("https://example.com/queued", line.l)
            }
        }

    @Test fun exportSatisfiesTheAppsBackupZipCaps() =
        authServices { f ->
            val owner = f.account()
            val actor = f.principal(f.grant(owner))
            val fixture = WriterFixture()
            f.services.records.push(actor, SyncRequest(null, listOf(fixture.podcast())))
            val raw =
                fixture
                    .state(episode(), "fav", JsonPrimitive(true))
                    .copy(match = MatchHints(title = "T".repeat(900)))
            f.store.write(owner.id) {
                repeat(RATIO_EPISODES) { index ->
                    put(StoredRecord(episode(episode = "g:caps-$index"), raw, nextSequence(), null, f.now, null))
                }
            }
            val path = f.services.backups.export(owner.id)
            // Mirror the app's ZipGuard accounting under ArchiveCaps.BACKUP.
            val caps = ArchiveCaps.BACKUP
            ZipFile(path.toFile()).use { zip ->
                assertTrue(zip.size() <= caps.maxEntries, "entry count ${zip.size()}")
                var total = 0L
                zip.entries().asSequence().forEach { entry ->
                    total += entry.size
                    assertTrue(entry.size <= caps.maxEntryBytes, "${entry.name} ${entry.size}")
                    if (entry.size > caps.ratioFloorBytes) {
                        assertTrue(
                            entry.size <= entry.compressedSize * caps.maxRatio,
                            "${entry.name} inflates past the ratio guard",
                        )
                    }
                }
                assertTrue(total <= caps.maxTotalBytes, "total $total")
                assertTrue(
                    checkNotNull(zip.getEntry("episodes.jsonl")).size > caps.ratioFloorBytes,
                    "fixture must actually arm the ratio check",
                )
            }
        }

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
        const val RATIO_EPISODES = 20_000
    }
}
