// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database.migration

import androidx.room3.migration.Migration

/**
 * Every manual `Migration(N, N + 1)`, in version order (02 Writing migrations).
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_TO_2)
