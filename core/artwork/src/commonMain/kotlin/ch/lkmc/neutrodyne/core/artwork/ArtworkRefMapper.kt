// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.map.Mapper
import coil3.request.Options
import dev.zacsweers.metro.Inject
import okio.Path

/**
 * Coil mapper for [ArtworkRef] (08 Coil ImageLoader): the pinned file first — its in-memory
 * index lookup does no disk I/O on the main thread — else the URL. `m-` keys and provider
 * `{key}.fallback.png` index entries map to the URL path (never the fallback raster): callers
 * render the monogram painter for `m-` rows, and an in-app failure leaves the URL retryable.
 */
@Inject
public class ArtworkRefMapper(
    private val store: ArtworkStore,
) : Mapper<ArtworkRef, Any> {
    override fun map(
        data: ArtworkRef,
        options: Options,
    ): Any? {
        // Monogram rasters are system-surface files; in-app rows paint live (08).
        if (data.key.startsWith(MONOGRAM_PREFIX)) return null
        val pinned = store.pinnedPath(data.key)
        return if (pinned != null && !pinned.name.endsWith(FALLBACK_SUFFIX)) pinned else data.url
    }

    private companion object {
        const val MONOGRAM_PREFIX = "m-"
        const val FALLBACK_SUFFIX = ".fallback.png"
    }
}
