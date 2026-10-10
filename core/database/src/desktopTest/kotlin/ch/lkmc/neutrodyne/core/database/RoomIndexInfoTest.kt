// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.util.TableInfo
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lowest-boundary regression for vendored Room patch 0003: `SchemaInfoUtil.readIndex` reads
 * `PRAGMA index_xinfo` rows but never reads the `key` flag, so on a WITHOUT ROWID table the
 * primary-key columns stored inside every secondary index (key = 0 auxiliary rows) are
 * reported as declared index columns. Migration validation then fails a correct database:
 * `Expected columns [hlc, nodeId], found [hlc, nodeId, coll, rid, field]` on sync_outbox.
 *
 * Per https://www.sqlite.org/pragma.html#pragma_index_xinfo the `key` column is 1 for a
 * declared key column and 0 for an auxiliary column — the WITHOUT ROWID primary key
 * carried for lookups, or the rowid (cid = -1) on ordinary tables.
 */
class RoomIndexInfoTest {
    /**
     * The exact failing shape: a WITHOUT ROWID table (like sync_outbox) with a composite
     * primary key and a declared secondary index must expose only the declared columns.
     */
    @Test
    fun aWithoutRowIdSecondaryIndexExposesOnlyItsDeclaredColumns() =
        runBlocking {
            BundledSQLiteDriver().open(":memory:").use { connection ->
                connection.execSQL(
                    "CREATE TABLE sync_outbox (" +
                        "coll TEXT NOT NULL, rid TEXT NOT NULL, field TEXT NOT NULL, " +
                        "hlc TEXT NOT NULL, nodeId TEXT NOT NULL, " +
                        "PRIMARY KEY (coll, rid, field)) WITHOUT ROWID",
                )
                connection.execSQL(
                    "CREATE INDEX index_sync_outbox_hlc_nodeId ON sync_outbox (hlc, nodeId)",
                )

                // Raw pragma proof: the PK columns ride along as key = 0 auxiliary rows —
                // index payload for lookups, not declared key columns.
                val (declared, auxiliary) = connection.keyAndAuxiliaryColumns("index_sync_outbox_hlc_nodeId")
                assertEquals(listOf("hlc", "nodeId"), declared)
                assertEquals(listOf("coll", "rid", "field"), auxiliary)

                val index = assertNotNull(TableInfo.read(connection, "sync_outbox").indices).single()
                assertEquals("index_sync_outbox_hlc_nodeId", index.name)
                assertEquals(listOf("hlc", "nodeId"), index.columns)
                assertEquals(listOf("ASC", "ASC"), index.orders)
                assertFalse(index.unique)
            }
        }

    /** Auxiliary filtering must preserve unique and DESC order metadata. */
    @Test
    fun aWithoutRowIdUniqueDescIndexKeepsItsDeclaredFlags() =
        runBlocking {
            BundledSQLiteDriver().open(":memory:").use { connection ->
                connection.execSQL(
                    "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL, c TEXT NOT NULL, " +
                        "PRIMARY KEY (a, b)) WITHOUT ROWID",
                )
                connection.execSQL("CREATE UNIQUE INDEX index_t_c_b ON t (c DESC, b)")

                val index = assertNotNull(TableInfo.read(connection, "t").indices).single()
                assertEquals(listOf("c", "b"), index.columns)
                assertEquals(listOf("DESC", "ASC"), index.orders)
                assertTrue(index.unique)
            }
        }

    /** An ordinary rowid table: only the declared column, the rowid stays out either way. */
    @Test
    fun aRowIdIndexExposesOnlyItsDeclaredColumns() =
        runBlocking {
            BundledSQLiteDriver().open(":memory:").use { connection ->
                connection.execSQL("CREATE TABLE t (a TEXT, b TEXT, c TEXT, PRIMARY KEY (a, b))")
                connection.execSQL("CREATE INDEX index_t_c ON t (c)")

                // On rowid tables the single auxiliary row is the rowid (cid = -1, no name).
                val (declared, auxiliary) = connection.keyAndAuxiliaryColumns("index_t_c")
                assertEquals(listOf("c"), declared)
                assertEquals(1, auxiliary.size)

                val index = assertNotNull(TableInfo.read(connection, "t").indices).single()
                assertEquals(listOf("c"), index.columns)
                assertEquals(listOf("ASC"), index.orders)
            }
        }

    /**
     * Expression index columns (cid = -2) were already dropped by the cid < 0 filter and must
     * stay dropped — the key filter only removes auxiliary rows, it widens nothing.
     */
    @Test
    fun anExpressionIndexStillDropsTheExpression() =
        runBlocking {
            BundledSQLiteDriver().open(":memory:").use { connection ->
                connection.execSQL("CREATE TABLE t (a TEXT, b TEXT)")
                connection.execSQL("CREATE INDEX index_t_expr ON t (a, (length(b)))")

                val index = assertNotNull(TableInfo.read(connection, "t").indices).single()
                assertEquals(listOf("a"), index.columns)
            }
        }

    /** Reads (key columns, auxiliary rows) of `PRAGMA index_xinfo` in seqno order. */
    private fun SQLiteConnection.keyAndAuxiliaryColumns(indexName: String): Pair<List<String?>, List<String?>> =
        prepare("PRAGMA index_xinfo(`$indexName`)").use { stmt ->
            val nameIx = stmt.columnIndex("name")
            val keyIx = stmt.columnIndex("key")
            val declared = mutableListOf<String?>()
            val auxiliary = mutableListOf<String?>()
            while (stmt.step()) {
                (if (stmt.getLong(keyIx) == 0L) auxiliary else declared).add(stmt.getText(nameIx))
            }
            declared to auxiliary
        }

    private fun androidx.sqlite.SQLiteStatement.columnIndex(name: String): Int =
        getColumnNames().indexOf(name).also { check(it >= 0) { "index_xinfo must expose a $name column" } }
}
