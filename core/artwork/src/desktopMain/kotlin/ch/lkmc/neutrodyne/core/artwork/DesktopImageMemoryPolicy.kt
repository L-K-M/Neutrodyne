// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Desktop memory policy (08 Coil ImageLoader): a fixed 96 MB — a wide window holds about 140
 * thumbnails at 384 px plus two 1024 px heroes. Tray trim via `memoryCache.trimToSize` is 11's.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopImageMemoryPolicy : ImageMemoryPolicy {
    override fun build(context: PlatformContext): MemoryCache = MemoryCache.Builder().maxSizeBytes(MEMORY_BYTES).build()

    override fun configure(builder: ImageLoader.Builder) {}

    private companion object {
        const val MEMORY_BYTES = 96L * 1024 * 1024
    }
}
