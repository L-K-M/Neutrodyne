// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** Public read-only artwork, available before Application.onCreate without eager graph access. */
public class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun access(): ArtworkProviderAccess =
        (
            (
                requireNotNull(
                    context,
                ).applicationContext as GraphHolder
            ).graph as ArtworkProviderGraph
        ).artworkProviderAccess()

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val key = validKey(uri) ?: throw FileNotFoundException("Invalid artwork key")
        val local =
            runCatching { runBlocking { access().open(key) } }
                .onFailure { Log.w("ArtworkProvider", it) { "local artwork unavailable" } }
                .getOrNull()
        return local ?: placeholder()
    }

    override fun getType(uri: Uri): String {
        val key = validKey(uri) ?: return "image/jpeg"
        return metadata(key)?.mime ?: "image/jpeg"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val key = validKey(uri) ?: return null
        val metadata = metadata(key)
        val columns =
            (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
                .toTypedArray()
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> metadata?.name ?: "$key.jpg"
                        else -> metadata?.size ?: 0L
                    }
                },
            )
        }
    }

    private fun metadata(key: String): ArtworkFileMetadata? =
        runCatching { runBlocking { access().metadata(key) } }.getOrNull()

    private fun validKey(uri: Uri): String? {
        val expected = "${requireNotNull(context).packageName}.artwork"
        if (uri.scheme != "content" || uri.authority != expected) return null
        val segments = uri.pathSegments
        if (segments.size != 1) return null
        return segments.single().takeIf { ArtworkKeys.VALID.matches(it) }
    }

    /** Neutral artwork also works when opening the database timed out or failed. */
    private fun placeholder(): ParcelFileDescriptor =
        synchronized(PLACEHOLDER_LOCK) {
            val root = File(requireNotNull(context).filesDir, "artwork").apply { mkdirs() }
            val image = File(root, "nd-placeholder.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(PLACEHOLDER_SIDE, PLACEHOLDER_SIDE, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(NEUTRAL_BACKGROUND)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = NEUTRAL_FOREGROUND
                            strokeWidth = 16f
                        }
                    // A neutral record symbol, independent of app resources and theme.
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(256f, 256f, 136f, paint)
                    canvas.drawCircle(256f, 256f, 24f, paint)
                    val temporary = File(root, "nd-placeholder.tmp")
                    FileOutputStream(temporary).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        stream.fd.sync()
                    }
                    check(temporary.renameTo(image)) { "Cannot publish artwork placeholder" }
                } finally {
                    bitmap.recycle()
                }
            }
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
        }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri = throw UnsupportedOperationException("Read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle = throw UnsupportedOperationException("Read-only")

    private companion object {
        val PLACEHOLDER_LOCK = Any()
        const val PLACEHOLDER_SIDE = 512
        const val NEUTRAL_BACKGROUND = 0xff45494c.toInt()
        const val NEUTRAL_FOREGROUND = 0xffd4d8db.toInt()
    }
}
