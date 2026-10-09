// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database.migration

import androidx.room3.migration.Migration

/**
 * Every manual `Migration(N, N + 1)`, in version order (02 Writing migrations). Version 1 is the
 * complete M1a schema, so the list is empty until the first schema change.
 */
val ALL_MIGRATIONS: Array<Migration> = emptyArray()
