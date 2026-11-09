// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.HexFormat

/**
 * [RecordedMediaScenario]'s adaptation contract (MD3 AC2): the recorded `resolve` scenario
 * copied with only response bodies rewritten — every request triple byte-exact, the
 * recorded placeholders and length required, itag/mime/duration/expiry/signature
 * preserved — and every refusal closed: external or non-loopback URLs, a missing or
 * mutated scenario, and writing over the recording.
 */
class RecordedMediaScenarioTest {
    private val repoRoot = File(System.getProperty("user.dir")).canonicalFile.parentFile
    private val sourceDir = repoRoot.resolve("youtube/engine/src/test/resources/recorded/resolve")

    private fun stubHead(): ByteArray =
        HexFormat.of().parseHex("0000001c667479704d344120000002004d34412069736f6d69736f32")

    private fun fixture(): LoopbackMediaFixture = LoopbackMediaFixture(FixtureMediaBytes(stubHead(), TOTAL_BYTES))

    private fun targetDir(): File = Files.createTempDirectory("nd-media-scenario").toFile()

    private fun failure(block: () -> Unit): String =
        runCatching { block() }.exceptionOrNull()?.message ?: error("expected a refusal")

    @Test
    fun `the recorded resolve scenario exists`() {
        assertThat(File(sourceDir, "http.json").isFile).isTrue()
    }

    @Test
    fun `rewrites only response bodies keeping every request byte exact`() {
        fixture().use { fixture ->
            val source = File(sourceDir, "http.json")
            val original = source.readText()
            val out =
                RecordedMediaScenario.rewriteResolve(sourceDir, targetDir(), fixture.mediaUrl, fixture.media.size)

            val src = Json.parseToJsonElement(original).jsonObject
            val dst = Json.parseToJsonElement(out.readText()).jsonObject

            // Identical shape: same keys in the same order, every member but `bodies` untouched.
            assertThat(dst.keys.toList()).isEqualTo(src.keys.toList())
            for (key in src.keys - "bodies") {
                assertThat(dst[key]).isEqualTo(src[key])
            }

            // Every recorded request and every response projection byte-exact, in order —
            // no wildcarded matcher, no touched request key.
            val srcExchanges = src.getValue("exchanges").jsonArray
            val dstExchanges = dst.getValue("exchanges").jsonArray
            assertThat(dstExchanges.map { it.jsonObject.getValue("request").toString() })
                .containsExactlyElementsIn(srcExchanges.map { it.jsonObject.getValue("request").toString() })
                .inOrder()
            assertThat(dstExchanges.map { it.jsonObject.getValue("response").toString() })
                .containsExactlyElementsIn(srcExchanges.map { it.jsonObject.getValue("response").toString() })
                .inOrder()

            // Bodies differ only by the three intended replacements — inverting them
            // restores the recording byte for byte.
            val srcBodies = src.getValue("bodies").jsonArray.map { it.jsonPrimitive.content }
            val dstBodies = dst.getValue("bodies").jsonArray.map { it.jsonPrimitive.content }
            assertThat(dstBodies).hasSize(srcBodies.size)
            for ((index, rewritten) in dstBodies.withIndex()) {
                assertThat(
                    rewritten
                        .replace(fixture.mediaUrl, "https://media.example.invalid/audio")
                        .replace("clen=$TOTAL_BYTES", "clen=1000")
                        .replace("\"contentLength\": \"$TOTAL_BYTES\"", "\"contentLength\": \"1000\""),
                ).isEqualTo(srcBodies[index])
            }
            val joined = dstBodies.joinToString("\n")
            assertThat(joined).doesNotContain("media.example.invalid")
            assertThat(joined).contains(fixture.mediaUrl)
            assertThat(joined).contains("clen=$TOTAL_BYTES")
            assertThat(joined).contains("\"contentLength\": \"$TOTAL_BYTES\"")
            // itag/mime/duration/expiry/signature ride along untouched.
            for (kept in listOf("itag=140", "expire=4102444800", "sig=X", "mp4a.40.2", "\"120000\"")) {
                assertThat(joined).contains(kept)
            }

            // Scenario siblings ship verbatim; the recording is never mutated.
            for (sibling in listOf("request.json", "result.json")) {
                assertThat(out.parentFile.resolve(sibling).readBytes())
                    .isEqualTo(sourceDir.resolve(sibling).readBytes())
            }
            assertThat(source.readText()).isEqualTo(original)
        }
    }

    @Test
    fun `the rewritten body resolves to the fixture url and declared length`() {
        fixture().use { fixture ->
            val out =
                RecordedMediaScenario.rewriteResolve(sourceDir, targetDir(), fixture.mediaUrl, fixture.media.size)
            val bodies =
                Json
                    .parseToJsonElement(out.readText())
                    .jsonObject
                    .getValue("bodies")
                    .jsonArray
            val player =
                bodies
                    .mapNotNull {
                        runCatching {
                            Json
                                .parseToJsonElement(
                                    it.jsonPrimitive.content,
                                ).jsonObject
                        }.getOrNull()
                    }.first { "streamingData" in it }
            val format =
                player
                    .getValue("streamingData")
                    .jsonObject
                    .getValue("adaptiveFormats")
                    .jsonArray
                    .single()
                    .jsonObject
            assertThat(format.getValue("itag").jsonPrimitive.content).isEqualTo("140")
            assertThat(format.getValue("url").jsonPrimitive.content)
                .isEqualTo(
                    "${fixture.mediaUrl}?itag=140&clen=$TOTAL_BYTES&lmt=123&ip=0.0.0.0" +
                        "&expire=4102444800&sig=X",
                )
            assertThat(format.getValue("contentLength").jsonPrimitive.content).isEqualTo("$TOTAL_BYTES")
            assertThat(format.getValue("mimeType").jsonPrimitive.content).startsWith("audio/mp4")
            assertThat(format.getValue("approxDurationMs").jsonPrimitive.content).isEqualTo("120000")
        }
    }

    @Test
    fun `the rewritten url is answered by the actually bound fixture`() {
        fixture().use { fixture ->
            // The port in the URL is the listener's real bound port — proven by serving it.
            Socket(InetAddress.getByName("127.0.0.1"), fixture.port).use { socket ->
                socket.soTimeout = 5_000
                socket.outputStream.write(
                    "GET /audio?itag=140&clen=$TOTAL_BYTES&sig=X HTTP/1.1\r\n\r\n".toByteArray(),
                )
                socket.outputStream.flush()
                val head = socket.inputStream.readNBytes(15)
                assertThat(String(head).take(12)).isEqualTo("HTTP/1.1 200")
            }
        }
    }

    @Test
    fun `refuses external and non-loopback media urls`() {
        fixture().use { fixture ->
            val target = targetDir()
            for (
            bad in
            listOf(
                "https://127.0.0.1:${fixture.port}/audio",
                "http://localhost:${fixture.port}/audio",
                "http://[::1]:${fixture.port}/audio",
                "http://10.0.0.4:${fixture.port}/audio",
                "https://media.example.invalid/audio",
                "http://127.0.0.1/audio",
                "http://127.0.0.1:${fixture.port}/evil",
                "http://127.0.0.1:${fixture.port}/audio?x=1",
                "http://user@127.0.0.1:${fixture.port}/audio",
                "not a url",
            )
            ) {
                assertThat(failure { RecordedMediaScenario.rewriteResolve(sourceDir, target, bad, TOTAL_BYTES) })
                    .isNotEmpty()
            }
            for (badLength in listOf(0L, -1L)) {
                assertThat(
                    failure {
                        RecordedMediaScenario.rewriteResolve(sourceDir, target, fixture.mediaUrl, badLength)
                    },
                ).isNotEmpty()
            }
        }
    }

    @Test
    fun `refuses scenarios without the recorded placeholders`() {
        val template =
            """{"provenance":"unit test","bodies":[%s],""" +
                """"exchanges":[{"request":["GET","https://x",null],"response":{"status":200,"bodyRef":0}}]}"""
        // No media URL at all.
        var scenario = targetDir()
        File(scenario, "http.json").writeText(template.format("\"{}\""))
        assertThat(
            failure {
                RecordedMediaScenario.rewriteResolve(scenario, targetDir(), "http://127.0.0.1:9/audio", TOTAL_BYTES)
            },
        ).contains("media URL placeholder")

        // The URL but a different recorded length is not our fixture's recording.
        scenario = targetDir()
        File(scenario, "http.json").writeText(
            template.format(
                """"{\"u\":\"https://media.example.invalid/audio?clen=2000\",\"contentLength\":\"2000\"}"""",
            ),
        )
        assertThat(
            failure {
                RecordedMediaScenario.rewriteResolve(scenario, targetDir(), "http://127.0.0.1:9/audio", TOTAL_BYTES)
            },
        ).contains("clen")

        // clen present, contentLength missing.
        scenario = targetDir()
        File(scenario, "http.json").writeText(
            template.format(
                """"{\"u\":\"https://media.example.invalid/audio?clen=1000\"}"""",
            ),
        )
        assertThat(
            failure {
                RecordedMediaScenario.rewriteResolve(scenario, targetDir(), "http://127.0.0.1:9/audio", TOTAL_BYTES)
            },
        ).contains("contentLength")

        // Bodies that are not a string table, and a missing scenario file.
        scenario = targetDir()
        File(scenario, "http.json").writeText(template.format("1"))
        assertThat(
            failure {
                RecordedMediaScenario.rewriteResolve(scenario, targetDir(), "http://127.0.0.1:9/audio", TOTAL_BYTES)
            },
        ).isNotEmpty()
        assertThat(
            failure {
                RecordedMediaScenario.rewriteResolve(targetDir(), targetDir(), "http://127.0.0.1:9/audio", TOTAL_BYTES)
            },
        ).contains("http.json")
    }

    @Test
    fun `refuses to write over the recorded scenario`() {
        assertThat(
            failure {
                RecordedMediaScenario.rewriteResolve(sourceDir, sourceDir, "http://127.0.0.1:9/audio", TOTAL_BYTES)
            },
        ).contains("recording")
    }

    private companion object {
        private const val TOTAL_BYTES = 21L * 1024 * 1024 + 123
    }
}
