// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.io.IOException
import java.nio.file.Path

/** Resolution belongs to the application adapter; the engine never queries repositories. */
fun interface DesktopSourceResolver {
    suspend fun resolve(item: EngineItem, attempt: Int): ResolvedSource
}

sealed interface ResolvedSource {
    data class File(val path: Path) : ResolvedSource
    data class Http(val url: String, val headers: Map<String, String>, val cacheKey: String) : ResolvedSource
    data class YouTube(
        val url: String,
        val formatId: String,
        val cacheKey: String,
        val contentLength: Long?,
        val availableAtMs: Long?,
    ) : ResolvedSource
}

class ContentChangedException(message: String) : IOException(message)

class HttpSourceException(val status: Int) : IOException("Media HTTP status $status")
