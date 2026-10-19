// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncTriggersTest {
    @Test
    fun all24NamesAndUpdateColumnsMatchLiveSchemaAndGolden() =
        runTest {
            val db = TestDb.inMemory()
            try {
                assertEquals(24, SyncTriggers.specs.size)
                assertEquals(
                    24,
                    SyncTriggers.specs
                        .map { it.name }
                        .toSet()
                        .size,
                )
                db.useWriterConnection { c ->
                    for (spec in SyncTriggers.specs) {
                        val columns =
                            c.usePrepared("PRAGMA table_info(${spec.table})") { s ->
                                buildSet { while (s.step()) add(s.getText(1)) }
                            }
                        assertTrue(columns.containsAll(spec.columns), "${spec.name}: ${spec.columns - columns}")
                    }
                    assertEquals(
                        24L,
                        c.longQuery(
                            "SELECT count(*) FROM sqlite_master" +
                                " WHERE type='trigger' AND name GLOB 'sync_cap_*'",
                        ),
                    )
                }
                val golden = checkNotNull(javaClass.getResource("/sync-triggers.sql")).readText()
                assertEquals(golden, SyncTriggers.sql().joinToString("\n\n", postfix = "\n"))
            } finally {
                db.close()
            }
        }

    @Test
    fun ensureRepairsTheWholeSetAndIdenticalEnsureWritesNothing() =
        runTest {
            raw { c ->
                c.exec("DROP TRIGGER sync_cap_podcast_ins")
                c.exec("DROP TRIGGER sync_cap_podcast_del")
                c.exec("CREATE TRIGGER sync_cap_podcast_del AFTER DELETE ON podcast BEGIN SELECT 1; END")
                c.exec("CREATE TRIGGER sync_cap_extra AFTER INSERT ON podcast BEGIN SELECT 1; END")
                c.exec("CREATE TRIGGER syncXcapXforeign AFTER INSERT ON podcast BEGIN SELECT 1; END")
                SyncTriggers.ensure(c)
                assertEquals(SyncTriggers.sql().map { it.removeSuffix(";") }.toSet(), captureSql(c).toSet())
                assertEquals(1L, c.longQuery("SELECT count(*) FROM sqlite_master WHERE name='syncXcapXforeign'"))
                val changes = c.longQuery("SELECT total_changes()")
                val version = c.longQuery("PRAGMA schema_version")
                SyncTriggers.ensure(c)
                assertEquals(changes, c.longQuery("SELECT total_changes()"))
                assertEquals(version, c.longQuery("PRAGMA schema_version"))
            }
        }

    @Test
    fun failedEnsureRollsBackTheEntireRepair() =
        runTest {
            raw { c ->
                c.exec("DROP TABLE episode")
                val before = captureSql(c)
                assertFailsWith<Exception> { SyncTriggers.ensure(c) }
                assertEquals(before, captureSql(c))
                c.exec("BEGIN IMMEDIATE")
                c.exec("ROLLBACK")
            }
        }

    @Test
    fun productionOpenRepairsTriggersAndResetsStrayApplying() =
        runTest {
            val dir = Files.createTempDirectory("sync-open")
            try {
                TestDb.file(dir).let { db ->
                    db.syncStateDao().get()
                    db.close()
                }
                BundledSQLiteDriver().open(dir.resolve(NeutrodyneDatabase.FILE_NAME).toString()).use { c ->
                    c.exec("DROP TRIGGER sync_cap_podcast_ins")
                    c.exec("UPDATE sync_state SET applying=1, enabled=1, nodeId='0000000000000001'")
                }
                val db = TestDb.file(dir)
                try {
                    assertEquals(false, db.syncStateDao().get()!!.applying)
                    db.podcastDao().insertPodcast(
                        ch.lkmc.neutrodyne.core.testing.database
                            .podcastEntity(),
                    )
                    assertEquals(
                        "*",
                        db
                            .syncOutboxDao()
                            .rows()
                            .single()
                            .field,
                    )
                } finally {
                    db.close()
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    private suspend fun raw(block: suspend (SQLiteConnection) -> Unit) {
        val dir = Files.createTempDirectory("sync-triggers")
        try {
            TestDb.file(dir).let { db ->
                db.syncStateDao().get()
                db.close()
            }
            BundledSQLiteDriver().open(dir.resolve(NeutrodyneDatabase.FILE_NAME).toString()).use { block(it) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun captureSql(c: SQLiteConnection): List<String> =
        c.texts("SELECT sql FROM sqlite_master WHERE type='trigger' AND name GLOB 'sync_cap_*' ORDER BY name")
}
