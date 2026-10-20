// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.FailReason
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import okio.Buffer

/** FEED deliberately disables automatic redirects; never send the consent cookie to other hosts. */
internal class YouTubeHttp(
    private val clients: NeutrodyneHttpClients,
    private val appLocale: () -> String,
) {
    data class Reply<T>(
        val code: Int,
        val body: T? = null,
        val consent: Boolean = false,
    )

    suspend fun <T> get(
        url: String,
        kind: HttpClientKind,
        accept: String,
        read: suspend (ByteReadChannel, String) -> T,
    ): Reply<T> {
        var current = url
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            if (!visited.add(current)) return Reply(LOOP_STATUS)
            var next: String? = null
            val request =
                clients.client(kind).prepareGet(current) {
                    header(HttpHeaders.Accept, accept)
                    header(HttpHeaders.AcceptLanguage, "${appLocale()}, en;q=0.5")
                    if (kind == HttpClientKind.FEED) header(HttpHeaders.Cookie, "SOCS=CAE=")
                }
            val result =
                request.execute { response ->
                    val channel = response.bodyAsChannel()
                    try {
                        val finalUrl = response.call.request.url
                        if (finalUrl.host.equals(CONSENT_HOST, true)) {
                            return@execute Reply<T>(response.status.value, consent = true)
                        }
                        val code = response.status.value
                        if (code in REDIRECT_CODES) {
                            val location = response.headers[HttpHeaders.Location] ?: return@execute Reply<T>(code)
                            val target =
                                runCatching { URLBuilder(current).takeFrom(location).build() }.getOrNull()
                                    ?: return@execute Reply<T>(code)
                            if (target.host.equals(CONSENT_HOST, true)) {
                                return@execute Reply<T>(code, consent = true)
                            }
                            if (!safeRedirect(target)) return@execute Reply<T>(code)
                            next = target.toString()
                            return@execute Reply<T>(code)
                        }
                        Reply(code, if (code == 200) read(channel, finalUrl.toString()) else null)
                    } finally {
                        // Release unread bytes immediately; execute also closes the response.
                        channel.cancel(null)
                    }
                }
            current = next ?: return result
        }
        return Reply(LOOP_STATUS)
    }

    private fun safeRedirect(url: Url): Boolean =
        url.protocol.name == "https" && url.host.lowercase() in PAGE_HOSTS &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.port == 443

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val LOOP_STATUS = 508
        private const val CONSENT_HOST = "consent.youtube.com"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val PAGE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
        const val FEED_CAP = 2 * 1024 * 1024

        suspend fun bounded(
            channel: ByteReadChannel,
            cap: Int,
        ): ByteArray? {
            val buffer = Buffer()
            val chunk = ByteArray(8192)
            while (buffer.size <= cap) {
                val count = channel.readAvailable(chunk, 0, minOf(chunk.size, cap + 1 - buffer.size.toInt()))
                if (count < 0) return buffer.readByteArray()
                if (count == 0) {
                    channel.awaitContent()
                    continue
                }
                buffer.write(chunk, 0, count)
            }
            return null
        }
    }
}

internal fun failed(
    reason: FailReason,
    cause: Throwable? = null,
) = ChannelResolution.Failed(reason == FailReason.NETWORK || reason == FailReason.RATE_LIMITED, reason, cause)

internal fun YouTubeHttp.Reply<*>.failure(): ChannelResolution =
    when {
        consent -> failed(FailReason.CONSENT_WALL)
        code == 404 || code == 410 -> ChannelResolution.NotFound
        code == 429 -> failed(FailReason.RATE_LIMITED)
        code >= 500 -> failed(FailReason.NETWORK)
        else -> failed(FailReason.PAGE_UNREADABLE)
    }
