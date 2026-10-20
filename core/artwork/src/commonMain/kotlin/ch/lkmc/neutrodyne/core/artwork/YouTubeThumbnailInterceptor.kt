// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.youtube.api.ThumbVariant
import ch.lkmc.neutrodyne.youtube.api.YouTubeThumbnails
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.size.Dimension

/** Candidate disk keys cache missing variants; explicit tier keys still serve hero placeholders. */
public class YouTubeThumbnailInterceptor(
    private val store: ArtworkStore,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url =
            when (val data = request.data) {
                is ArtworkRef -> {
                    // The mapper owns pinned/fallback-file semantics. Do not replace its data or keys.
                    if (store.isPinned(data.key)) return chain.proceed()
                    data.url
                }

                is String -> {
                    data
                }

                else -> {
                    null
                }
            } ?: return chain.proceed()
        val videoId =
            YouTubeThumbnails.THUMB
                .matchEntire(url)
                ?.groupValues
                ?.get(1) ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
        val candidates = YouTubeThumbnails.chainFor(width) + ThumbVariant.HQ
        var last: ImageResult? = null
        for (variant in candidates) {
            val candidateUrl = YouTubeThumbnails.video(videoId, variant)
            val candidate =
                request
                    .newBuilder()
                    .data((request.data as? ArtworkRef)?.copy(url = candidateUrl) ?: candidateUrl)
                    .diskCacheKey(candidateUrl)
                    .apply {
                        if (variant == ThumbVariant.HQ) {
                            transformations(listOf(LetterboxCrop169()) + request.transformations)
                        }
                    }.build()
            val result = chain.withRequest(candidate).proceed()
            if (result is SuccessResult) return result
            last = result
        }
        return checkNotNull(last)
    }
}
