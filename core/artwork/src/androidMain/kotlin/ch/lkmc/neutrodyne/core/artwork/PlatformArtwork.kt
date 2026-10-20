// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import coil3.network.HttpException
import coil3.request.ImageRequest
import coil3.request.allowHardware
import java.io.IOException

internal actual fun ImageRequest.Builder.softwareArtwork(): ImageRequest.Builder = allowHardware(false)

internal actual fun artworkError(error: Throwable): String =
    when (error) {
        is HttpException -> "HTTP_${error.response.code}"
        is IOException -> "NETWORK"
        else -> "DECODE"
    }
