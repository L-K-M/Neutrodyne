// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError as CheckError

/** HTTP identity remains HTTPS:443; only the test socket destination is rewritten to loopback. */
class GitHubUpdateSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var clients: OkHttpNeutrodyneHttpClients
    private lateinit var source: GitHubUpdateSource
    private val hosts = mutableListOf<String>()
    private var credentialLookups = 0
    private var failIo = false
    private val transportCases =
        checkNotNull(javaClass.getResourceAsStream("/updates/transport-url-cases.json"))
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }

    @Before fun setup() {
        val certificate =
            HeldCertificate
                .Builder()
                .commonName("github.com")
                .addSubjectAlternativeName("github.com")
                .addSubjectAlternativeName("release-assets.githubusercontent.com")
                .build()
        server = MockWebServer()
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.useHttps(
            HandshakeCertificates
                .Builder()
                .heldCertificate(certificate)
                .build()
                .sslSocketFactory(),
        )
        server.start()
        val platform =
            object : PlatformInfo {
                override val kind = PlatformKind.DESKTOP
                override val userAgentPlatform = "Test; x64"
                override val androidSdkInt: Int? = null
                override val regionCode = "DE"
            }
        val ua = UserAgentProvider(platform, "0.1.0", UPDATE_REPO)
        val credentials =
            CredentialLookup {
                credentialLookups++
                "Basic must-never-be-sent"
            }
        val networks =
            NetworkClients(
                CoreClients(
                    UserAgentInterceptor(ua),
                    LocalNetworkGuard(LocalNetworkAccess(), platform),
                    DnsFamilyHints(),
                    emptySet(),
                ),
                AuthInterceptor { credentials },
            )
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val routed =
            networks.api
                .newBuilder()
                .proxy(Proxy.NO_PROXY)
                .dns { name ->
                    hosts += name
                    check(name in setOf("github.com", "release-assets.githubusercontent.com"))
                    listOf(InetAddress.getLoopbackAddress())
                }.socketFactory(
                    object : SocketFactory() {
                        override fun createSocket(): Socket =
                            object : Socket() {
                                override fun connect(
                                    endpoint: SocketAddress,
                                    timeout: Int,
                                ) {
                                    val destination = InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)
                                    super.connect(destination, timeout)
                                }
                            }

                        override fun createSocket(
                            host: String,
                            port: Int,
                        ): Socket = error("Unused")

                        override fun createSocket(
                            host: String,
                            port: Int,
                            local: InetAddress,
                            localPort: Int,
                        ): Socket = error("Unused")

                        override fun createSocket(
                            host: InetAddress,
                            port: Int,
                        ): Socket = error("Unused")

                        override fun createSocket(
                            host: InetAddress,
                            port: Int,
                            local: InetAddress,
                            localPort: Int,
                        ): Socket = error("Unused")
                    },
                ).sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .addInterceptor(
                    Interceptor { chain ->
                        if (failIo) {
                            throw IOException(
                                "GET https://release-assets.githubusercontent.com/a?sig=must-not-log",
                            )
                        }
                        chain.proceed(chain.request())
                    },
                ).build()
        // Replace only this test instance before the Ktor wrapper reads it. No production test hook
        // or second client family; derived API retains the pool, guards and credential interceptor.
        NetworkClients::class.java
            .getDeclaredField("api")
            .apply { isAccessible = true }
            .set(networks, routed)
        clients = OkHttpNeutrodyneHttpClients(networks, ua)
        source = GitHubUpdateSource(clients, updateBuild(), UpdateManifestParser(updateBuild()))
    }

    @After fun cleanup() {
        clients.close()
        server.close()
    }

    private fun response(
        code: Int = 200,
        body: String = updateJson(),
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build()

    private fun redirect(url: String): MockResponse =
        MockResponse
            .Builder()
            .code(302)
            .addHeader("Location", url)
            .build()

    private fun assertError(expected: CheckError) =
        runBlocking {
            assertEquals(Outcome.Failure(expected), source.fetch())
        }

    @Test fun followsTwoRedirectsUsingApiStackAndNoCredentialsOrCookies() =
        runBlocking {
            server.enqueue(redirect("$UPDATE_REPO/releases/download/v1.0.0/neutrodyne-update.json"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(
                        302,
                    ).addHeader("Location", "https://release-assets.githubusercontent.com/assets/123?sig=secret")
                    .addHeader("Set-Cookie", "token=secret; Secure")
                    .build(),
            )
            server.enqueue(response())
            assertIs<Outcome.Success<*>>(source.fetch())
            repeat(3) {
                val request = server.takeRequest()
                assertEquals(null, request.headers["Authorization"])
                assertEquals(null, request.headers["Cookie"])
                assertEquals(null, request.headers["Proxy-Authorization"])
                assertTrue(request.headers["User-Agent"]!!.startsWith("Neutrodyne/"))
            }
            assertEquals(0, credentialLookups)
            assertTrue(hosts.none { it == transportCases.text("restApiHost") })
            assertTrue("release-assets.githubusercontent.com" in hosts)
        }

    @Test fun rejectsForeignLookalikeApiHttpCredentialsAndWrongPortBeforeFollow() =
        runBlocking {
            transportCases.getValue("rejectedRedirects").jsonArray.map { it.jsonPrimitive.content }.forEach { target ->
                server.enqueue(redirect(target))
                val before = server.requestCount
                assertError(CheckError.MANIFEST_INVALID)
                assertEquals(before + 1, server.requestCount)
            }
            assertEquals(listOf("github.com"), hosts.distinct())
        }

    @Test fun redirectLoopsAreBoundedAndClassifiedInvalid() {
        repeat(6) { server.enqueue(redirect("$UPDATE_REPO/releases/download/v1.0.0/neutrodyne-update.json")) }
        assertError(CheckError.MANIFEST_INVALID)
        assertEquals(6, server.requestCount)
    }

    @Test fun forbiddenCdnHopIsBlockedBeforeDnsOrRequest() {
        server.enqueue(redirect("https://release-assets.githubusercontent.com/a?sig=secret"))
        server.enqueue(redirect("https://evil.org/a"))
        assertError(CheckError.MANIFEST_INVALID)
        assertEquals(2, server.requestCount)
        assertTrue("evil.org" !in hosts)
    }

    @Test fun notFoundAndOtherHttpErrorsAreManifestInvalid() {
        listOf(404, 401, 500).forEach { status ->
            server.enqueue(response(status, ""))
            assertError(CheckError.MANIFEST_INVALID)
        }
    }

    @Test fun forbiddenAndTooManyRequestsAreRateLimitedWithoutRetry() {
        listOf(403, 429).forEach { status ->
            server.enqueue(response(status, ""))
            assertError(CheckError.RATE_LIMITED)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun ioFailureIsNetwork() {
        failIo = true
        assertError(CheckError.NETWORK)
        assertEquals(0, server.requestCount)
    }

    @Test fun declaredOversizeBodyIsRejected() {
        server.enqueue(response(body = "x".repeat(MAX_UPDATE_MANIFEST_BYTES + 1)))
        assertError(CheckError.MANIFEST_INVALID)
    }

    @Test fun chunkedBodyIsBoundedWithoutContentLength() {
        server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(MAX_UPDATE_MANIFEST_BYTES + 1), 1024).build())
        assertError(CheckError.MANIFEST_INVALID)
    }

    @Test fun misleadingSmallContentLengthCannotExposeAnOversizeManifest() {
        server.enqueue(
            MockResponse
                .Builder()
                .body("x".repeat(MAX_UPDATE_MANIFEST_BYTES + 1))
                .setHeader("Content-Length", "2")
                .build(),
        )
        assertError(CheckError.MANIFEST_INVALID)
    }

    @Test fun exactCapIsAcceptedAndOneMoreByteRejected() =
        runBlocking {
            val body = updateJson() + " ".repeat(MAX_UPDATE_MANIFEST_BYTES - updateJson().encodeToByteArray().size)
            server.enqueue(MockResponse.Builder().chunkedBody(body, 4096).build())
            assertIs<Outcome.Success<*>>(source.fetch())
            server.enqueue(MockResponse.Builder().chunkedBody("$body ", 4096).build())
            assertError(CheckError.MANIFEST_INVALID)
        }

    @Test fun hostileManifestLinksFailEndToEnd() {
        server.enqueue(
            response(
                body = updateJson().replace("/releases/download/", "/releases/../../../attacker/releases/download/"),
            ),
        )
        assertError(CheckError.MANIFEST_INVALID)
    }

    @Test fun cancellationPropagatesAndClosesTheActiveCall() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .bodyDelay(20, java.util.concurrent.TimeUnit.SECONDS)
                    .body(updateJson())
                    .build(),
            )
            val check = async { source.fetch() }
            withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
            check.cancel()
            try {
                check.await()
                error("Expected cancellation")
            } catch (_: CancellationException) {
            }
            assertEquals(1, server.requestCount)
        }

    @Test fun apiCallTimeoutIsStillTheIslandEightSecondLimit() {
        // A slower server exercises the real engine timeout; the coordinator supplies the 30s ceiling.
        server.enqueue(
            MockResponse
                .Builder()
                .headersDelay(10, java.util.concurrent.TimeUnit.SECONDS)
                .body(updateJson())
                .build(),
        )
        assertError(CheckError.NETWORK)
        assertEquals(1, server.requestCount)
    }

    @Test fun signedUrlExceptionsAreNotLogged() {
        val messages = mutableListOf<String>()
        Log.install(LogSink { _, _, message -> messages += message })
        try {
            failIo = true
            assertError(CheckError.NETWORK)
            assertTrue(messages.none { it.contains("must-not-log") })
        } finally {
            Log.install()
        }
    }

    @Test fun invalidConfiguredManifestEndpointNeverSendsARequest() =
        runBlocking {
            val build = updateBuild().copy(updateManifestUrl = transportCases.text("invalidManifestEndpoint"))
            val bad = GitHubUpdateSource(clients, build, UpdateManifestParser(build))
            assertEquals(Outcome.Failure(CheckError.MANIFEST_INVALID), bad.fetch())
            assertEquals(0, server.requestCount)
        }
}
