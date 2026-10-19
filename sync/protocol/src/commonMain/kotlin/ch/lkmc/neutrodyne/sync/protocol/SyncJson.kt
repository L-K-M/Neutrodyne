// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val SyncJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

enum class JsonBody { REQUEST, RESPONSE }

/** Call after transport decompression has enforced its streaming byte cap; pre-scan before building a tree. */
object ProtocolCodec {
    fun parse(
        text: String,
        body: JsonBody = JsonBody.REQUEST,
    ): JsonElement {
        preflight(text, body)
        return try {
            SyncJson.parseToJsonElement(text).also { ProtocolValidation.json(it) }
        } catch (failure: ProtocolException) {
            throw failure
        } catch (_: SerializationException) {
            invalid("malformed JSON")
        } catch (_: IllegalArgumentException) {
            invalid("malformed JSON")
        }
    }

    fun decodeRequest(text: String): SyncRequest {
        val tree = parse(text)
        val request = decode { SyncJson.decodeFromJsonElement(SyncRequest.serializer(), tree) }
        request.since?.let(ProtocolValidation::cursor)
        if (request.changes.size > ProtocolLimits.MAX_CHANGES) {
            throw ProtocolException(SyncErrorCode.PAYLOAD_TOO_LARGE, "too many changes")
        }
        // Each change is validated independently by the writer, so a rejected change cannot fail its neighbours.
        return request
    }

    fun decodeResponse(text: String): SyncResponse {
        val response =
            decode { SyncJson.decodeFromJsonElement(SyncResponse.serializer(), parse(text, JsonBody.RESPONSE)) }
        ProtocolValidation.cursor(response.cursor)
        // Records include stale/merged extras, which are outside the page's 1,000-record limit.
        if (response.results.size > ProtocolLimits.MAX_CHANGES) invalid("too many change results")
        response.results.forEach { result ->
            if (result.i !in 0 until ProtocolLimits.MAX_CHANGES ||
                ChangeStatus.entries.none { it.wire == result.status }
            ) {
                invalid("invalid change result")
            }
            if (result.code != null &&
                SyncErrorCode.entries.none { it.wire == result.code }
            ) {
                invalid("invalid result code")
            }
        }
        return response
    }

    private fun preflight(
        text: String,
        body: JsonBody,
    ) {
        val cap =
            if (body ==
                JsonBody.REQUEST
            ) {
                ProtocolLimits.MAX_DECOMPRESSED_BYTES
            } else {
                ProtocolLimits.MAX_RESPONSE_DECOMPRESSED_BYTES
            }
        if (!fitsUtf8(
                text,
                cap.toInt(),
            )
        ) {
            throw ProtocolException(SyncErrorCode.PAYLOAD_TOO_LARGE, "JSON body exceeds cap")
        }
        var depth = 0
        var quoted = false
        var escaped = false
        var tokenLength = 0
        for (char in text) {
            if (quoted) {
                if (++tokenLength > MAX_ENCODED_TOKEN) invalid("JSON token exceeds cap")
                when {
                    escaped -> {
                        escaped = false
                    }

                    char == '\\' -> {
                        escaped = true
                    }

                    char == '"' -> {
                        quoted = false
                        tokenLength = 0
                    }
                }
                continue
            }
            when (char) {
                '"' -> {
                    quoted = true
                    tokenLength = 0
                }

                '{', '[' -> {
                    tokenLength = 0
                    if (++depth > ProtocolLimits.MAX_JSON_DEPTH) invalid("JSON too deep")
                }

                '}', ']' -> {
                    depth--
                    tokenLength = 0
                }

                ',', ':', ' ', '\t', '\r', '\n' -> {
                    tokenLength = 0
                }

                else -> {
                    if (++tokenLength > ProtocolLimits.MAX_STRING) invalid("JSON scalar exceeds cap")
                }
            }
            if (depth < 0) invalid("malformed JSON nesting")
        }
        if (depth != 0 || quoted) invalid("unterminated JSON")
    }

    private inline fun <T> decode(block: () -> T): T =
        try {
            block()
        } catch (failure: ProtocolException) {
            throw failure
        } catch (_: SerializationException) {
            invalid("invalid DTO shape")
        } catch (_: IllegalArgumentException) {
            invalid("invalid DTO value")
        }

    // A JSON unicode escape uses six ASCII bytes for one decoded character.
    private const val MAX_ENCODED_TOKEN = ProtocolLimits.MAX_STRING * 6 + 1
}
