// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File

/** The `u-…` key a fetch URL pins under (production SHA-1, not a look-alike). */
internal fun uKey(url: String): String = ArtworkKeys.forUrl(url)

/** `filesDir/artwork`; the `DefaultArtworkStore` root for [context]. */
internal fun artworkDir(context: Context): File = File(context.filesDir, "artwork")

/** Opaque JPEG of [argb]; deterministic; the same call returns the same bytes. */
internal fun jpegBytes(
    width: Int,
    height: Int,
    argb: Int,
): ByteArray = encodeBitmap(width, height, Bitmap.CompressFormat.JPEG, quality = 90) { drawColor(argb) }

/** RGBA PNG: fully transparent except an opaque left half in [argb]. */
internal fun alphaPngBytes(
    width: Int,
    height: Int,
    argb: Int,
): ByteArray =
    encodeBitmap(width, height, Bitmap.CompressFormat.PNG, quality = 100) {
        drawColor(Color.TRANSPARENT)
        drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = argb })
    }

/** Encoded with the platform codec path so decode/encode stay real under `GraphicsMode.NATIVE`. */
private inline fun encodeBitmap(
    width: Int,
    height: Int,
    format: Bitmap.CompressFormat,
    quality: Int,
    draw: Canvas.() -> Unit,
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(bitmap).draw()
        if (format == Bitmap.CompressFormat.JPEG) bitmap.setHasAlpha(false)
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, quality, out)) { "Bitmap.compress($format) failed" }
        return out.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/** A cacheable image response; `Cache-Control` makes Coil persist it, so WRITE_ONLY is provable. */
internal fun imageResponse(
    bytes: ByteArray,
    mime: String = "image/jpeg",
    code: Int = 200,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", mime)
        .addHeader("Cache-Control", "public, max-age=31536000")
        .body(Buffer().write(bytes))
        .build()

internal fun notFoundResponse(): MockResponse = MockResponse.Builder().code(404).build()

/** A subscribed podcast whose `artworkKey`/`artworkUrl` feed `syncCandidates`. */
internal suspend fun NeutrodyneDatabase.seedArtworkPodcast(
    key: String,
    url: String?,
    title: String = "Synthetic Show",
    latestAt: Long? = null,
): Long =
    podcastDao().insertPodcast(
        // `latestAt` is named unlike the entity field on purpose: the block receiver's member
        // would shadow a `latestEpisodeAt` parameter here.
        podcastEntity(feedKey = "feed-$key", title = title, artworkKey = key) {
            copy(artworkUrl = url, latestEpisodeAt = latestAt)
        },
    )

/** A `COMPLETED` download whose episode art feeds `syncCandidates` as kind `DOWNLOAD`. */
internal suspend fun NeutrodyneDatabase.seedDownloadedArtwork(
    podcastId: Long,
    key: String,
    artUrl: String,
) {
    val episodeId =
        ingestDao()
            .insertEpisodes(
                listOf(
                    episodeEntity(podcastId = podcastId) {
                        copy(imageUrl = artUrl, artworkKey = key)
                    },
                ),
            ).single()
    downloadDao().insert(downloadEntity(episodeId = episodeId))
}

/** Exercise the same IMAGE policy as the singleton loader, including the network island. */
internal fun artworkTestImageClient(): OkHttpClient {
    val platform =
        object : PlatformInfo {
            override val kind = PlatformKind.ANDROID
            override val userAgentPlatform = "Android 28; artwork test"
            override val androidSdkInt = 28
            override val regionCode = "DE"
        }
    val ua = UserAgentProvider(platform, "0.1.0", "https://synthetic.test/repository")
    return NetworkClients(
        CoreClients(
            UserAgentInterceptor(ua),
            LocalNetworkGuard(LocalNetworkAccess(), platform),
            DnsFamilyHints(),
            emptySet(),
        ),
        AuthInterceptor { CredentialLookup.None },
    ).image
}
