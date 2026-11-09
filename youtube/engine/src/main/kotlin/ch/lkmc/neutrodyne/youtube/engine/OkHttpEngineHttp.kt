// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream

/**
 * `EngineHttp` on 01's API and DOWNLOAD clients. Both are re-derived with
 * `followSslRedirects(false)` so no update request ever hops https → http; redirect targets are
 * not pinned (GitHub may move its asset host), checks 7–9 authenticate the bytes instead.
 */
class OkHttpEngineHttp(
    api: OkHttpClient,
    download: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Plain HTTP exists for the loopback fixture server in tests; production never sets it.
    private val allowPlainHttp: Boolean = false,
) : EngineHttp {
    private val api = api.newBuilder().followSslRedirects(false).build()
    private val download = download.newBuilder().followSslRedirects(false).build()
    private val noFollow = api.newBuilder().followRedirects(false).build()

    override suspend fun get(
        url: String,
        maxBytes: Long,
    ): ByteArray = fetch(api, url, maxBytes)

    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): ByteArray = fetch(download, url, maxBytes)

    override suspend fun redirectLocation(url: String): String? =
        withContext(io) {
            val request = Request.Builder().url(requireHttps(url)).build()
            noFollow.newCall(request).execute().use { response ->
                response.body.bytes()
                if (response.isRedirect) response.header("Location") else null
            }
        }

    private suspend fun fetch(
        client: OkHttpClient,
        url: String,
        maxBytes: Long,
    ): ByteArray =
        withContext(io) {
            val request = Request.Builder().url(requireHttps(url)).build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) throw EngineHttpException(response.code)
                readCapped(response, maxBytes)
            }
        }

    private fun readCapped(
        response: Response,
        maxBytes: Long,
    ): ByteArray {
        val stream = response.body.byteStream()
        stream.use { return it.readCapped(maxBytes) }
    }

    private fun requireHttps(url: String): String {
        if (allowPlainHttp) return url
        require(url.startsWith("https://")) { "Engine update URLs are HTTPS only" }
        return url
    }
}

/** A body beyond its declared cap; the updater maps this to [EngineRejectReason.SIZE_CAP]. */
class EngineSizeCapException : Exception("Engine download size cap") {
    override fun toString(): String = "EngineSizeCapException"
}

internal fun InputStream.readCapped(maxBytes: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw EngineSizeCapException()
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
