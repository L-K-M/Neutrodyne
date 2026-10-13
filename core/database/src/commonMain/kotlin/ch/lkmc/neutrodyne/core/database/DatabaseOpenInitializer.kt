// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

/**
 * Band 100 of the start-up sequence (01 Application start-up): opens and recovers the database on
 * IO. A failed open keeps higher bands suspended until the start-up gate's retry succeeds, so
 * their database-backed providers cannot fail permanently before the database is available.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class DatabaseOpenInitializer(
    private val opener: DatabaseOpener,
) : AppInitializer {
    override val order: Int = ORDER

    override suspend fun run() {
        try {
            opener.awaitOpen()
        } catch (failure: DatabaseOpenException) {
            Log.e("AppInit", failure) { "database open failed; waiting for start-up gate retry" }
            // A gate retry owns the next open attempt. Keep this one initializer sequence alive
            // so recovery resumes higher bands once, in order, without blocking the UI.
            opener.openState.first { it is DatabaseOpenState.Opened }
        }
    }

    private companion object {
        const val ORDER = 100
    }
}
