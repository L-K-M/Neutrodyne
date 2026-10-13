// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * V1 → V2 (D98, 02 Migrations): adds `episode_guid_provenance` (feed-scoped GUID knowledge) and
 * `podcast.guidCoverageSince` (the coverage marker that gates `KNOWN_INDEPENDENT` recording).
 * Purely additive — every existing row, digest and `sqlite_sequence` mark is untouched — and
 * idempotent through Room's migration transaction: a partial failure rolls both statements back
 * and the next open retries cleanly.
 *
 * Migrated podcasts keep `guidCoverageSince = NULL`: nothing a V1 library observed can be
 * reconstructed, so every GUID stays unknown until the running feed's own evidence speaks —
 * ambiguity is still recorded, independence never is. That means a post-upgrade contested claim
 * prefers an unresolved insert/drop over any state transfer (03 deviation 17).
 */
val MIGRATION_1_TO_2 =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            exec(
                connection,
                "CREATE TABLE IF NOT EXISTS `episode_guid_provenance` (" +
                    "`podcastId` INTEGER NOT NULL, `guid` TEXT NOT NULL, `knowledge` TEXT NOT NULL," +
                    " PRIMARY KEY(`podcastId`, `guid`)," +
                    " FOREIGN KEY(`podcastId`) REFERENCES `podcast`(`id`)" +
                    " ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            exec(connection, "ALTER TABLE `podcast` ADD COLUMN `guidCoverageSince` INTEGER")
        }
    }

private suspend fun exec(
    connection: SQLiteConnection,
    sql: String,
) {
    connection.prepare(sql).use { it.step() }
}
