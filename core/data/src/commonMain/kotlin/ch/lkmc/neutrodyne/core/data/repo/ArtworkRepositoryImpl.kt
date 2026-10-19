// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.repo

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ArtworkRepository
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ArtworkRepositoryImpl(
    private val db: NeutrodyneDatabase,
) : ArtworkRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeColors(
        key: String,
        fallbackPodcastId: Long?,
    ): Flow<ArtworkColors?> =
        db
            .artworkDao()
            .observe(key)
            .map { it.completedColors() }
            .distinctUntilChanged()
            .flatMapLatest { primary ->
                if (primary != null || fallbackPodcastId == null) return@flatMapLatest flowOf(primary)

                db
                    .podcastDao()
                    .observeById(
                        fallbackPodcastId,
                    ).map { it?.artworkKey }
                    .distinctUntilChanged()
                    .flatMapLatest { fallback ->
                        if (fallback == null || fallback == key) return@flatMapLatest flowOf(null)

                        // Emit absence while switching so a buffered old podcast result cannot tint its successor.
                        db
                            .artworkDao()
                            .observe(fallback)
                            .map { it.completedColors() }
                            .onStart { emit(null) }
                    }.buffer(0)
            }.buffer(0)
            .onStart { emit(null) }
            .distinctUntilChanged()
}

private fun ArtworkEntity?.completedColors(): ArtworkColors? {
    if (this == null || localPath == null || avgArgb == null) return null
    return ArtworkColors(key, version, seedArgb, avgArgb)
}
