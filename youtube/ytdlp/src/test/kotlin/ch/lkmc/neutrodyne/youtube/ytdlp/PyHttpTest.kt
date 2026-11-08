// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.PyHttp
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PyHttpTest {
    private val info =
        object : PlatformInfo {
            override val kind = PlatformKind.DESKTOP
            override val userAgentPlatform = "test"
            override val androidSdkInt: Int? = null
            override val regionCode = "US"
        }
    private val clients =
        CoreClients(
            UserAgentInterceptor(UserAgentProvider(info, "test", "https://example.invalid")),
            LocalNetworkGuard(LocalNetworkAccess(), info),
            DnsFamilyHints(),
            emptySet(),
        )

    @Test fun gzipIsDecodedAndRepeatedCookieHeadersSurviveTheBridge() {
        MockWebServer().use { server ->
            val plain = "decoded engine response".encodeToByteArray()
            val zipped = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(plain) } }.toByteArray()
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("Set-Cookie", "first=1; Path=/")
                    .addHeader("Set-Cookie", "second=2; Path=/")
                    .body(Buffer().write(zipped))
                    .build(),
            )
            server.start()
            val http = PyHttp(clients)
            http.begin(1)
            try {
                val reply =
                    http.execute(
                        1,
                        "GET",
                        server.url("/engine").toString(),
                        """{"Accept-Encoding":"identity","Cookie":"SOCS=CAE"}""",
                        null,
                        5000,
                        "V4",
                    )
                assertEquals(200, reply.status)
                assertContentEquals(plain, reply.body)
                assertTrue(reply.headersJson.contains("first=1") && reply.headersJson.contains("second=2"))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("gzip", request.headers["Accept-Encoding"])
                assertEquals("SOCS=CAE", request.headers["Cookie"])
                assertNull(request.headers["Authorization"])
            } finally {
                http.end(1)
            }
        }
    }

    @Test fun cancellingOneCallLeavesOtherCallSocketsAvailable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            server.enqueue(MockResponse.Builder().body("second").build())
            server.start()
            val http = PyHttp(clients)
            http.begin(1)
            http.begin(2)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val first =
                    executor.submit<Boolean> {
                        assertFailsWith<IOException> {
                            http.execute(1, "GET", server.url("/first").toString(), "{}", null, 5000, null)
                        }
                        true
                    }
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                http.cancel(1)
                assertTrue(first.get(5, TimeUnit.SECONDS))
                assertContentEquals(
                    "second".encodeToByteArray(),
                    http.execute(2, "GET", server.url("/second").toString(), "{}", null, 5000, null).body,
                )
            } finally {
                http.end(1)
                http.end(2)
                executor.shutdownNow()
            }
        }
    }

    @Test fun responseCapAndUnregisteredCallsFailWithoutLeakingTheUrl() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(Buffer().write(ByteArray(YtxLimits.HTTP_BODY_BYTES + 1)))
                    .build(),
            )
            server.start()
            val http = PyHttp(clients)
            http.begin(1)
            try {
                val failure =
                    assertFailsWith<IOException> {
                        http.execute(1, "GET", server.url("/signed?sig=secret").toString(), "{}", null, 5000, null)
                    }
                assertTrue(!failure.toString().contains("secret"))
                assertFailsWith<IOException> {
                    http.execute(2, "GET", server.url("/not-owned").toString(), "{}", null, 5000, null)
                }
            } finally {
                http.end(1)
            }
        }
    }
}
