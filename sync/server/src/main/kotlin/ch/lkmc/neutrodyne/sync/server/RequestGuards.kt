// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.ProtocolCodec
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

internal enum class RequestEncoding { IDENTITY, GZIP }

internal class BoundaryException(
    val status: HttpStatusCode,
    val code: String,
) : RuntimeException(code)

/** Count actual channel bytes even without Content-Length. Never ask Ktor to decompress requests.
 * The capped wire buffer also prevents gzip read-ahead/trailing bytes from evading the sent cap.
 */
internal class RequestGuards {
    fun install(application: Application) {
        application.intercept(ApplicationCallPipeline.Plugins) {
            val lengths =
                call.request.headers
                    .getAll(HttpHeaders.ContentLength)
                    .orEmpty()
            if (lengths.isNotEmpty()) {
                val length = lengths.singleOrNull()?.toLongOrNull()
                if (length == null || length < 0) invalidBody()
                if (length > ProtocolLimits.MAX_REQUEST_BYTES) tooLarge()
            }
            val encoding =
                call.request.headers
                    .getAll(HttpHeaders.ContentEncoding)
                    .orEmpty()
            val bodyEncoding =
                when {
                    encoding.isEmpty() -> RequestEncoding.IDENTITY
                    encoding.size == 1 && encoding.single().trim().equals("gzip", true) -> RequestEncoding.GZIP
                    else -> throw BoundaryException(HttpStatusCode.UnsupportedMediaType, "invalid")
                }
            val wire = readCapped(call.request.receiveChannel())
            val decoded = decode(wire, bodyEncoding)
            val contentType =
                call.request.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
            if (decoded.isNotEmpty() && (contentType == "application/json" || contentType?.endsWith("+json") == true)) {
                val declaredType =
                    try {
                        ContentType.parse(checkNotNull(call.request.headers[HttpHeaders.ContentType]))
                    } catch (_: BadContentTypeFormatException) {
                        invalidBody()
                    }
                val charset = declaredType.parameter("charset")
                if (charset != null && !charset.equals("utf-8", ignoreCase = true)) invalidBody()
                if (call.request.path() == "/api/v1/sync") {
                    call.attributes.put(SYNC_REQUEST, ProtocolCodec.decodeRequest(utf8(decoded)))
                } else {
                    ProtocolCodec.parse(utf8(decoded))
                }
            }
            call.attributes.put(BODY, decoded)
            checkProtocol(
                call.request.path(),
                call.request.headers
                    .getAll(PROTOCOL_HEADER)
                    .orEmpty(),
            )
        }
        // Reuse the bounded bytes for normal ContentNegotiation and receiveText callers.
        application.receivePipeline.intercept(ApplicationReceivePipeline.Before) {
            if (subject is ByteReadChannel) context.attributes.getOrNull(BODY)?.let { proceedWith(ByteReadChannel(it)) }
        }
    }

    companion object {
        const val PROTOCOL_HEADER = "Neutrodyne-Sync-Protocol"
        private val SYNC_REQUEST = AttributeKey<SyncRequest>("bounded-sync-request")
        private val BODY = AttributeKey<ByteArray>("bounded-request-body")
        private const val BUFFER_BYTES = 8_192

        fun bodySize(call: ApplicationCall): Int = call.attributes[BODY].size

        fun syncRequest(call: ApplicationCall): SyncRequest =
            call.attributes.getOrNull(SYNC_REQUEST) ?: ProtocolCodec.decodeRequest(utf8(call.attributes[BODY]))

        internal fun checkProtocol(
            path: String,
            headers: List<String>,
        ) {
            if (!path.startsWith("/api/v1/")) return
            if (headers.isEmpty()) throw BoundaryException(HttpStatusCode.UpgradeRequired, "protocol_too_old")
            val header = headers.singleOrNull()
            val version =
                header?.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
                    ?: invalidBody()
            if (version < PROTOCOL_VERSION) throw BoundaryException(HttpStatusCode.UpgradeRequired, "protocol_too_old")
            // Above max is a discovery/client compatibility decision. The server only rejects below min.
        }

        internal suspend fun readCapped(channel: ByteReadChannel): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                coroutineContext.ensureActive()
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > ProtocolLimits.MAX_REQUEST_BYTES) tooLarge()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        internal suspend fun decode(
            wire: ByteArray,
            encoding: RequestEncoding,
        ): ByteArray {
            if (wire.size > ProtocolLimits.MAX_REQUEST_BYTES) tooLarge()
            if (encoding == RequestEncoding.IDENTITY) return wire
            return withContext(Dispatchers.IO) {
                try {
                    GZIPInputStream(ByteArrayInputStream(wire)).use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size().toLong() + count > ProtocolLimits.MAX_DECOMPRESSED_BYTES) tooLarge()
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } catch (_: IOException) {
                    invalidBody()
                }
            }
        }

        internal fun utf8(bytes: ByteArray): String {
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes)
            val characters = CharBuffer.allocate(BUFFER_BYTES)
            // Validate with a fixed scratch buffer; a giant temporary UTF-16 copy would break the small heap budget.
            while (true) {
                val result = decoder.decode(input, characters, true)
                if (result.isError) invalidBody()
                if (result.isUnderflow) break
                characters.clear()
            }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun invalidBody(): Nothing = throw BoundaryException(HttpStatusCode.BadRequest, "invalid")

        private fun tooLarge(): Nothing = throw BoundaryException(HttpStatusCode.PayloadTooLarge, "payload_too_large")
    }
}
