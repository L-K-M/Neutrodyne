// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension

/** Reject a tiny original cover, while keeping deliberate thumbnail requests and plain URLs valid. */
public class TinyImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (chain.request.data !is ArtworkRef || result !is SuccessResult) return result
        val width = result.image.width
        val height = result.image.height
        val requestedWidth = (chain.size.width as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
        val requestedHeight = (chain.size.height as? Dimension.Pixels)?.px ?: Int.MAX_VALUE
        if (maxOf(width, height) >= MIN_SIDE || width >= requestedWidth || height >= requestedHeight) return result
        return ErrorResult(image = null, request = result.request, throwable = TinyArtworkException())
    }

    private class TinyArtworkException : IllegalArgumentException("Artwork is smaller than 128 pixels")

    private companion object {
        const val MIN_SIDE = 128
    }
}
