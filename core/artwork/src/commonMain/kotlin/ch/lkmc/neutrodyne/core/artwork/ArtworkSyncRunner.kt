// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.ArtworkCandidate
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** One bounded, restartable pass; all retry state lives in artwork rows, never work input. */
@SingleIn(AppScope::class)
@Inject
public class ArtworkSyncRunner internal constructor(
    private val db: NeutrodyneDatabase,
    private val store: DefaultArtworkStore,
    private val codec: ArtworkCodec,
    private val monograms: MonogramRenderer,
    private val loader: Provider<ImageLoader>,
    private val context: PlatformContext,
    private val scheduler: ArtworkSyncScheduler,
    private val clock: Clock,
    @param:Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val runs = Mutex()

    public suspend fun runOnce(deadline: Duration? = null): Unit =
        withContext(io) {
            runs.withLock {
                val end = deadline?.let { clock.elapsedRealtime() + it.inWholeMilliseconds }
                store.refreshIndex()
                val wanted =
                    db
                        .artworkDao()
                        .syncCandidates()
                        .sortedWith(
                            compareByDescending<ArtworkCandidate> { it.nowPlaying }
                                .thenBy { priority(it) }
                                .thenByDescending { it.latestEpisodeAt ?: Long.MIN_VALUE }
                                .thenBy { it.key },
                        ).distinctBy { it.key }
                        .mapNotNull { candidate ->
                            if (!ArtworkKeys.VALID.matches(candidate.key)) return@mapNotNull null
                            val descriptor = descriptor(candidate) ?: return@mapNotNull null
                            val row = db.artworkDao().byKey(candidate.key)
                            if (!needsWork(candidate.key, descriptor, row)) return@mapNotNull null
                            Work(candidate, descriptor, row)
                        }
                val producers = Semaphore(PRODUCERS)
                for (batch in wanted.chunked(BATCH_SIZE)) {
                    val remaining = end?.minus(clock.elapsedRealtime())
                    if (remaining != null && remaining <= 0) {
                        scheduler.enqueueNow()
                        return@withLock
                    }
                    val produce =
                        suspend {
                            coroutineScope {
                                batch
                                    .map { work ->
                                        async { producers.withPermit { produce(work) } }
                                    }.awaitAll()
                            }
                        }
                    val results = if (remaining == null) produce() else withTimeoutOrNull(remaining) { produce() }
                    if (results == null) {
                        scheduler.enqueueNow()
                        return@withLock
                    }
                    store.applyBatch(results, clock.now())
                    yield()
                }
                store.collectGarbage()
            }
        }

    private fun needsWork(
        key: String,
        descriptor: String,
        row: ArtworkEntity?,
    ): Boolean {
        if (row == null) return true
        if (backoffActive(row, clock.now())) return false
        return row.lastError != null || row.localPath == null || store.pinnedFile(key) == null ||
            row.url != descriptor ||
            (key.startsWith("u-") && (row.fetchedAt ?: Long.MIN_VALUE) < clock.now() - MONTH_MS)
    }

    private suspend fun produce(work: Work): ArtworkProduction {
        val candidate = work.candidate
        try {
            if (candidate.key.startsWith("m-")) {
                val spec = Monogram.spec(candidate.title)
                return ArtworkProduction(
                    candidate.key,
                    work.descriptor,
                    codec.encode(monograms.render(spec)),
                    monogramColor = spec.colors(MonogramMode.RASTER).first,
                )
            }
            val imageLoader = loader()
            val request =
                ImageRequest
                    .Builder(context)
                    .data(candidate.sourceUrl)
                    .size(MAX_SIDE)
                    .precision(Precision.INEXACT)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    // Retrying a too-small response must not re-read the same cached failure.
                    .diskCachePolicy(if (work.row?.fetchedAt != null) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                    .softwareArtwork()
                    .build()
            return when (val result = imageLoader.execute(request)) {
                is ErrorResult -> {
                    ArtworkProduction(
                        candidate.key,
                        work.descriptor,
                        error = artworkError(result.throwable),
                    )
                }

                is SuccessResult -> {
                    if (result.image.width < MIN_SIDE && result.image.height < MIN_SIDE) {
                        ArtworkProduction(candidate.key, work.descriptor, error = "TOO_SMALL")
                    } else {
                        ArtworkProduction(candidate.key, work.descriptor, codec.encode(result.image))
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ArtworkProduction(candidate.key, work.descriptor, error = artworkError(error))
        }
    }

    private fun descriptor(candidate: ArtworkCandidate): String? =
        when {
            candidate.key.startsWith("m-") -> {
                monogramDescriptor(Monogram.spec(candidate.title))
            }

            candidate.key.startsWith("u-") -> {
                candidate.sourceUrl?.takeIf {
                    it.startsWith("https://") || it.startsWith("http://")
                }
            }

            else -> {
                null
            } // Group raster production belongs to M10.
        }

    private fun priority(candidate: ArtworkCandidate): Int =
        when {
            candidate.kind == "PODCAST" && candidate.key.startsWith("u-") -> 0
            candidate.kind == "DOWNLOAD" -> 1
            else -> 2
        }

    private data class Work(
        val candidate: ArtworkCandidate,
        val descriptor: String,
        val row: ArtworkEntity?,
    )

    private companion object {
        const val PRODUCERS = 4
        const val BATCH_SIZE = 8
        const val MAX_SIDE = 1024
        const val MIN_SIDE = 128
        const val MONTH_MS = 30L * 24 * 60 * 60 * 1000
    }
}

internal data class ArtworkProduction(
    val key: String,
    val descriptor: String,
    val image: EncodedArtwork? = null,
    val error: String? = null,
    val monogramColor: Int? = null,
)

internal fun monogramDescriptor(spec: MonogramSpec): String = "nd:monogram:v1:${spec.initials}:${spec.hue}"

internal fun backoffActive(
    row: ArtworkEntity,
    now: Long,
): Boolean {
    val attempt = row.lastError?.substringAfterLast(':')?.toIntOrNull() ?: return false
    val fetched = row.fetchedAt ?: return false
    val hours =
        when (attempt) {
            1 -> 1
            2 -> 6
            3 -> 24
            else -> 7 * 24
        }
    return now < fetched + hours * 60 * 60 * 1000L
}

internal expect fun ImageRequest.Builder.softwareArtwork(): ImageRequest.Builder

internal expect fun artworkError(error: Throwable): String
