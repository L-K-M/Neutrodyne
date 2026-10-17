// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

@ContributesIntoSet(AppScope::class)
@Inject
internal class NewEpisodeChannelsInitializer(
    private val application: Application,
) : AppInitializer {
    override val order: Int = 10

    override suspend fun run() = NewEpisodeChannels.ensureChannels(application)
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class GroupChannelsInitializer(
    // Initializers are constructed before band 100 opens the database (01 DI rule 7).
    private val channelSync: () -> GroupChannelSync,
) : AppInitializer {
    override val order: Int = 140

    override suspend fun run() = channelSync().sync()
}
