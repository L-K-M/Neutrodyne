// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.model.MosaicSnapshot
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import coil3.BitmapImage
import coil3.asImage
import dev.zacsweers.metro.Inject
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/** Local files only; each owned bitmap is released before the next cell is decoded. */
@Inject
internal class MosaicRenderer(
    private val store: DefaultArtworkStore,
    private val monograms: MonogramRenderer,
    private val codec: ArtworkCodec,
) {
    fun render(snapshot: MosaicSnapshot): EncodedArtwork {
        val output = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            // System files have no live theme; use the fixed light container tone like RASTER art.
            canvas.drawColor(ArtColors.tone(snapshot.groupColorArgb ?: DEFAULT_SEED, CONTAINER_TONE))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for ((index, member) in snapshot.members.withIndex()) {
                val cell = store.pinnedFile(member.artwork.key)?.let(::decode)
                val fallback = if (cell == null) monogram(member.displayTitle) else null
                val bitmap = cell ?: fallback?.bitmap ?: continue
                try {
                    val edge = minOf(bitmap.width, bitmap.height)
                    val left = (bitmap.width - edge) / 2
                    val top = (bitmap.height - edge) / 2
                    val x = (index % 2) * (CELL + GAP)
                    val y = (index / 2) * (CELL + GAP)
                    canvas.drawBitmap(
                        bitmap,
                        Rect(left, top, left + edge, top + edge),
                        Rect(x, y, x + CELL, y + CELL),
                        paint,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
            output.setHasAlpha(false)
            return codec.encode(output.asImage())
        } finally {
            output.recycle()
        }
    }

    private fun monogram(title: String): BitmapImage? =
        try {
            monograms.render(Monogram.spec(title), CELL) as? BitmapImage
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null // The group container is the final fallback when a monogram cannot be rendered.
        }

    private fun decode(path: Path): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path.toString(), bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CELL) sample *= 2
            BitmapFactory.decodeFile(path.toString(), BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null // A missing, corrupt or concurrently replaced member falls back locally.
        }
    }

    private companion object {
        const val SIDE = 512
        const val GAP = 4
        const val CELL = 254
        const val DEFAULT_SEED = 0xFFFFC107.toInt()
        const val CONTAINER_TONE = 90.0
    }
}
