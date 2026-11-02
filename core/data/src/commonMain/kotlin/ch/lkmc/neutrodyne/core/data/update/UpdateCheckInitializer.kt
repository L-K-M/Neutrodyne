// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/** Defer graph construction until settings restoration and the database gate have completed. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class UpdateCheckInitializer(
    private val executor: Provider<UpdateCheckExecutor>,
) : AppInitializer {
    override val order: Int = 200

    override suspend fun run() = executor().initialize()
}
