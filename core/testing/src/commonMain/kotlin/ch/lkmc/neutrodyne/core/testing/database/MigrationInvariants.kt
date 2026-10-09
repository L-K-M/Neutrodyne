// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * The migration invariant snapshot of 02 Invariants: `capture` runs each documented projection in
 * a deterministic row order and folds the typed values into an ordered SHA-256; `assertPreserved`
 * compares a before- and after-migration snapshot. A migration test declares intended changes by
 * passing their labels in [Mismatches][assertPreserved]'s `tolerated` set.
 */
object MigrationInvariants {
    /** One digest per projection plus the row-count and `sqlite_sequence` guards. */
    data class Snapshot(
        val digests: Map<String, String>,
        val counts: Map<String, Long>,
        val sequences: Map<String, Long>,
    )

    /**
     * The 02 invariant projections. `*` means "every column" (the spec asks for the whole row);
     * the `ORDER BY` is what makes the digest ordered — the migration's own reorderings must not
     * change the outcome.
     */
    private val PROJECTIONS: List<Pair<String, String>> =
        listOf(
            "subscriptions" to
                "SELECT syncId, feedKey, feedUrl, customTitle, includeInAll, subscribedAt, credentialId" +
                " FROM podcast ORDER BY id",
            "aliases" to "SELECT url, podcastId FROM podcast_url_alias ORDER BY url",
            "groups" to
                "SELECT uuid, name, nameKey, orderKey, colorArgb, iconKey, feedOrder, playOrder," +
                " filterFlags, mediaFilter, hideOlderThanDays, showAsTab FROM podcast_group ORDER BY id",
            "members" to
                "SELECT groupId, podcastId, orderKey FROM podcast_group_member ORDER BY groupId, podcastId",
            "podcast_settings" to "SELECT * FROM podcast_settings ORDER BY podcastId",
            "group_settings" to "SELECT * FROM podcast_group_settings ORDER BY groupId",
            "episode_identity" to "SELECT id, podcastId, identityKey FROM episode ORDER BY id",
            "episode_state" to "SELECT * FROM episode_state ORDER BY episodeId",
            "positions" to "SELECT episodeId, positionMs FROM episode_position ORDER BY episodeId",
            "queue" to "SELECT episodeId, orderKey FROM queue_entry ORDER BY orderKey, id",
            "play_session" to
                "SELECT currentEpisodeId, contextType, contextId FROM play_session ORDER BY id",
            "downloads" to
                "SELECT episodeId, rootId, relativePath, finalUri, totalBytes FROM download" +
                " WHERE state = 'COMPLETED' ORDER BY episodeId",
            "credentials" to "SELECT id, origin, username, secretCipher, iv FROM credential ORDER BY id",
            "sync_state" to "SELECT * FROM sync_state ORDER BY id",
            "sync_outbox" to "SELECT * FROM sync_outbox ORDER BY coll, rid, field",
            "sync_clock" to "SELECT * FROM sync_clock ORDER BY coll, rid",
            "sync_parked" to "SELECT * FROM sync_parked ORDER BY id",
            "sync_held" to "SELECT * FROM sync_held ORDER BY id",
        )

    /** Every schema-v1 table for the `COUNT(*)` guard (02). */
    private val TABLES: List<String> =
        listOf(
            "podcast",
            "podcast_url_alias",
            "credential",
            "podcast_settings",
            "podcast_group",
            "podcast_group_member",
            "podcast_group_settings",
            "episode",
            "episode_description",
            "episode_transcript",
            "episode_alt_enclosure",
            "person",
            "funding",
            "chapter",
            "episode_state",
            "episode_position",
            "queue_entry",
            "play_session",
            "download",
            "artwork",
            "import_session",
            "import_item",
            "sync_state",
            "sync_outbox",
            "sync_clock",
            "sync_parked",
            "sync_held",
        )

    /** The `autoGenerate` tables whose `sqlite_sequence` high-water mark must never shrink. */
    private val AUTOINCREMENT_TABLES: List<String> =
        listOf(
            "podcast",
            "credential",
            "podcast_group",
            "episode",
            "person",
            "funding",
            "queue_entry",
            "import_session",
            "sync_parked",
            "sync_held",
        )

    suspend fun capture(connection: SQLiteConnection): Snapshot {
        val digests = LinkedHashMap<String, String>(PROJECTIONS.size)
        for ((label, sql) in PROJECTIONS) {
            digests[label] = digest(connection, sql)
        }

        val counts = LinkedHashMap<String, Long>(TABLES.size)
        for (table in TABLES) {
            counts[table] = scalarLong(connection, "SELECT COUNT(*) FROM $table")
        }

        val sequences = LinkedHashMap<String, Long>(AUTOINCREMENT_TABLES.size)
        for (table in AUTOINCREMENT_TABLES) {
            sequences[table] =
                scalarLong(
                    connection,
                    "SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name = '$table'), 0)",
                )
        }
        return Snapshot(digests, counts, sequences)
    }

    /**
     * Asserts [after] preserves [before]: every projection digest matches (unless the test lists
     * the label in [tolerated] as an intended change), every table's row count is unchanged, and
     * no `sqlite_sequence` high-water mark went down.
     */
    fun assertPreserved(
        before: Snapshot,
        after: Snapshot,
        tolerated: Set<String> = emptySet(),
    ) {
        val changed =
            before.digests.keys.filter { label ->
                label !in tolerated && before.digests[label] != after.digests[label]
            }
        check(changed.isEmpty()) { "migration changed invariant projections: $changed" }

        val lostRows = before.counts.filter { (table, n) -> after.counts[table] != n }.keys
        check(lostRows.isEmpty()) { "migration changed row counts of: $lostRows" }

        val shrunk =
            before.sequences.filter { (table, seq) -> (after.sequences[table] ?: 0L) < seq }.keys
        check(shrunk.isEmpty()) { "migration lowered sqlite_sequence of: $shrunk" }
    }

    private suspend fun digest(
        connection: SQLiteConnection,
        sql: String,
    ): String {
        val sha = Sha256()
        connection.prepare(sql).use { stmt ->
            while (stmt.step()) {
                for (i in 0 until stmt.getColumnCount()) {
                    sha.update(valueBytes(stmt, i))
                }
                sha.update(ROW_END)
            }
        }
        return sha.hex()
    }

    private suspend fun scalarLong(
        connection: SQLiteConnection,
        sql: String,
    ): Long =
        connection.prepare(sql).use { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }

    /**
     * Typed canonical encoding of one column value — two representations never collide. TEXT and
     * BLOB payloads carry a fixed 8-byte length prefix: without it a value ending in another
     * column's type tag would let the column boundary slide ("a\x01" + "b" = "a" + "\x01b").
     */
    private fun valueBytes(
        stmt: SQLiteStatement,
        i: Int,
    ): ByteArray =
        when {
            stmt.isNull(i) -> {
                byteArrayOf(TYPE_NULL)
            }

            stmt.getColumnType(i) == SQLITE_BLOB -> {
                val blob = stmt.getBlob(i)
                byteArrayOf(TYPE_BLOB) + blob.size.toLong().toFixedBytes() + blob
            }

            stmt.getColumnType(i) == SQLITE_FLOAT -> {
                byteArrayOf(TYPE_FLOAT) + stmt.getDouble(i).toBits().toFixedBytes()
            }

            stmt.getColumnType(i) == SQLITE_INTEGER -> {
                byteArrayOf(TYPE_INTEGER) + stmt.getLong(i).toFixedBytes()
            }

            else -> {
                val text = stmt.getText(i).encodeToByteArray()
                byteArrayOf(TYPE_TEXT) + text.size.toLong().toFixedBytes() + text
            }
        }

    private fun Long.toFixedBytes(): ByteArray =
        ByteArray(Long.SIZE_BYTES) { i -> (this shr ((Long.SIZE_BYTES - 1 - i) * 8)).toByte() }

    private val ROW_END = byteArrayOf(0xFE.toByte())

    // sqlite3_column_type values; androidx.sqlite exposes no named constants.
    private const val SQLITE_INTEGER = 1
    private const val SQLITE_FLOAT = 2
    private const val SQLITE_BLOB = 4

    private const val TYPE_NULL: Byte = 0
    private const val TYPE_TEXT: Byte = 1
    private const val TYPE_INTEGER: Byte = 2
    private const val TYPE_FLOAT: Byte = 3
    private const val TYPE_BLOB: Byte = 4
}

/**
 * Minimal SHA-256 for [MigrationInvariants] — the JDK digest is unavailable in `commonMain`.
 * FIPS 180-4, padded per message.
 */
@OptIn(ExperimentalUnsignedTypes::class)
private class Sha256 {
    private val state =
        uintArrayOf(
            0x6a09e667u,
            0xbb67ae85u,
            0x3c6ef372u,
            0xa54ff53au,
            0x510e527fu,
            0x9b05688cu,
            0x1f83d9abu,
            0x5be0cd19u,
        )
    private val buffer = ByteArray(64)
    private var bufferSize = 0
    private var length = 0L

    fun update(bytes: ByteArray) {
        var offset = 0
        length += bytes.size
        while (offset < bytes.size) {
            val n = minOf(64 - bufferSize, bytes.size - offset)
            bytes.copyInto(buffer, bufferSize, offset, offset + n)
            bufferSize += n
            offset += n
            if (bufferSize == 64) {
                compress(buffer, 0)
                bufferSize = 0
            }
        }
    }

    fun hex(): String {
        val bitLength = length * 8
        update(PAD)
        while (bufferSize != 56) update(ZERO)
        for (i in 7 downTo 0) update(byteArrayOf((bitLength shr (i * 8)).toByte()))
        check(bufferSize == 0)
        return state.joinToString("") { it.toString(16).padStart(8, '0') }
    }

    private fun compress(
        block: ByteArray,
        offset: Int,
    ) {
        val w = IntArray(64)
        for (t in 0 until 16) {
            val i = offset + t * 4
            w[t] = (block[i].toInt() and 0xFF shl 24) or
                (block[i + 1].toInt() and 0xFF shl 16) or
                (block[i + 2].toInt() and 0xFF shl 8) or
                (block[i + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }

        var a = state[0].toInt()
        var b = state[1].toInt()
        var c = state[2].toInt()
        var d = state[3].toInt()
        var e = state[4].toInt()
        var f = state[5].toInt()
        var g = state[6].toInt()
        var h = state[7].toInt()
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + K[t].toInt() + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        state[0] += a.toUInt()
        state[1] += b.toUInt()
        state[2] += c.toUInt()
        state[3] += d.toUInt()
        state[4] += e.toUInt()
        state[5] += f.toUInt()
        state[6] += g.toUInt()
        state[7] += h.toUInt()
    }

    private companion object {
        val PAD = byteArrayOf(-128)
        val ZERO = byteArrayOf(0)

        // FIPS 180-4 §4.2.2 — unsigned so the values transcribe verbatim.
        val K =
            uintArrayOf(
                0x428a2f98u,
                0x71374491u,
                0xb5c0fbcfu,
                0xe9b5dba5u,
                0x3956c25bu,
                0x59f111f1u,
                0x923f82a4u,
                0xab1c5ed5u,
                0xd807aa98u,
                0x12835b01u,
                0x243185beu,
                0x550c7dc3u,
                0x72be5d74u,
                0x80deb1feu,
                0x9bdc06a7u,
                0xc19bf174u,
                0xe49b69c1u,
                0xefbe4786u,
                0x0fc19dc6u,
                0x240ca1ccu,
                0x2de92c6fu,
                0x4a7484aau,
                0x5cb0a9dcu,
                0x76f988dau,
                0x983e5152u,
                0xa831c66du,
                0xb00327c8u,
                0xbf597fc7u,
                0xc6e00bf3u,
                0xd5a79147u,
                0x06ca6351u,
                0x14292967u,
                0x27b70a85u,
                0x2e1b2138u,
                0x4d2c6dfcu,
                0x53380d13u,
                0x650a7354u,
                0x766a0abbu,
                0x81c2c92eu,
                0x92722c85u,
                0xa2bfe8a1u,
                0xa81a664bu,
                0xc24b8b70u,
                0xc76c51a3u,
                0xd192e819u,
                0xd6990624u,
                0xf40e3585u,
                0x106aa070u,
                0x19a4c116u,
                0x1e376c08u,
                0x2748774cu,
                0x34b0bcb5u,
                0x391c0cb3u,
                0x4ed8aa4au,
                0x5b9cca4fu,
                0x682e6ff3u,
                0x748f82eeu,
                0x78a5636fu,
                0x84c87814u,
                0x8cc70208u,
                0x90befffau,
                0xa4506cebu,
                0xbef9a3f7u,
                0xc67178f2u,
            )
    }
}
