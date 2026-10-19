// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs only in mutationTest's 128-MiB fork; any stack/heap exhaustion fails the task. */
class ProtocolHostileInputTest {
    private val clock = "01a10c942d800001-0000000000000001"
    private val id = JsonPrimitive("11111111-1111-4111-8111-111111111111")

    @Test
    fun suiteActuallyRunsWithTheCappedHeap() {
        assertTrue(Runtime.getRuntime().maxMemory() <= CAPPED_HEAP_BYTES, "hostile tests must run in mutationTest")
    }

    @Test
    fun deepJsonIsRejectedBeforeRecursiveDecoding() {
        reject("[".repeat(50_000) + "0" + "]".repeat(50_000))
        reject("{\"x\":".repeat(20_000) + "0" + "}".repeat(20_000))
        ProtocolCodec.parse("[".repeat(16) + "0" + "]".repeat(16))
        reject("[".repeat(17) + "0" + "]".repeat(17))
    }

    @Test
    fun tokenAndDecodedUtf8CapsHandleEscapesAndSurrogates() {
        reject("\"" + "x".repeat(1_000_000) + "\"")
        reject("\"" + "9".repeat(100_000) + "\"")
        reject("9".repeat(100_000))
        reject("\"" + "\\u00e9".repeat(2_049) + "\"")
        reject("\"" + "\\ud83d\\ude00".repeat(1_025) + "\"")
        val escaped = "\"" + "\\u0061".repeat(4_096) + "\""
        assertEquals("a".repeat(4_096), (ProtocolCodec.parse(escaped) as JsonPrimitive).content)
        // Brackets and escaped quotes inside strings must not consume nesting depth.
        ProtocolCodec.parse("{\"x\":\"[\\\"{\\\"}]\"}")
    }

    @Test
    fun decompressedRequestCapIsCheckedBeforeTreeAllocation() {
        val oversized = " ".repeat(ProtocolLimits.MAX_DECOMPRESSED_BYTES.toInt() + 1)
        reject(oversized, SyncErrorCode.PAYLOAD_TOO_LARGE)
        // A response has its separate larger cap; this valid tiny object may have whitespace padding.
        assertEquals(ProtocolCodec.parse("{}"), ProtocolCodec.parse(oversized + "{}", JsonBody.RESPONSE))
    }

    @Test
    fun admissionAndRecordCapsRejectWideOrMalformedPayloads() {
        val field = FieldValue(JsonPrimitive("x"), clock)
        val valid = ChangeDto("podcast", id, fields = mapOf("title" to field))
        val wide = valid.copy(fields = (0..64).associate { "f$it" to field })
        assertEquals(SyncErrorCode.INVALID, assertFailsWith<ProtocolException> { ProtocolValidation.change(wide) }.code)
        val batch = SyncRequest(changes = List(1_001) { valid })
        val body = SyncJson.encodeToString(SyncRequest.serializer(), batch)
        assertEquals(
            SyncErrorCode.PAYLOAD_TOO_LARGE,
            assertFailsWith<ProtocolException> { ProtocolCodec.decodeRequest(body) }.code,
        )
        listOf(
            "x".repeat(100_000),
            "ffffffffffffffff-0000000000000001",
            "01a10c942d800001-FFFFFFFFFFFFFFFF",
        ).forEach { malformed ->
            assertEquals(
                SyncErrorCode.INVALID,
                assertFailsWith<ProtocolException> {
                    ProtocolValidation.change(valid.copy(fields = mapOf("title" to field.copy(c = malformed))))
                }.code,
            )
        }
    }

    @Test
    fun malformedShapesAndSeededMutationsOnlyProduceBoundedProtocolFailures() {
        listOf(
            "",
            "{",
            "[}",
            "{\"x\":",
            "{\"x\":NaN}",
            "\"unterminated",
            "{\"changes\":{}}",
            "{\"changes\":[{\"coll\":\"podcast\",\"id\":1,\"fields\":{\"title\":{\"reset\":\"true\"}}}]}",
        ).forEach { text ->
            assertFailsWith<ProtocolException>(text) { ProtocolCodec.decodeRequest(text) }
        }
        val seed = 0x51A7
        val random = Random(seed)
        val original =
            "{\"changes\":[{\"coll\":\"podcast\",\"id\":$id," +
                "\"fields\":{\"title\":{\"v\":\"title\",\"c\":\"$clock\"}}}]}"
        val iterations = System.getProperty("neutrodyne.mutationIterations", "20").toInt()
        repeat(iterations * MUTATIONS_PER_ITERATION) { operation ->
            val at = random.nextInt(original.length)
            val mutated = original.replaceRange(at, at + 1, random.nextInt(128).toChar().toString())
            try {
                ProtocolCodec.decodeRequest(mutated).changes.forEach {
                    ProtocolValidation.change(
                        it,
                        1_791_212_400_000,
                    )
                }
            } catch (_: ProtocolException) {
                // Expected shape/type/admission rejection. Other exceptions fail with a reproducible trace.
            } catch (failure: Throwable) {
                throw AssertionError("seed=$seed operation=$operation at=$at", failure)
            }
        }
    }

    private fun reject(
        text: String,
        code: SyncErrorCode = SyncErrorCode.INVALID,
    ) {
        assertEquals(code, assertFailsWith<ProtocolException> { ProtocolCodec.parse(text) }.code)
    }

    private companion object {
        const val CAPPED_HEAP_BYTES = 128L shl 20
        const val MUTATIONS_PER_ITERATION = 50
    }
}
