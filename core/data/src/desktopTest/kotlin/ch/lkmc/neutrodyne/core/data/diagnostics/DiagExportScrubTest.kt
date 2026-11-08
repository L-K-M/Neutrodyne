// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DiagExportScrub` against a real `VACUUM INTO` copy (02's export procedure, D23/N3):
 * the seeded secrets must not survive in any column, KEEP-listed columns keep their values,
 * URL values collapse to `scheme://host/…#{rowid}` and uniqueness keeps working.
 */
class DiagExportScrubTest {
    private val driver = BundledSQLiteDriver()
    private val dbDir =
        java.nio.file.Files
            .createTempDirectory("nd-scrub-src")
            .toFile()
            .apply { deleteOnExit() }
    private val copyFile = File.createTempFile("nd-scrub-copy", ".db").apply { deleteOnExit() }

    @Test
    fun scrubRemovesSecretsAndKeepsAllowedColumns() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                val podcastId =
                    db.podcastDao().insertPodcast(
                        podcastEntity(
                            feedUrl =
                                "https://alice:s3cret@feeds.example.invalid" +
                                    "/rss/a8F3kq09ZpLm2xQr7Tz4?token=SECRETTOKEN",
                            title = "Seeded Show",
                        ),
                    )
                db.episodeDao().insertStub(
                    episodeEntity(
                        podcastId = podcastId,
                        identityKey = "u:https://cdn.example.invalid/ep1.mp3?auth=SECRET2",
                        title = "Pilot",
                    ) {
                        copy(
                            guid = "https://feeds.example.invalid/guid/SECRETGUID",
                            enclosureUrl = "https://cdn.example.invalid/ep1.mp3?auth=SECRET2",
                            snippet = "talks about SECRETS in plain text",
                        )
                    },
                )
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example.invalid",
                        accountId = "acc-SECRETACCT",
                        deviceId = "dev-SECRETDEV",
                        lastSyncAt = 123L,
                    ),
                )
                db.useWriterConnection { conn ->
                    conn.usePrepared(
                        "INSERT INTO credential (origin, username, secretCipher, iv, createdAt) " +
                            "VALUES ('feed', 'alice', X'6E64735F53594E43544F4B454E534543524554', NULL, 1)",
                    ) { it.step() }
                }

                // The exporter's VACUUM INTO, then the scrub on a raw connection.
                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                    connection.prepare("VACUUM").use { it.step() }
                }

                driver.open(copyFile.absolutePath).use { connection ->
                    fun query(sql: String): List<List<String?>> =
                        buildList {
                            connection.prepare(sql).use { stmt ->
                                val cols = stmt.getColumnCount()
                                while (stmt.step()) {
                                    add(
                                        (0 until cols).map { i ->
                                            runCatching {
                                                if (stmt.isNull(i)) null else stmt.getText(i)
                                            }.getOrNull()
                                        },
                                    )
                                }
                            }
                        }

                    // Payload tables emptied; sync disabled.
                    assertEquals(0, query("SELECT COUNT(*) FROM credential")[0][0]!!.toInt())
                    assertEquals(0, query("SELECT COUNT(*) FROM sync_outbox")[0][0]!!.toInt())
                    assertEquals(0, query("SELECT COUNT(*) FROM sync_clock")[0][0]!!.toInt())
                    val state = query("SELECT enabled, serverUrl, accountId, deviceId FROM sync_state")
                    assertEquals("0", state[0][0])
                    assertFalse(state[0][1]!!.contains("sync.example.invalid"))
                    assertFalse(state[0][2]!!.contains("SECRETACCT"))
                    assertFalse(state[0][3]!!.contains("SECRETDEV"))

                    // Triggers are gone.
                    assertEquals(0, query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'")[0][0]!!.toInt())

                    // podcast.title is KEEP-listed; feedUrl became scheme://host/…#rowid.
                    val podcast = query("SELECT title, feedUrl, syncId FROM podcast")
                    assertEquals("Seeded Show", podcast[0][0])
                    assertEquals("https://feeds.example.invalid/…#1", podcast[0][1])
                    assertFalse(podcast[0][2]!!.contains("s3cret"))
                    assertTrue(podcast[0][2]!!.contains("…#"))

                    // episode: identityKey keeps its u: prefix; guid is URL-masked;
                    // title is KEEP; enclosure URL keeps host only; free text collapses.
                    val episode =
                        query(
                            "SELECT title, identityKey, guid, enclosureUrl, snippet FROM episode",
                        )
                    assertEquals("Pilot", episode[0][0])
                    assertTrue(episode[0][1]!!.startsWith("u:…#"))
                    // A URL-shaped guid has no ≤3-char key prefix: the PREFIXED rule
                    // yields the bare `…#{rowid}` mask.
                    assertEquals("…#1", episode[0][2])
                    assertEquals("https://cdn.example.invalid/…#1", episode[0][3])
                    assertEquals("…#1", episode[0][4])

                    // No seeded secret survives anywhere.
                    val secrets =
                        listOf(
                            "s3cret",
                            "SECRETTOKEN",
                            "SECRET2",
                            "SECRETGUID",
                            "SECRETACCT",
                            "SECRETDEV",
                            "a8F3kq09ZpLm2xQr7Tz4",
                            "nds_SYNCTOKENSECRET",
                            "sync.example.invalid",
                            "talks about",
                        )
                    for (table in query("SELECT name FROM sqlite_master WHERE type='table'").flatten()) {
                        val name = table ?: continue
                        if (name.startsWith("sqlite_") || name == "room_master_table") continue
                        for (row in query("SELECT * FROM \"$name\"")) {
                            for (cell in row) {
                                if (cell == null) continue
                                for (secret in secrets) {
                                    assertFalse(
                                        cell.contains(secret),
                                        "secret '$secret' survived in $name: $cell",
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }

    /**
     * `episode_description.html` is `BLOB`-affinity, so the TEXT-column pass never saw it
     * (review I1). The seeded pair covers both codec storage forms: < 512 B stays raw
     * UTF-8 (byte-readable in the copy), ≥ 512 B is raw DEFLATE (only a decoded read
     * exposes it). After the scrub every copied blob must still decode, decode to the
     * `…#{rowid}` mask and carry no secret — while the source database keeps the
     * originals untouched.
     */
    @Test
    fun scrubRewritesEncodedDescriptionsToCodecMasks() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                val podcastId =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedUrl = "https://desc.example.invalid/feed", title = "Desc"),
                    )
                val shortEpisode =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = podcastId, identityKey = "ep-desc-1"),
                    )
                val longEpisode =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = podcastId, identityKey = "ep-desc-2"),
                    )
                val shortHtml =
                    "<p>notes <a href=\"https://alice:s3cret@notes1.example.invalid" +
                        "/ep?token=SECRETTOKEN\">link</a></p>"
                val longHtml =
                    "<p>" + "filler ".repeat(80) +
                        "<a href=\"https://bob:DESCBLOBSECRET@notes2.example.invalid" +
                        "/x?auth=DESCBLOBTOKEN\">more</a></p>"
                val shortBlob = EpisodeDescriptionCodec.encode(shortHtml, DescriptionFormat.HTML)
                val longBlob = EpisodeDescriptionCodec.encode(longHtml, DescriptionFormat.PLAIN_TEXT)
                // Both storage forms are exercised: the short body is stored verbatim, the
                // long one is a deflate stream whose secret never appears in file bytes.
                assertEquals(RAW_HTML_TAG, shortBlob[0])
                assertEquals(DEFLATED_TEXT_TAG, longBlob[0])
                db.useWriterConnection { conn ->
                    conn.usePrepared(
                        "INSERT INTO episode_description (episodeId, html) VALUES (?, ?)",
                    ) {
                        it.bindLong(1, shortEpisode)
                        it.bindBlob(2, shortBlob)
                        it.step()
                        it.reset()
                        it.clearBindings()
                        it.bindLong(1, longEpisode)
                        it.bindBlob(2, longBlob)
                        it.step()
                    }
                }

                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }

                // The seeded descriptions decode to their secrets before the scrub — the
                // fixture is real codec data, not a text column dressed as a blob.
                driver.open(copyFile.absolutePath).use { connection ->
                    val decoded = decodedDescriptions(connection)
                    assertTrue(decoded[0].contains("s3cret"), "seeded raw description: $decoded")
                    assertTrue(decoded[1].contains("DESCBLOBSECRET"), "seeded deflated description: $decoded")
                }

                driver.open(copyFile.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                    connection.prepare("VACUUM").use { it.step() }
                }

                driver.open(copyFile.absolutePath).use { connection ->
                    val decoded = decodedDescriptions(connection)
                    assertEquals(2, decoded.size)
                    for (text in decoded) {
                        assertTrue(
                            text.matches(Regex("…#\\d+")),
                            "a copied description must decode to the mask, got: $text",
                        )
                        for (secret in DESC_SECRETS) {
                            assertFalse(text.contains(secret), "'$secret' in decoded description")
                        }
                    }
                }

                // The raw short encoding sat in the copy byte-for-byte; the final VACUUM
                // must have removed it from the file's pages entirely.
                val bytes = copyFile.readBytes()
                for (secret in DESC_SECRETS) {
                    val needle = secret.encodeToByteArray()
                    val found =
                        (0..bytes.size - needle.size).any { i ->
                            needle.indices.all { j -> bytes[i + j] == needle[j] }
                        }
                    assertFalse(found, "'$secret' still in exported bytes")
                }

                // The source database is read-only for the export: its descriptions still
                // decode to the seeded originals.
                db.useWriterConnection { conn ->
                    conn.usePrepared(
                        "SELECT html FROM episode_description ORDER BY episodeId",
                    ) { stmt ->
                        val decoded =
                            buildList {
                                while (stmt.step()) add(EpisodeDescriptionCodec.decode(stmt.getBlob(0)).text)
                            }
                        assertEquals(listOf(shortHtml, longHtml), decoded)
                    }
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }

    private fun decodedDescriptions(connection: SQLiteConnection): List<String> =
        buildList {
            connection
                .prepare("SELECT html FROM episode_description ORDER BY episodeId")
                .use { stmt ->
                    while (stmt.step()) {
                        // decode() never throws on the copy: a scrub that wrote an invalid
                        // blob fails here even if its text looked masked.
                        add(EpisodeDescriptionCodec.decode(stmt.getBlob(0)).text)
                    }
                }
        }

    private companion object {
        const val RAW_HTML_TAG: Byte = 0x00
        const val DEFLATED_TEXT_TAG: Byte = 0x03
        val DESC_SECRETS =
            listOf(
                "s3cret",
                "SECRETTOKEN",
                "DESCBLOBSECRET",
                "DESCBLOBTOKEN",
                "notes1.example.invalid",
                "notes2.example.invalid",
            )
    }

    @Test
    fun scrubMasksUrlFragments() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                // A fragment before any slash is part of the authority cut — the 02 rule
                // "only scheme://host survives" must cut it at `#` too.
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        feedUrl = "https://tokens.example.invalid#access_token=SECRETFRAG",
                        title = "Frag",
                    ),
                )
                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    connection.prepare("SELECT feedUrl FROM podcast").use { stmt ->
                        stmt.step()
                        assertEquals("https://tokens.example.invalid/…#1", stmt.getText(0))
                    }
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }

    @Test
    fun scrubMasksIpv6Hosts() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                db.podcastDao().insertPodcast(
                    podcastEntity(feedUrl = "http://[2001:db8::1]:8080/feed", title = "V6"),
                )
                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    connection.prepare("SELECT feedUrl FROM podcast").use { stmt ->
                        stmt.step()
                        assertEquals("http://[2001:db8::1]/…#1", stmt.getText(0))
                    }
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }

    @Test
    fun scrubMasksVarcharAndClobColumns() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                // SQLite's TEXT affinity also covers CHAR, CLOB and VARCHAR(n) declarations;
                // a column declared any of them must be scrubbed by default.
                db.useWriterConnection { conn ->
                    conn.usePrepared(
                        "CREATE TABLE scrub_probe(" +
                            "id INTEGER PRIMARY KEY, note VARCHAR(64), memo CLOB, n NUMERIC)",
                    ) { it.step() }
                    conn.usePrepared(
                        "INSERT INTO scrub_probe VALUES (1, 'probe SECRETNOTE', 'memo SECRETMEMO', 5)",
                    ) { it.step() }
                }
                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    val row =
                        buildList {
                            connection.prepare("SELECT note, memo, n FROM scrub_probe").use { stmt ->
                                while (stmt.step()) {
                                    add(
                                        Triple(stmt.getText(0), stmt.getText(1), stmt.getText(2)),
                                    )
                                }
                            }
                        }.single()
                    assertEquals("…#1", row.first)
                    assertEquals("…#1", row.second)
                    assertEquals("5", row.third)
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }

    @Test
    fun aFailedScrubLeavesTheCopyUntouched() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                val original = "https://fail.example.invalid/feed?s=SECRETFAIL"
                db.podcastDao().insertPodcast(podcastEntity(feedUrl = original, title = "Fail"))
                // A WITHOUT ROWID table has no `rowid` column, so scrubTable's SELECT throws
                // after earlier tables were already rewritten.
                db.useWriterConnection { conn ->
                    conn.usePrepared("CREATE TABLE probe(id TEXT PRIMARY KEY, note TEXT) WITHOUT ROWID") {
                        it.step()
                    }
                    conn.usePrepared("INSERT INTO probe VALUES ('k', 'x')") { it.step() }
                }
                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    try {
                        DiagExportScrub.scrub(connection)
                        error("scrub should fail on the rowid-less probe table")
                    } catch (_: androidx.sqlite.SQLiteException) {
                        // expected: the probe table cannot be read by rowid
                    }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    connection.prepare("SELECT feedUrl FROM podcast").use { stmt ->
                        stmt.step()
                        assertEquals(
                            original,
                            stmt.getText(0),
                            "a failed scrub must leave earlier tables untouched",
                        )
                    }
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }

    @Test
    fun scrubKeepsUniqueIndicesValid() =
        runTest {
            val db = TestDb.file(dbDir.toPath())
            try {
                // Two podcasts with identical scrub-target values would collide if the mask
                // ignored rowid — feedKey/identityKey are unique indices.
                repeat(3) { i ->
                    val pid =
                        db.podcastDao().insertPodcast(
                            podcastEntity(
                                feedUrl = "https://f$i.secret.invalid/feed.xml",
                                title = "Show $i",
                            ),
                        )
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = pid, identityKey = "g:same-guid-$i"),
                    )
                }
                copyFile.delete()
                db.useWriterConnection { conn ->
                    conn.usePrepared("VACUUM INTO '${copyFile.absolutePath}'") { it.step() }
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    DiagExportScrub.scrub(connection)
                }
                driver.open(copyFile.absolutePath).use { connection ->
                    val keys = mutableListOf<String>()
                    connection.prepare("SELECT identityKey FROM episode ORDER BY id").use { stmt ->
                        while (stmt.step()) keys += stmt.getText(0)
                    }
                    assertEquals(3, keys.size)
                    assertEquals(keys.toSet().size, keys.size)
                }
            } finally {
                db.close()
                dbDir.deleteRecursively()
                copyFile.delete()
            }
        }
}
