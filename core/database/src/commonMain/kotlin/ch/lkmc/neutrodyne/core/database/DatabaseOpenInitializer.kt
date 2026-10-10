// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/**
 * Band 100 of the start-up sequence (01 Application start-up): opens and recovers the database on
 * IO. A failure is not rethrown silently — [DatabaseOpener.awaitOpen] already recorded it in
 * `openState`, so the gate can render `Failed`; `runInitializers` logs the throw and later
 * initializers still run (they hold database-backed dependencies lazily).
 */
@ContributesIntoSet(AppScope::class)
@Inject
class DatabaseOpenInitializer(
    private val opener: DatabaseOpener,
) : AppInitializer {
    override val order: Int = ORDER

    override suspend fun run() {
        opener.awaitOpen()
    }

    private companion object {
        const val ORDER = 100
    }
}
