// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache

/**
 * The memory cache sizing of 08's loader table: Android a percent of the app's memory class
 * (shrinking while backgrounded), the desktop a fixed MB budget — common code cannot express
 * either, so the platforms bind this.
 */
public interface ImageMemoryPolicy {
    /** The memory cache to install on the loader. */
    public fun build(context: PlatformContext): MemoryCache

    /** Platform extras on the builder (Android's background trim); no-op where absent. */
    public fun configure(builder: ImageLoader.Builder)
}
