// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.common.Origin
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardedHttpDataSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun redirectsKeepSameOriginAuthButDropItAcrossOriginsAndPinFinalUrl() {
        MockWebServer().use { origin ->
            MockWebServer().use { cdn ->
                origin.start()
                cdn.start()
                origin.enqueue(response(302, "", "Location" to origin.url("/second").toString()))
                origin.enqueue(response(302, "", "Location" to cdn.url("/signed?token=private").toString()))
                cdn.enqueue(response(body = "abcdef"))
                cdn.enqueue(response(206, "def", "Content-Range" to "bytes 3-5/6"))
                val credential = Origin("http", "localhost", origin.port)
                val clients = mediaClients(CredentialLookup { if (it == credential) "Basic private" else null })
                SourceHarness(temporary.newFolder()).use { h ->
                    h.index.putAll(listOf(media(url = origin.url("/start").toString())))
                    val factory = GuardedHttpDataSource.Factory(clients, h.resolver)
                    assertEquals("abcdef", read(factory.createDataSource(), h.resolve()))
                    assertEquals("def", read(factory.createDataSource(), h.resolve(position = 3)))
                    assertEquals("Basic private", origin.takeRequest().headers["Authorization"])
                    assertEquals("Basic private", origin.takeRequest().headers["Authorization"])
                    assertNull(cdn.takeRequest().headers["Authorization"])
                    val second = cdn.takeRequest()
                    assertEquals("/signed?token=private", second.target)
                    assertEquals("bytes=3-", second.headers["Range"])
                    assertEquals(2, origin.requestCount)
                }
            }
        }
    }

    @Test fun httpToHttpsRedirectsWorkAndSchemeChangeDoesNotCarryAuth() {
        val certificate =
            HeldCertificate
                .Builder()
                .commonName(
                    "localhost",
                ).addSubjectAlternativeName("localhost")
                .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                http.start()
                https.useHttps(serverTls.sslSocketFactory())
                https.start()
                http.enqueue(response(302, "", "Location" to https.url("/secure").toString()))
                https.enqueue(response(body = "audio"))
                val credential = Origin("http", "localhost", http.port)
                val client =
                    mediaClients(
                        CredentialLookup {
                            if (it ==
                                credential
                            ) {
                                "Basic private"
                            } else {
                                null
                            }
                        },
                    ).media
                        .newBuilder()
                        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                        .build()
                SourceHarness(temporary.newFolder()).use { h ->
                    h.index.putAll(listOf(media(url = http.url("/start").toString())))
                    assertEquals(
                        "audio",
                        read(
                            GuardedHttpDataSource(OkHttpDataSource.Factory(client).createDataSource(), h.resolver),
                            h.resolve(),
                        ),
                    )
                    assertEquals("Basic private", http.takeRequest().headers["Authorization"])
                    assertNull(https.takeRequest().headers["Authorization"])
                }
            }
        }
    }

    @Test fun identityEncodingAndBoundedRangesKeepByteOffsets() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(206, "cde", "Content-Range" to "bytes 2-4/10"))
            SourceHarness(temporary.newFolder()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/audio").toString())))
                val factory = GuardedHttpDataSource.Factory(mediaClients(), h.resolver)
                assertEquals("cde", read(factory.createDataSource(), h.resolve(position = 2, length = 3)))
                val request = server.takeRequest()
                assertEquals("identity", request.headers["Accept-Encoding"])
                assertEquals("bytes=2-4", request.headers["Range"])
                assertTrue(request.headers["User-Agent"]!!.startsWith("Neutrodyne/"))
                assertEquals(10L, h.resolver.remotePin(h.resolve().key)?.totalLength)
            }
        }
    }

    @Test fun staleSignedFinalUrlIsClearedAndPolicyAllowsOnlyOneOriginalRetry() {
        for (code in listOf(401, 403, 404, 410)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response(302, "", "Location" to server.url("/old").toString()))
                server.enqueue(response(body = "abcdef"))
                server.enqueue(response(code))
                server.enqueue(response(302, "", "Location" to server.url("/fresh").toString()))
                server.enqueue(response(206, "def", "Content-Range" to "bytes 3-5/6"))
                SourceHarness(temporary.newFolder()).use { h ->
                    h.index.putAll(listOf(media(url = server.url("/original").toString())))
                    val factory = GuardedHttpDataSource.Factory(mediaClients(), h.resolver)
                    read(factory.createDataSource(), h.resolve())
                    val final = h.resolve(position = 3)
                    assertEquals("/old", final.uri.path)
                    val failure =
                        assertThrows(
                            HttpDataSource.InvalidResponseCodeException::class.java,
                        ) { read(factory.createDataSource(), final) }
                    assertEquals(1_000L, retry(failure, 1))
                    assertEquals(C.TIME_UNSET, retry(failure, 2))
                    val retry = h.resolve(position = 3)
                    assertEquals("/original", retry.uri.path)
                    assertEquals("def", read(factory.createDataSource(), retry))
                    assertEquals("/fresh", h.resolve().uri.path)
                }
            }
        }
    }

    @Test fun changedTotalLengthClosesBeforeReadingAndRemainsFatal() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(body = "abcdef"))
            server.enqueue(response(206, "xyz", "Content-Range" to "bytes 3-5/9"))
            SourceHarness(temporary.newFolder()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/audio").toString())))
                val factory = GuardedHttpDataSource.Factory(mediaClients(), h.resolver)
                read(factory.createDataSource(), h.resolve())
                val failure =
                    assertThrows(
                        ContentChangedException::class.java,
                    ) { read(factory.createDataSource(), h.resolve(position = 3)) }
                assertEquals(C.TIME_UNSET, retry(IOException(failure), 1))
                assertThrows(ContentChangedException::class.java) { h.resolve() }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun partialContentLengthDoesNotMasqueradeAsTotalAndUnknownTotalsRemainUnknown() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(206, "abc", "Content-Range" to "bytes 0-2/6"))
            server.enqueue(response(206, "def", "Content-Range" to "bytes 3-5/*"))
            SourceHarness(temporary.newFolder()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/audio").toString())))
                val factory = GuardedHttpDataSource.Factory(mediaClients(), h.resolver)
                read(factory.createDataSource(), h.resolve(length = 3))
                assertEquals("def", read(factory.createDataSource(), h.resolve(position = 3)))
                assertEquals(6L, h.resolver.remotePin(h.resolve().key)?.totalLength)
            }
        }
    }

    @Test fun defaultBackoffRemainsForNonRssFailuresAndTerminalSourceFailuresAreFatal() {
        val ytSpec =
            DataSpec
                .Builder()
                .setUri("https://example.com/yt")
                .setKey("yt:video:140")
                .build()
        val error =
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                null,
                emptyMap(),
                ytSpec,
                byteArrayOf(),
            )
        assertEquals(0L, retry(error, 1))
        assertEquals(1_000L, retry(error, 2))
        assertEquals(5_000L, retry(error, 9))
        for (failure in listOf(
            NoMediaException(),
            EpisodeGoneException(1),
            YouTubeResolveException(ch.lkmc.neutrodyne.youtube.api.ResolveResult.Unsupported),
        )) {
            assertEquals(C.TIME_UNSET, retry(IOException(failure), 1))
        }
    }

    @Test fun sharedLoggingRedactsPrivateMediaErrors() {
        val messages = mutableListOf<String>()
        Log.install(LogSink { _, _, message -> messages += message })
        try {
            Log.e(
                "Playback",
                IOException("https://user:password@media.example/audio?token=private\nAuthorization: Basic secret"),
            ) {
                "Media open failed"
            }
            assertTrue(messages.single().contains("token=…"))
            for (secret in listOf(
                "password",
                "private",
                "Basic secret",
            )) {
                assertFalse(messages.single().contains(secret))
            }
        } finally {
            Log.install()
        }
    }

    @Test fun cachedRedirectDoesNotDefeatStaleFinalRetry() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(302, "", "Location" to server.url("/old").toString()))
            server.enqueue(response(206, "abc", "Content-Range" to "bytes 0-2/6"))
            server.enqueue(response(403))
            server.enqueue(response(302, "", "Location" to server.url("/fresh").toString()))
            server.enqueue(response(206, "def", "Content-Range" to "bytes 3-5/6"))
            SourceHarness(temporary.newFolder()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/original").toString())))
                val factory = cachedFactory(h)
                val first =
                    DataSpec
                        .Builder()
                        .setUri(episodeUri(1))
                        .setLength(3)
                        .build()
                val rest =
                    DataSpec
                        .Builder()
                        .setUri(episodeUri(1))
                        .setPosition(3)
                        .build()
                assertEquals("abc", read(factory.createDataSource(), first))
                val key = checkNotNull(h.resolve().key)
                assertNull(
                    androidx.media3.datasource.cache.ContentMetadata.getRedirectedUri(
                        h.owner.cache().getContentMetadata(key),
                    ),
                )
                assertThrows(
                    HttpDataSource.InvalidResponseCodeException::class.java,
                ) { read(factory.createDataSource(), rest) }
                assertEquals("def", read(factory.createDataSource(), rest))
                val paths = List(5) { server.takeRequest().target }
                assertEquals(listOf("/original", "/old", "/old", "/original", "/fresh"), paths)
                assertEquals("abcdef", read(factory.createDataSource(), DataSpec(episodeUri(1))))
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun cachedBytesNeverGetSplicedWithChangedResponse() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(206, "abc", "Content-Range" to "bytes 0-2/6"))
            server.enqueue(response(206, "XYZ", "Content-Range" to "bytes 3-5/9"))
            SourceHarness(temporary.newFolder()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/audio").toString())))
                val factory = cachedFactory(h)
                assertEquals(
                    "abc",
                    read(
                        factory.createDataSource(),
                        DataSpec
                            .Builder()
                            .setUri(episodeUri(1))
                            .setLength(3)
                            .build(),
                    ),
                )
                assertThrows(ContentChangedException::class.java) {
                    read(
                        factory.createDataSource(),
                        DataSpec
                            .Builder()
                            .setUri(episodeUri(1))
                            .setPosition(3)
                            .build(),
                    )
                }
                assertThrows(
                    ContentChangedException::class.java,
                ) { read(factory.createDataSource(), DataSpec(episodeUri(1))) }
                assertEquals(3L, h.owner.cache().cacheSpace)
            }
        }
    }

    @Test fun rangeIgnoredWithChangedLengthIsFatalBeforeBytesReachCache() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(206, "abc", "Content-Range" to "bytes 0-2/6"))
            server.enqueue(response(body = "uvwxyz123"))
            SourceHarness(temporary.newFolder()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/audio").toString())))
                val factory = cachedFactory(h)
                read(
                    factory.createDataSource(),
                    DataSpec
                        .Builder()
                        .setUri(episodeUri(1))
                        .setLength(3)
                        .build(),
                )
                assertThrows(ContentChangedException::class.java) {
                    read(
                        factory.createDataSource(),
                        DataSpec
                            .Builder()
                            .setUri(episodeUri(1))
                            .setPosition(3)
                            .build(),
                    )
                }
                assertEquals(3L, h.owner.cache().cacheSpace)
            }
        }
    }

    @Test fun localFileBypassesCacheAndNetworkThroughTheWholeStack() {
        SourceHarness(temporary.newFolder()).use { h ->
            val file = temporary.newFile().apply { writeText("local audio") }
            h.index.putAll(listOf(media()))
            h.local.files[1] = Uri.fromFile(file).toString()
            assertEquals("local audio", read(cachedFactory(h).createDataSource(), DataSpec(episodeUri(1))))
            assertEquals(0L, h.owner.cache().cacheSpace)
        }
    }

    @Test fun brokenCacheInitializationFallsBackToHttpOnRetry() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(body = "audio"))
            SourceHarness(temporary.newFile()).use { h ->
                h.index.putAll(listOf(media(url = server.url("/audio").toString())))
                val source = cachedFactory(h).createDataSource()
                val spec = DataSpec(episodeUri(1))
                assertThrows(IOException::class.java) { read(source, spec) }
                assertEquals(ch.lkmc.neutrodyne.playback.api.StreamKind.STREAM, h.resolver.streamKind(1))
                assertEquals("audio", read(source, spec))
                assertEquals(1, server.requestCount)
                assertTrue(
                    h.owner
                        .cache()
                        .getCachedSpans(checkNotNull(h.resolve().key))
                        .isEmpty(),
                )
            }
        }
    }

    private fun cachedFactory(h: SourceHarness): DataSource.Factory =
        ResolvingDataSource.Factory(
            DefaultDataSource.Factory(
                RuntimeEnvironment.getApplication(),
                CacheDataSource
                    .Factory()
                    .setCache(
                        h.owner.cache(),
                    ).setUpstreamDataSourceFactory(GuardedHttpDataSource.Factory(mediaClients(), h.resolver))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            ),
            h.resolver,
        )

    private fun read(
        source: DataSource,
        spec: DataSpec,
    ): String =
        try {
            source.open(spec)
            val out = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(32)
            while (true) {
                val count = source.read(bytes, 0, bytes.size)
                if (count == C.RESULT_END_OF_INPUT) break
                out.write(bytes, 0, count)
            }
            out.toString("UTF-8")
        } finally {
            source.close()
        }

    private fun retry(
        error: IOException,
        count: Int,
    ): Long {
        val event = LoadEventInfo(1, DataSpec(Uri.parse("neutrodyne://episode/1")), 0)
        return NdLoadErrorHandlingPolicy().getRetryDelayMsFor(
            LoadErrorInfo(event, MediaLoadData(C.DATA_TYPE_MEDIA), error, count),
        )
    }

    private fun response(
        code: Int = 200,
        body: String = "",
        vararg headers: Pair<String, String>,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .apply {
                headers.forEach { (name, value) ->
                    addHeader(name, value)
                }
            }.build()
}
