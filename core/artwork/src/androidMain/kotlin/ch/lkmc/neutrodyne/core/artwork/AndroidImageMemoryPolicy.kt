// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import coil3.memoryCacheMaxSizePercentWhileInBackground
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android memory policy (08 Coil ImageLoader): 20 % of the app's memory class, trimmed to a
 * quarter of that while backgrounded — an audio app spends hours in the background (01 P18).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidImageMemoryPolicy : ImageMemoryPolicy {
    override fun build(context: PlatformContext): MemoryCache =
        MemoryCache.Builder().maxSizePercent(context, MEMORY_PERCENT).build()

    override fun configure(builder: ImageLoader.Builder) {
        builder.memoryCacheMaxSizePercentWhileInBackground(BACKGROUND_PERCENT)
    }

    private companion object {
        const val MEMORY_PERCENT = 0.20
        const val BACKGROUND_PERCENT = 0.25
    }
}
