// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.StoragePaths
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import dev.zacsweers.metro.Inject
import okio.Path.Companion.toPath

/**
 * Builds the process-wide Coil [ImageLoader] (08 Coil ImageLoader, D58): installed once per
 * process with `SingletonImageLoader.setSafe` by `NeutrodyneApplication` and the desktop's main.
 * The network fetcher and memory sizing are platform contributions; the disk cache is
 * `<cache>/coil` at 256 MB on both. Coil ignores `Cache-Control` — freshness is the store's
 * monthly refresh, not HTTP caching. `YouTubeThumbnailInterceptor` joins at M8,
 * `TinyImageInterceptor` at M4.
 */
@Inject
public class NeutrodyneImageLoaderFactory(
    private val network: ImageNetworkComponent,
    private val memory: ImageMemoryPolicy,
    private val store: ArtworkStore,
    private val paths: StoragePaths,
) : SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                network.install(this)
                add(ArtworkRefMapper(store))
            }.memoryCache { memory.build(context) }
            .apply { memory.configure(this) }
            .diskCache {
                DiskCache
                    .Builder()
                    .directory(paths.cacheDir.toPath() / DISK_CACHE_DIR)
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }.build()

    private companion object {
        const val DISK_CACHE_DIR = "coil"
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
