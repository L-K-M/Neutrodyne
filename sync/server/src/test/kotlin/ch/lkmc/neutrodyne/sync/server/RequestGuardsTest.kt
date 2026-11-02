// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestGuardsTest {
    @Test fun `exact sent cap accepted and cap plus one rejected with or without length`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            for (chunked in listOf(false, true)) {
                for (extra in listOf(0, 1)) {
                    val bytes = paddedJson(ProtocolLimits.MAX_REQUEST_BYTES.toInt() + extra)
                    val response =
                        client.post("/echo") {
                            header(HttpHeaders.ContentType, "application/json")
                            if (chunked) setBody(streaming(bytes)) else setBody(bytes)
                        }
                    assertEquals(if (extra == 0) HttpStatusCode.OK else HttpStatusCode.PayloadTooLarge, response.status)
                }
            }
        }

    @Test fun `gzip exact decoded cap accepted and cap plus one rejected including chunked`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            for (chunked in listOf(false, true)) {
                for (extra in listOf(0, 1)) {
                    val compressed = gzip(paddedJson(ProtocolLimits.MAX_DECOMPRESSED_BYTES.toInt() + extra))
                    val response =
                        client.post("/echo") {
                            header(HttpHeaders.ContentType, "application/json")
                            header(HttpHeaders.ContentEncoding, "gzip")
                            if (chunked) setBody(streaming(compressed)) else setBody(compressed)
                        }
                    assertEquals(if (extra == 0) HttpStatusCode.OK else HttpStatusCode.PayloadTooLarge, response.status)
                }
            }
        }

    @Test fun `gzip sent cap counts all trailing bytes even beyond decompressor read ahead`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            val compressed = gzip("{}".toByteArray())
            for (extra in listOf(0, 1)) {
                val padded = compressed.copyOf(ProtocolLimits.MAX_REQUEST_BYTES.toInt() + extra)
                val response =
                    client.post("/echo") {
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(streaming(padded))
                    }
                assertEquals(if (extra == 0) HttpStatusCode.OK else HttpStatusCode.PayloadTooLarge, response.status)
            }
        }

    @Test fun `bad truncated and CRC damaged gzip are request errors`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            val good = gzip("{}".toByteArray())
            val damaged = good.clone().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
            for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2), good.copyOf(good.size - 1), damaged)) {
                val response =
                    client.post("/echo") {
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(bytes)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

    @Test fun `unsupported and compound encodings return 415`() =
        testApplication {
            application { installTestModule(loadValidConfig()) }
            for (encoding in listOf("br", "gzip, gzip", "identity", "deflate")) {
                assertEquals(
                    HttpStatusCode.UnsupportedMediaType,
                    client
                        .post("/echo") {
                            header(HttpHeaders.ContentEncoding, encoding)
                            setBody("{}")
                        }.status,
                )
            }
        }

    @Test fun `bounded JSON accepts depth sixteen and rejects seventeen malformed and huge scalars`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            for ((body, status) in listOf(
                "[".repeat(16) + "0" + "]".repeat(16) to HttpStatusCode.OK,
                "[".repeat(17) + "0" + "]".repeat(17) to HttpStatusCode.BadRequest,
                "{\"x\":\"[{}]\\\"\"}" to HttpStatusCode.OK,
                "{\"x\":1" to HttpStatusCode.BadRequest,
                "{\"x\":" + "1".repeat(4097) + "}" to HttpStatusCode.BadRequest,
            )) {
                val response =
                    client.post("/echo") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(body)
                    }
                assertEquals(status, response.status)
            }
        }

    @Test fun `escaped UTF8 strings are bounded by decoded bytes not ASCII escape length`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            for (count in listOf(2048, 2049)) {
                val body = "{\"extension\":\"" + "\\u00e9".repeat(count) + "\"}"
                val response =
                    client.post("/echo") {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(body)
                    }
                assertEquals(if (count == 2048) HttpStatusCode.OK else HttpStatusCode.BadRequest, response.status)
            }
            assertFailsWith<BoundaryException> { RequestGuards.utf8(byteArrayOf(0xc3.toByte(), 0x28)) }
        }

    @Test fun `one thousand changes accepted one more fails before route and typed shapes fail whole request`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing {
                    post("/api/v1/sync") {
                        call.respondText(
                            RequestGuards
                                .syncRequest(call)
                                .changes.size
                                .toString(),
                        )
                    }
                }
            }
            for (count in listOf(1000, 1001)) {
                val changes = List(count) { """{"coll":"setting","id":"x"}""" }.joinToString(",")
                val body = """{"changes":[$changes]}"""
                val response =
                    client.post("/api/v1/sync") {
                        header(RequestGuards.PROTOCOL_HEADER, "1")
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(body)
                    }
                assertEquals(if (count == 1000) HttpStatusCode.OK else HttpStatusCode.PayloadTooLarge, response.status)
            }
            val malformed =
                client.post("/api/v1/sync") {
                    header(RequestGuards.PROTOCOL_HEADER, "1")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("{\"changes\":[{\"coll\":4,\"id\":\"x\"}]}")
                }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
        }

    @Test fun `semantic failures remain independent while decoded bytes support content negotiation`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing {
                    post("/api/v1/sync") {
                        val request = call.receive<SyncRequest>()
                        val statuses =
                            request.changes.map { change ->
                                runCatching { ProtocolValidation.change(change) }.isSuccess
                            }
                        call.respondText(statuses.joinToString(","))
                    }
                }
            }
            val body =
                """{"changes":[{"coll":"unknown","id":"x"},""" +
                    """{"coll":"setting","id":"x","fields":{"v":{"v":null,""" +
                    """"c":"01995e7600000000-0000000000000001"}}}]}"""
            val response =
                client.post("/api/v1/sync") {
                    header(RequestGuards.PROTOCOL_HEADER, "1")
                    header(HttpHeaders.ContentType, "application/json")
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(gzip(body.toByteArray()))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("false,true", response.bodyAsText())
        }

    @Test fun `protocol policy covers missing below minimum invalid duplicate and above maximum`() {
        for (headers in listOf(emptyList(), listOf("0"))) {
            val failure =
                assertFailsWith<BoundaryException> {
                    RequestGuards.checkProtocol("/api/v1/sync", headers)
                }
            assertEquals(HttpStatusCode.UpgradeRequired, failure.status)
        }
        for (headers in listOf(
            listOf("x"),
            listOf("1", "1"),
            listOf("-1"),
            listOf("1,2"),
            listOf("9999999999999999"),
        )) {
            val failure =
                assertFailsWith<BoundaryException> {
                    RequestGuards.checkProtocol("/api/v1/sync", headers)
                }
            assertEquals(HttpStatusCode.BadRequest, failure.status)
        }
        RequestGuards.checkProtocol("/api/v1/sync", listOf("1"))
        RequestGuards.checkProtocol("/api/v1/sync", listOf("2"))
        RequestGuards.checkProtocol("/readyz", emptyList())
    }

    @Test fun `body reading and decompression preserve cancellation`() =
        runBlocking<Unit> {
            val channel = ByteChannel()
            val read = launch { RequestGuards.readCapped(channel) }
            read.cancelAndJoin()
            assertTrue(read.isCancelled)
            val job =
                async {
                    currentCoroutineContext().cancel()
                    RequestGuards.decode(gzip(paddedJson(1024)), RequestEncoding.GZIP)
                }
            assertFailsWith<CancellationException> { job.await() }
        }

    @Test
    fun `JSON requires UTF8 even when another charset is declared`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { post("/echo") { call.respondText("ok") } }
            }
            for (charset in listOf("UTF-8", "ISO-8859-1", "UTF-16")) {
                val response =
                    client.post("/echo") {
                        header(HttpHeaders.ContentType, "application/json; charset=$charset")
                        setBody("""{"text":"é"}""".toByteArray(Charsets.UTF_8))
                    }
                assertEquals(if (charset == "UTF-8") HttpStatusCode.OK else HttpStatusCode.BadRequest, response.status)
            }
        }

    private fun paddedJson(size: Int) =
        ByteArray(size) { ' '.code.toByte() }.also {
            it[0] = '{'.code.toByte()
            it[1] = '}'.code.toByte()
        }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun streaming(bytes: ByteArray) =
        object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.Json

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(bytes)
            }
        }
}
