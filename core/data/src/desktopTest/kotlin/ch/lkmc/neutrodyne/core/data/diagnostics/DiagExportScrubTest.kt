// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
