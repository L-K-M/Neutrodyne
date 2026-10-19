// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ManifestTransportTest {
    @Test fun `bounded body subscriber accepts exact cap and cancels before cap plus one`() {
        class Subscription : Flow.Subscription {
            var cancelled = false

            override fun request(n: Long) {}

            override fun cancel() {
                cancelled = true
            }
        }
        val exact = ManifestBody()
        val accepted = Subscription()
        exact.onSubscribe(accepted)
        exact.onNext(listOf(ByteBuffer.wrap(ByteArray(65_536))))
        exact.onComplete()
        assertEquals(
            65_536,
            exact.body
                .toCompletableFuture()
                .get()
                .size,
        )
        val over = ManifestBody()
        val rejected = Subscription()
        over.onSubscribe(rejected)
        over.onNext(listOf(ByteBuffer.wrap(ByteArray(65_536))))
        over.onNext(listOf(ByteBuffer.wrap(ByteArray(1))))
        assertTrue(rejected.cancelled)
        assertFails { over.body.toCompletableFuture().get() }
    }

    @Test fun `real JDK transport is bounded and never automatically follows redirects`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var reached = 0
        server.createContext("/redirect") { call ->
            call.responseHeaders.add("Location", "/destination")
            call.sendResponseHeaders(302, -1)
            call.close()
        }
        server.createContext("/destination") { call ->
            reached++
            call.sendResponseHeaders(200, -1)
            call.close()
        }
        server.createContext("/oversize") { call ->
            runCatching {
                call.sendResponseHeaders(200, 65_537)
                call.responseBody.use { it.write(ByteArray(65_537)) }
            }
            call.close()
        }
        server.createContext("/stall") { call ->
            Thread.sleep(1000)
            runCatching { call.sendResponseHeaders(200, -1) }
            call.close()
        }
        server.createContext("/readyz") { call ->
            call.sendResponseHeaders(200, -1)
            call.close()
        }
        server.start()
        val defaultProxy = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = error("Proxy must not receive server requests")

                override fun connectFailed(
                    uri: URI,
                    address: SocketAddress,
                    failure: IOException,
                ) {}
            },
        )
        try {
            assertEquals(0, HealthCheck.check(mapOf(ServerEnv.LISTEN to "127.0.0.1:${server.address.port}")))

            fun uri(path: String) = URI("http://127.0.0.1:${server.address.port}/$path")

            fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            assertEquals(302, fetchManifestResponse(uri("redirect"), deadline()).status)
            assertEquals(0, reached)
            assertFails { fetchManifestResponse(uri("oversize"), deadline()) }
            val before = System.nanoTime()
            assertFails { fetchManifestResponse(uri("stall"), before + TimeUnit.MILLISECONDS.toNanos(200)) }
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(3))
        } finally {
            ProxySelector.setDefault(defaultProxy)
            server.stop(0)
        }
    }
}
