// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.ColorImage
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class TinyImageInterceptorTest {
    @Test fun tinyCoverFails() = runTest { assertIs<ErrorResult>(intercept(100, 100, 384, cover = true)) }

    @Test fun plainUrlIsUnchanged() = runTest { assertIs<SuccessResult>(intercept(100, 100, 384, cover = false)) }

    @Test fun deliberateThumbnailIsAccepted() =
        runTest {
            assertIs<SuccessResult>(
                intercept(100, 100, 48, cover = true),
            )
        }

    @Test fun minimumBoundaryIsAccepted() = runTest { assertIs<SuccessResult>(intercept(128, 100, 384, cover = true)) }

    @Test fun oneLongAxisIsAccepted() = runTest { assertIs<SuccessResult>(intercept(144, 50, 384, cover = true)) }

    @Test fun existingErrorIsPreserved() =
        runTest {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(ArtworkRef(KEY, URL, 0)).build()
            val error = ErrorResult(null, request, IllegalStateException("decode failed"))
            assertSame(error, TinyImageInterceptor().intercept(Chain(request, Size(384, 384), error)))
        }

    private suspend fun intercept(
        width: Int,
        height: Int,
        requested: Int,
        cover: Boolean,
    ): ImageResult {
        val data = if (cover) ArtworkRef(KEY, URL, 0) else URL
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(data).build()
        val result = SuccessResult(ColorImage(0xff123456.toInt(), width, height), request, DataSource.NETWORK)
        return TinyImageInterceptor().intercept(Chain(request, Size(requested, requested), result))
    }

    private class Chain(
        override val request: ImageRequest,
        override val size: Size,
        private val result: ImageResult,
    ) : Interceptor.Chain {
        override fun withRequest(request: ImageRequest): Interceptor.Chain = Chain(request, size, result)

        override fun withSize(size: Size): Interceptor.Chain = Chain(request, size, result)

        override suspend fun proceed(): ImageResult = result
    }

    private companion object {
        val KEY = "u-" + "a".repeat(40)
        const val URL = "https://synthetic.test/cover.png"
    }
}
