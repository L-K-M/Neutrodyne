// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import dev.zacsweers.metro.Inject
import java.io.IOException
import java.net.HttpURLConnection

/** Validates headers before CacheDataSource can receive or commit any new rendition's bytes. */
internal class GuardedHttpDataSource internal constructor(
    private val upstream: HttpDataSource,
    private val resolver: EpisodeResolver,
) : HttpDataSource by upstream {
    private var requestedUri: android.net.Uri? = null

    // CacheDataSource persists getUri() redirects. Signed final URLs belong only in the pin.
    override fun getUri(): android.net.Uri? = requestedUri

    override fun close() {
        requestedUri = null
        upstream.close()
    }

    override fun open(dataSpec: DataSpec): Long {
        requestedUri = dataSpec.uri
        val pin = resolver.remotePin(dataSpec.key)
        if (pin?.contentChanged == true) throw ContentChangedException()
        try {
            val length = resolver.openYouTube(dataSpec, upstream) ?: upstream.open(dataSpec)
            if (pin != null) validate(pin)
            return length
        } catch (e: IOException) {
            if (pin != null && e is HttpDataSource.InvalidResponseCodeException &&
                e.responseCode in STALE_FINAL_CODES && dataSpec.uri.toString() == pin.finalUrl
            ) {
                pin.finalUrl = null
            }
            try {
                upstream.close()
            } catch (close: IOException) {
                e.addSuppressed(close)
            }
            // URLs/headers are never logged here. The shared Log entry point redacts cause chains.
            throw e
        }
    }

    private fun validate(pin: Pin.Remote) {
        val headers = upstream.responseHeaders

        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, true) }
                ?.value
                ?.firstOrNull()

        val total =
            header("Content-Range")?.let {
                CONTENT_RANGE
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            }
                ?: if (upstream.responseCode == HttpURLConnection.HTTP_OK) {
                    header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                } else {
                    null
                }
        synchronized(pin) {
            val previous = pin.totalLength
            if (pin.contentChanged || (previous != null && total != null && previous != total)) {
                pin.contentChanged = true
                throw ContentChangedException()
            }
            if (total != null) pin.totalLength = total
            if (pin.finalUrl == null) pin.finalUrl = upstream.uri?.toString()
        }
    }

    class Factory
        @Inject
        constructor(
            clients: NetworkClients,
            private val resolver: EpisodeResolver,
        ) : DataSource.Factory {
            private val upstream = OkHttpDataSource.Factory(clients[HttpClientKind.MEDIA])

            override fun createDataSource(): GuardedHttpDataSource =
                GuardedHttpDataSource(upstream.createDataSource(), resolver)
        }

    private companion object {
        val CONTENT_RANGE = Regex("bytes (?:[0-9]+-[0-9]+|\\*)/([0-9]+)")
        val STALE_FINAL_CODES =
            setOf(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                HttpURLConnection.HTTP_NOT_FOUND,
                HttpURLConnection.HTTP_GONE,
            )
    }
}
