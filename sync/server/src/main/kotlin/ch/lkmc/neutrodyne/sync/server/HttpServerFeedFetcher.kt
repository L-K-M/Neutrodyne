// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.identity.LocalDestination
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.server.ServerFeedFetcher.ServerFeedFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** The same checked DNS answers are used to connect. Literal hosts and each redirect are checked separately. */
internal class HttpServerFeedFetcher(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(FeedDns(Dns.SYSTEM))
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build(),
) : ServerFeedFetcher {
    override suspend fun fetch(
        url: String,
        authorization: String?,
        etag: String?,
        lastModified: String?,
    ): ServerFeedFetch? =
        withContext(Dispatchers.IO) {
            val origin = url.toHttpUrlOrNull() ?: return@withContext null
            var target = origin
            var auth = authorization
            var permanentUrl: String? = null
            var permanentChain = true
            try {
                repeat(MAX_REDIRECTS + 1) {
                    ensureActive()
                    if (!allowed(target) || (origin.isHttps && !target.isHttps)) return@withContext null
                    val request =
                        Request
                            .Builder()
                            .url(target)
                            .apply {
                                header("User-Agent", "Neutrodyne-Sync")
                                auth?.let { if (target.isHttps) header("Authorization", it) }
                                if (target == origin) {
                                    etag?.let { header("If-None-Match", it) }
                                    lastModified?.let { header("If-Modified-Since", it) }
                                }
                            }.build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            304 -> {
                                return@withContext if (target == origin &&
                                    (etag != null || lastModified != null)
                                ) {
                                    ServerFeedFetch.Unchanged
                                } else {
                                    null
                                }
                            }

                            200 -> {
                                return@withContext readDocument(target, response, target == origin, permanentUrl)
                            }

                            301, 302, 303, 307, 308 -> {
                                val next = response.header("Location")?.let(target::resolve) ?: return@withContext null
                                if (next.scheme != target.scheme || next.host != target.host ||
                                    next.port != target.port
                                ) {
                                    auth = null
                                }
                                if (target.isHttps && !next.isHttps) return@withContext null
                                permanentChain = permanentChain && response.code in setOf(301, 308)
                                if (permanentChain) permanentUrl = next.toString()
                                target = next
                            }

                            else -> {
                                return@withContext null
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // A failed poll leaves the subscription eligible for direct refresh on the device.
            } catch (_: IllegalArgumentException) {
                // Invalid header values from stored credentials or a previous publisher response.
            }
            null
        }

    private fun allowed(url: HttpUrl): Boolean =
        url.username.isEmpty() && url.password.isEmpty() && !LocalDestination.isLocal(url.toString())

    private fun readDocument(
        url: HttpUrl,
        response: Response,
        retainValidators: Boolean,
        permanentUrl: String?,
    ): ServerFeedFetch.Changed? {
        val body = response.body
        if (body.contentLength() > MAX_BODY_BYTES) return null
        val charset = body.contentType()?.parameter("charset")?.takeIf { it.length <= 64 }
        val bytes = body.byteStream().readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES || !hasFeedRoot(bytes, charset)) return null
        return ServerFeedFetch.Changed(
            bytes,
            url.toString(),
            response.header("ETag").takeIf { retainValidators },
            response.header("Last-Modified").takeIf { retainValidators },
            permanentUrl,
            charset,
        )
    }

    private fun hasFeedRoot(
        bytes: ByteArray,
        charset: String?,
    ): Boolean {
        val factory =
            XMLInputFactory.newDefaultFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
        return try {
            val reader =
                if (charset ==
                    null
                ) {
                    factory.createXMLStreamReader(bytes.inputStream())
                } else {
                    factory.createXMLStreamReader(bytes.inputStream(), charset)
                }
            try {
                reader.nextTag() == XMLStreamConstants.START_ELEMENT && reader.localName in setOf("rss", "feed", "RDF")
            } finally {
                reader.close()
            }
        } catch (_: XMLStreamException) {
            false
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BODY_BYTES = ProtocolLimits.MAX_FEED_BYTES
    }
}

internal class FeedDns(
    private val delegate: Dns,
) : Dns {
    override fun lookup(hostname: String) =
        delegate.lookup(hostname).also { answers ->
            if (answers.any { address ->
                    val host = address.hostAddress
                    val literal = if (':' in host) "[$host]" else host
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                        address.isSiteLocalAddress || address.isMulticastAddress ||
                        LocalDestination.isLocal("http://$literal/")
                }
            ) {
                throw UnknownHostException("Feed resolves to a local network")
            }
        }
}
