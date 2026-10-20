// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.net.Uri
import androidx.core.net.toUri
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/** W7 indexes each window before submitting it; metadata refreshes do not replace existing pins. */
@SingleIn(AppScope::class)
internal class EpisodeSourceIndex internal constructor(
    private val fallback: suspend (Long) -> PlaybackMediaInfo?,
) {
    @Inject
    constructor(
        opener: DatabaseOpener,
        lookup: Provider<PlaybackMediaLookup>,
        @Dispatcher(NeutrodyneDispatchers.IO) io: CoroutineDispatcher,
    ) : this({ opener.awaitOpen() }, lookup, io)

    internal constructor(
        awaitOpen: suspend () -> Unit,
        lookup: Provider<PlaybackMediaLookup>,
        io: CoroutineDispatcher,
    ) : this({ id ->
        withContext(io) {
            awaitOpen()
            lookup().get(id)
        }
    })

    private val sources = ConcurrentHashMap<Long, PlaybackMediaInfo>()

    fun putAll(items: Collection<PlaybackMediaInfo>) {
        items.forEach { sources[it.episodeId] = it }
    }

    fun remove(episodeId: Long) {
        sources.remove(episodeId)
    }

    operator fun get(episodeId: Long): PlaybackMediaInfo? = sources[episodeId]

    /** Loader thread only. The bounded miss path dereferences DB consumers after awaitOpen. */
    internal fun getOrLoad(episodeId: Long): PlaybackMediaInfo =
        sources[episodeId] ?: try {
            val item =
                runBlocking { withTimeout(LOOKUP_TIMEOUT_MS) { fallback(episodeId) } }
                    ?: throw EpisodeGoneException(episodeId)
            sources.putIfAbsent(episodeId, item) ?: item
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Episode lookup interrupted").also { it.initCause(e) }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Episode lookup timed out", e)
        }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 5_000L
    }
}

/** Accept only the canonical identity, never arbitrary authorities or encoded path aliases. */
internal fun episodeId(uri: Uri): Long {
    val id = uri.path?.removePrefix("/")?.toLongOrNull()
    if (id == null || id <= 0 || uri.toString() != episodeUri(id).toString()) {
        throw NoMediaException()
    }
    return id
}

internal fun episodeUri(id: Long): Uri = "neutrodyne://episode/$id".toUri()

internal class EpisodeGoneException(
    val episodeId: Long,
) : IOException("Episode $episodeId is no longer available")

internal class NoMediaException : IOException("Episode has no supported media source")

internal class ContentChangedException : IOException("Media content changed during playback")
