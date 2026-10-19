// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import ch.lkmc.neutrodyne.core.database.SyncTriggers

/** D22: the sole trigger-installing migration. No library or bookkeeping rows change. */
val MIGRATION_2_TO_3 =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            SyncTriggers.recreate(connection)
        }
    }
