// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RssTransferSourceTest {
    @Test
    fun httpsDowngradeUsesRealFamilyWithoutLeakingCredentials() =
        runBlocking<Unit> {
            val held =
                okhttp3.tls.HeldCertificate
                    .Builder()
                    .commonName(
                        "localhost",
                    ).addSubjectAlternativeName("localhost")
                    .build()
            val certificates =
                okhttp3.tls.HandshakeCertificates
                    .Builder()
                    .heldCertificate(held)
                    .build()
            val store =
                java.security.KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setCertificateEntry("generated", held.certificate)
                }
            val trustFile =
                java.nio.file.Files
                    .createTempFile("w3-trust", ".p12")
            java.nio.file.Files
                .newOutputStream(trustFile)
                .use { store.store(it, "generated".toCharArray()) }
            val properties =
                mapOf(
                    "javax.net.ssl.trustStore" to trustFile.toString(),
                    "javax.net.ssl.trustStorePassword" to "generated",
                    "javax.net.ssl.trustStoreType" to "PKCS12",
                )
            val previous = properties.mapValues { System.getProperty(it.key) }
            try {
                properties.forEach { (key, value) -> System.setProperty(key, value) }
                var origin: Origin? = null
                TransferRig(CredentialLookup { if (it == origin) "Basic generated" else null }).use { rig ->
                    MockWebServer().use { secure ->
                        secure.useHttps(certificates.sslSocketFactory())
                        secure.start()
                        origin = Origin("https", "localhost", secure.port)
                        secure.enqueue(response(ByteArray(0), 302, "Location" to rig.server.url("/plain").toString()))
                        rig.server.enqueue(response())
                        rig.source = rig.source.copy(enclosureUrl = secure.url("/private").toString())
                        rig.part().withOpen { part ->
                            assertIs<TransferResult.Complete>(rig.transfer.transfer(rig.plan(part), part, rig.sink))
                            assertEquals("Basic generated", secure.takeRequest().headers["Authorization"])
                            assertNull(rig.server.takeRequest().headers["Authorization"])
                        }
                    }
                }
            } finally {
                previous.forEach { (key, value) ->
                    if (value ==
                        null
                    ) {
                        System.clearProperty(key)
                    } else {
                        System.setProperty(key, value)
                    }
                }
                java.nio.file.Files
                    .delete(trustFile)
            }
        }

    @Test
    fun redirectLoopIsPermanentAndNeverWrites() =
        transferTest { part ->
            repeat(25) { server.enqueue(response(ByteArray(0), 302, "Location" to server.url("/loop").toString())) }
            assertEquals(
                DownloadError.HTTP_CLIENT,
                assertIs<TransferResult.Permanent>(transfer.transfer(plan(part), part, sink)).error,
            )
            assertEquals(0, part.length())
        }

    @Test
    fun freshTransferStreamsIdentityAndWaitsForStartedCommit() =
        transferTest { part ->
            val bytes = mediaBytes(900_000)
            server.enqueue(response(bytes, 200, "Content-Type" to "text/plain", "ETag" to "\"one\""))
            sink.beforeStarted = { assertEquals(0, part.length()) }
            val result = assertIs<TransferResult.Complete>(transfer.transfer(plan(part), part, sink))
            assertContentEquals(bytes, part.contents())
            assertEquals(bytes.size.toLong(), result.total)
            assertEquals(MediaKind.MP3, result.media)
            assertEquals("\"one\"", result.etag)
            assertEquals(1, sink.starts.size)
            assertTrue(faults.largestWrite <= TRANSFER_BUFFER_BYTES)
            val request = server.takeRequest()
            assertEquals("identity", request.headers["Accept-Encoding"])
            assertTrue(request.headers["User-Agent"]!!.startsWith("Neutrodyne/"))
            assertNull(request.headers["Range"])
            assertNull(request.headers["If-Range"])
        }

    @Test
    fun cutAtHalfResumesWithStrongValidatorAndActualLength() =
        transferTest { part ->
            val bytes = mediaBytes(32_768)
            val receivedHalf = java.util.concurrent.CountDownLatch(1)
            sink.onBytes = { if (it == bytes.size / 2L) receivedHalf.countDown() }
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(
                        object : mockwebserver3.MockResponseBody {
                            override val contentLength = bytes.size.toLong()

                            override fun writeTo(sink: okio.BufferedSink) {
                                sink.write(bytes, 0, bytes.size / 2)
                                sink.flush()
                                check(receivedHalf.await(10, TimeUnit.SECONDS))
                            }
                        },
                    ).setHeader("ETag", "\"one\"")
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build(),
            )
            val first = assertIs<TransferResult.Transient>(transfer.transfer(plan(part), part, sink))
            assertEquals(DownloadError.NETWORK_IO, first.error)
            assertEquals(server.url("/original").toString(), first.retryUrl)
            val offset = part.length()
            assertEquals(bytes.size / 2L, offset)
            val resumed = plan(part, row.copy(etag = "\"one\"", totalBytes = bytes.size.toLong(), downloadedBytes = 1))
            assertEquals(offset, resumed.offset)
            server.enqueue(
                response(
                    bytes.copyOfRange(offset.toInt(), bytes.size),
                    206,
                    "Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}",
                    "ETag" to "\"one\"",
                ),
            )
            assertIs<TransferResult.Complete>(transfer.transfer(resumed, part, sink))
            assertContentEquals(bytes, part.contents())
            server.takeRequest()
            val second = server.takeRequest()
            assertEquals("bytes=$offset-", second.headers["Range"])
            assertEquals("\"one\"", second.headers["If-Range"])
        }

    @Test
    fun weakOrAbsentValidatorsRestartSameLengthReplacement() =
        runBlocking<Unit> {
            for ((etag, modified) in listOf(null to null, "W/\"v1\"" to null, "W/\"v1\"" to MODIFIED)) {
                transferTest { part ->
                    part.append(mediaBytes(), 2048)
                    val plan = plan(part, row.copy(etag = etag, lastModified = modified, totalBytes = 4096))
                    assertEquals(0, plan.offset)
                    assertEquals(0, part.length())
                    assertNull(plan.ifRange)
                    val replacement = mediaBytes().also { it.fill(42, 3) }
                    server.enqueue(response(replacement))
                    assertIs<TransferResult.Complete>(transfer.transfer(plan, part, sink))
                    assertContentEquals(replacement, part.contents())
                    assertNull(server.takeRequest().headers["Range"])
                }
            }
        }

    @Test
    fun weakEtagSuppressesLastModifiedEvenWhenDateQualifies() =
        transferTest { part ->
            server.enqueue(
                response(
                    mediaBytes(),
                    200,
                    "ETag" to "W/\"one\"",
                    "Last-Modified" to MODIFIED,
                    "Date" to DATE,
                ),
            )
            val result = assertIs<TransferResult.Complete>(transfer.transfer(plan(part), part, sink))
            assertNull(result.etag)
            assertNull(result.lastModified)
            assertNull(sink.starts.single().lastModified)
        }

    @Test
    fun lastModifiedQualifiesOnlyWithoutEtagAndAfterSixtySeconds() =
        runBlocking<Unit> {
            for (date in listOf(DATE, MODIFIED, "bad")) {
                transferTest { part ->
                    server.enqueue(response(mediaBytes(), 200, "Last-Modified" to MODIFIED, "Date" to date))
                    val result = assertIs<TransferResult.Complete>(transfer.transfer(plan(part), part, sink))
                    assertEquals(if (date == DATE) MODIFIED else null, result.lastModified)
                    if (date == DATE) {
                        part.truncate(2048)
                        val resumed =
                            plan(part, row.copy(lastModified = result.lastModified, totalBytes = 4096))
                        assertEquals(MODIFIED, resumed.ifRange)
                        server.enqueue(
                            response(
                                mediaBytes().copyOfRange(2048, 4096),
                                206,
                                "Content-Range" to "bytes 2048-4095/4096",
                            ),
                        )
                        assertIs<TransferResult.Complete>(transfer.transfer(resumed, part, sink))
                    }
                }
            }
        }

    @Test
    fun ignoredRangeTruncatesBeforeAcceptingFreshResponse() =
        transferTest { part ->
            part.append(mediaBytes(), 2048)
            val plan = plan(part, row.copy(etag = "\"old\"", totalBytes = 4096))
            val replacement = mediaBytes(6000)
            server.enqueue(response(replacement, 200, "ETag" to "\"new\""))
            val result = assertIs<TransferResult.Complete>(transfer.transfer(plan, part, sink))
            assertEquals("\"new\"", result.etag)
            assertEquals(0, sink.starts.single().offset)
            assertContentEquals(replacement, part.contents())
        }

    @Test
    fun invalidRangesNeverAppend() =
        runBlocking<Unit> {
            for (range in listOf(
                null,
                "bytes */4096",
                "bytes 1-4095/4096",
                "bytes 2048-4095/5000",
                "bytes 2048-2047/4096",
                "bytes 2048-4096/4096",
                "bytes 2048-4095/*",
                "bytes 999999999999999999999999-4095/4096",
            )) {
                transferTest { part ->
                    part.append(mediaBytes(), 2048)
                    val plan = plan(part, row.copy(etag = "\"one\"", totalBytes = 4096))
                    val builder = MockResponse.Builder().code(206).body(Buffer().write(mediaBytes(2048)))
                    range?.let { builder.setHeader("Content-Range", it) }
                    server.enqueue(builder.build())
                    assertIs<TransferResult.Restart>(transfer.transfer(plan, part, sink), range)
                    assertEquals(2048, part.length())
                    assertTrue(sink.starts.isEmpty())
                }
            }
        }

    @Test
    fun changedStrongValidatorRejectsSameLengthSplice() =
        transferTest { part ->
            part.append(mediaBytes(), 2048)
            server.enqueue(
                response(
                    mediaBytes(2048),
                    206,
                    "Content-Range" to "bytes 2048-4095/4096",
                    "ETag" to "\"two\"",
                ),
            )
            assertIs<TransferResult.Restart>(
                transfer.transfer(plan(part, row.copy(etag = "\"one\"", totalBytes = 4096)), part, sink),
            )
            assertEquals(2048, part.length())
        }

    @Test
    fun unsolicited206IsRestartedWithoutWriting() =
        transferTest { part ->
            server.enqueue(response(mediaBytes(), 206, "Content-Range" to "bytes 0-4095/4096"))
            assertIs<TransferResult.Restart>(transfer.transfer(plan(part), part, sink))
            assertEquals(0, part.length())
        }

    @Test
    fun range416VerifiesWholeFileOrRestarts() =
        transferTest { part ->
            part.append(mediaBytes(), 4096)
            val plan = plan(part, row.copy(etag = "\"one\"", totalBytes = 4096))
            server.enqueue(response(ByteArray(0), 416, "Content-Range" to "bytes */4096"))
            assertIs<TransferResult.Complete>(transfer.transfer(plan, part, sink))
            server.enqueue(response(ByteArray(0), 416, "Content-Range" to "bytes */5000"))
            assertIs<TransferResult.Restart>(transfer.transfer(plan, part, sink))
            part.truncate(2048)
            server.enqueue(response(ByteArray(0), 416))
            assertIs<TransferResult.Restart>(transfer.transfer(plan.copy(offset = 2048), part, sink))
        }

    @Test
    fun wrongLengthIsNetworkFailureAndUnknownLengthAdoptsActualBytes() =
        transferTest { part ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(Buffer().write(mediaBytes()))
                    .setHeader("Content-Length", 8192)
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build(),
            )
            val cut = transfer.transfer(plan(part), part, sink)
            assertEquals(DownloadError.NETWORK_IO, assertIs<TransferResult.Transient>(cut, cut.toString()).error)
            part.truncate(0)
            server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(mediaBytes(7000)), 311).build())
            val result = assertIs<TransferResult.Complete>(transfer.transfer(plan(part), part, sink))
            assertEquals(7000, result.total)
            assertNull(sink.starts.last().total)
        }

    @Test
    fun retryAfterSupportsSecondsDatesAndCapsAtSixHours() =
        transferTest { part ->
            for ((header, expected) in listOf("5" to 5000L, "30" to 30000L, "-1" to null, "bad" to null)) {
                server.enqueue(response(ByteArray(0), 429, "Retry-After" to header))
                val result = assertIs<TransferResult.Transient>(transfer.transfer(plan(part), part, sink))
                assertEquals(DownloadError.HTTP_RATE_LIMITED, result.error)
                assertEquals(expected, result.retryAfterMs)
            }
            for (header in listOf("99999999", DATE)) {
                server.enqueue(response(ByteArray(0), 503, "Retry-After" to header))
                val result = assertIs<TransferResult.Wait>(transfer.transfer(plan(part), part, sink))
                assertEquals(WaitReason.BACKOFF, result.reason)
                assertTrue(result.countsAsAttempt)
                assertEquals(503, result.httpStatus)
                assertEquals(clock.now() + 6 * 60 * 60 * 1000, result.untilMs)
            }
            assertEquals(0, part.length())
        }

    @Test
    fun statusesMapToExplicitFailures() =
        transferTest { part ->
            for ((code, error) in mapOf(
                401 to DownloadError.HTTP_AUTH,
                403 to DownloadError.HTTP_AUTH,
                404 to DownloadError.HTTP_NOT_FOUND,
                410 to DownloadError.HTTP_GONE,
                400 to DownloadError.HTTP_CLIENT,
            )) {
                server.enqueue(response(ByteArray(0), code))
                val result = assertIs<TransferResult.Permanent>(transfer.transfer(plan(part), part, sink))
                assertEquals(error, result.error)
                assertEquals(code, result.httpStatus)
            }
            for (code in listOf(408, 500, 502, 599)) {
                server.enqueue(response(ByteArray(0), code, "Retry-After" to "1"))
                assertEquals(
                    DownloadError.HTTP_SERVER,
                    assertIs<TransferResult.Transient>(transfer.transfer(plan(part), part, sink)).error,
                )
            }
        }

    @Test
    fun htmlAndHlsAreRejectedBeforeAnyWrite() =
        runBlocking<Unit> {
            for ((bytes, error) in listOf(
                "\uFEFF \n<html>login</html>" to DownloadError.NOT_MEDIA,
                "#EXTM3U\nsegment.ts" to DownloadError.UNSUPPORTED_STREAM,
                "" to DownloadError.NOT_MEDIA,
            )) {
                transferTest { part ->
                    server.enqueue(response(bytes.encodeToByteArray()))
                    assertEquals(
                        error,
                        assertIs<TransferResult.Permanent>(transfer.transfer(plan(part), part, sink)).error,
                    )
                    assertEquals(0, part.length())
                    assertTrue(sink.starts.isEmpty())
                }
            }
        }

    @Test
    fun onlyUnvalidatedAndroidHtmlWaitsForNetwork() =
        runBlocking<Unit> {
            for (platform in PlatformKind.entries) {
                TransferRig(platform = platform).use { rig ->
                    with(rig) {
                        network.setStatus(NetworkStatus(true, false, false, false))
                        part().withOpen { part ->
                            server.enqueue(response("<html>login</html>".encodeToByteArray()))
                            val result = transfer.transfer(plan(part), part, sink)
                            if (platform ==
                                PlatformKind.ANDROID
                            ) {
                                assertEquals(WaitReason.NETWORK, assertIs<TransferResult.Wait>(result).reason)
                            } else {
                                assertEquals(DownloadError.NOT_MEDIA, assertIs<TransferResult.Permanent>(result).error)
                            }
                            assertEquals(0, part.length())
                        }
                    }
                }
            }
        }

    @Test
    fun disconnectedNetworkWaitsWithoutRequestOrAttempt() =
        runBlocking<Unit> {
            TransferRig().use { rig ->
                with(rig) {
                    network.setStatus(FakeNetworkMonitor.OFFLINE)
                    part().withOpen { part ->
                        assertEquals(
                            WaitReason.NETWORK,
                            assertIs<TransferResult.Wait>(transfer.transfer(plan(part), part, sink)).reason,
                        )
                        assertEquals(0, server.requestCount)
                    }
                }
            }
        }

    @Test
    fun writeAndSyncFaultsAreClassifiedBeforeNetworkRetries() =
        runBlocking<Unit> {
            for (failure in StorageFailure.entries) {
                transferTest { part ->
                    val plan = plan(part)
                    roots.failure = failure
                    faults.writeFailure = IOException("injected")
                    server.enqueue(response())
                    val result = transfer.transfer(plan, part, sink)
                    when (failure) {
                        StorageFailure.IO -> {
                            assertIs<TransferResult.Transient>(result)
                        }

                        StorageFailure.TOO_LARGE -> {
                            assertIs<TransferResult.Permanent>(result)
                        }

                        else -> {
                            assertEquals(WaitReason.STORAGE, assertIs<TransferResult.Wait>(result).reason)
                            assertEquals(
                                if (failure ==
                                    StorageFailure.FULL
                                ) {
                                    DownloadError.STORAGE_FULL
                                } else {
                                    DownloadError.STORAGE_UNAVAILABLE
                                },
                                result.error,
                            )
                        }
                    }
                    assertEquals(0, part.length())
                }
            }
            transferTest { part ->
                val plan = plan(part)
                roots.failure = StorageFailure.FULL
                faults.syncFailure = IOException("sync failed")
                server.enqueue(response())
                assertIs<TransferResult.Wait>(transfer.transfer(plan, part, sink))
                assertEquals(4096, part.length())
            }
        }

    @Test
    fun cancellationPropagatesAndKeepsPartForNextRunner() =
        transferTest { part ->
            val started = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            sink.beforeStarted = {
                started.complete(Unit)
                hold.await()
            }
            server.enqueue(response())
            val plan = plan(part)
            val job =
                kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).async {
                    transfer.transfer(plan, part, sink)
                }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(0, part.length())
            server.enqueue(response())
            sink.beforeStarted = null
            assertIs<TransferResult.Complete>(transfer.transfer(plan, part, sink))
        }

    @Test
    fun redirectedRequestsKeepIdentityAndOriginScopedCredentials() =
        runBlocking<Unit> {
            var credentialOrigin: Origin? = null
            val credentials =
                CredentialLookup { origin ->
                    if (origin ==
                        credentialOrigin
                    ) {
                        "Basic dXNlcjpwYXNz"
                    } else {
                        null
                    }
                }
            TransferRig(credentials).use { rig ->
                with(rig) {
                    MockWebServer().use { cdn ->
                        cdn.start()
                        credentialOrigin = Origin("http", "localhost", server.port)
                        server.enqueue(response(ByteArray(0), 302, "Location" to server.url("/hop").toString()))
                        server.enqueue(
                            response(
                                ByteArray(0),
                                302,
                                "Location" to
                                    cdn
                                        .url("/media?secret=hidden")
                                        .newBuilder()
                                        .host("127.0.0.1")
                                        .build()
                                        .toString(),
                            ),
                        )
                        cdn.enqueue(response())
                        part().withOpen { part ->
                            assertIs<TransferResult.Complete>(transfer.transfer(plan(part), part, sink))
                            assertEquals("Basic dXNlcjpwYXNz", server.takeRequest().headers["Authorization"])
                            assertEquals("Basic dXNlcjpwYXNz", server.takeRequest().headers["Authorization"])
                            val request = cdn.takeRequest()
                            assertNull(request.headers["Authorization"])
                            assertEquals("identity", request.headers["Accept-Encoding"])
                        }
                    }
                }
            }
        }

    @Test
    fun cachedFinalUrlAuthFailureFallsBackOnceAndFreshPrepareUsesOriginal() =
        transferTest { part ->
            val plan = plan(part)
            server.enqueue(response(ByteArray(0), 403))
            server.enqueue(response())
            assertIs<TransferResult.Complete>(
                transfer.transfer(plan.copy(url = server.url("/expired").toString()), part, sink),
            )
            assertEquals("/expired", server.takeRequest().url.encodedPath)
            assertEquals("/original", server.takeRequest().url.encodedPath)
            assertEquals(source.enclosureUrl, plan(part).url)
            server.enqueue(response(ByteArray(0), 403))
            server.enqueue(response(ByteArray(0), 503, "Retry-After" to "1"))
            val retry =
                assertIs<TransferResult.Transient>(
                    transfer.transfer(plan.copy(url = server.url("/expired").toString()), part, sink),
                )
            assertEquals(plan.originalUrl, retry.retryUrl)
        }

    @Test
    fun siriVerifiesAllSupportedDigestsAndRejectsMismatch() =
        transferTest { part ->
            val bytes = mediaBytes()
            for (algorithm in listOf("SHA-256", "SHA-384", "SHA-512")) {
                val digest =
                    java.security.MessageDigest
                        .getInstance(algorithm)
                        .digest(bytes)
                        .toByteString()
                        .base64()
                server.enqueue(response(bytes))
                val plan = plan(part).copy(integrity = "${algorithm.lowercase().replace("-", "")}-$digest")
                assertIs<TransferResult.Complete>(transfer.transfer(plan, part, sink))
                assertFalse(hasher.matches("old", part.path, "sha256-AAAA"))
                part.truncate(0)
            }
            server.enqueue(response(bytes))
            assertEquals(
                DownloadError.SIZE_MISMATCH,
                assertIs<TransferResult.Transient>(
                    transfer.transfer(plan(part).copy(integrity = "sha384-AAAA"), part, sink),
                ).error,
            )
        }

    private companion object {
        const val MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT"
        const val DATE = "Wed, 21 Oct 2015 07:30:00 GMT"
    }
}

internal suspend fun <T> PartFile.withOpen(block: suspend (PartFile) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
