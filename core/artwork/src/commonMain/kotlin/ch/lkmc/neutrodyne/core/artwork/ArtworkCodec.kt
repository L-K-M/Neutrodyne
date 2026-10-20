// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import coil3.Image
import okio.ByteString

/** Platform encoding, with aspect-preserving scaling to at most 1024 pixels per axis. */
public interface ArtworkCodec {
    public fun encode(
        image: Image,
        encoding: ArtworkEncoding = ArtworkEncoding.AUTO,
    ): EncodedArtwork
}

public enum class ArtworkEncoding { AUTO, PNG }

public data class EncodedArtwork(
    val bytes: ByteString,
    val width: Int,
    val height: Int,
    val extension: String,
)

/** System surfaces use the same initials and RASTER colours as the in-app painter. */
public interface MonogramRenderer {
    public fun render(
        spec: MonogramSpec,
        size: Int = 512,
    ): Image
}
