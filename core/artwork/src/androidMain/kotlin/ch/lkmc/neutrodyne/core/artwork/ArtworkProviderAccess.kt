// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.os.ParcelFileDescriptor
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem

/** Local-only entry point. It never resolves Coil or the artwork work scheduler. */
@Inject
public class ArtworkProviderAccess internal constructor(
    private val opener: DatabaseOpener,
    private val store: Provider<DefaultArtworkStore>,
    private val codec: ArtworkCodec,
    private val monograms: MonogramRenderer,
    @param:Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    internal suspend fun open(key: String): ParcelFileDescriptor? =
        withContext(io) {
            withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                opener.awaitOpen()
                store().openLocal(
                    key,
                    render = { title -> codec.encode(monograms.render(Monogram.spec(title)), ArtworkEncoding.PNG) },
                    open = { path -> ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY) },
                )
            }
        }

    internal suspend fun metadata(key: String): ArtworkFileMetadata? =
        withContext(io) {
            withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                opener.awaitOpen()
                val artwork = store()
                artwork.refreshIndex()
                val path = artwork.pinnedFile(key) ?: return@withTimeoutOrNull null
                ArtworkFileMetadata(
                    path.name,
                    FileSystem.SYSTEM.metadataOrNull(path)?.size ?: 0,
                    if (path.name.endsWith(".png")) "image/png" else "image/jpeg",
                )
            }
        }

    private companion object {
        const val OPEN_TIMEOUT_MS = 2_000L
    }
}

internal data class ArtworkFileMetadata(
    val name: String,
    val size: Long,
    val mime: String,
)
