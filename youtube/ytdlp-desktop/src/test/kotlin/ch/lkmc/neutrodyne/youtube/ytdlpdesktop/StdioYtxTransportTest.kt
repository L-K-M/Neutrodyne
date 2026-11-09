// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC1: the M9 recorded-response vectors replayed through the real stdio host — the shared
 * `neutrodyne_ytx` shim, the real bundled yt-dlp, `ReplayRH` answering the recorded exchanges.
 * No request leaves the box; an unrecorded one poisons the call instead of reaching YouTube.
 */
class StdioYtxTransportTest {
    private val roots = mutableListOf<File>()

    @AfterTest fun cleanup() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    private fun fixture(): StdioFixture = StdioFixture(tempRoot("vec").also { roots += it })

    private fun scenario(name: String) =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport(replay = recordedScenario(name))
            val (methodName, payload) = recordedRequest(name)
            val method = YtxMethod.entries.first { it.wireName == methodName }
            val result =
                withTimeout(60_000) {
                    transport.call(method, payload, fixture.deadline(60_000))
                }
            assertEquals(
                normalized(recordedResult(name)),
                normalized(result),
                "scenario $name",
            )
            transport.shutdown()
            Unit
        }

    @Test fun recordedPingVector() = scenario("ping")

    @Test fun recordedVersionVector() = scenario("version")

    @Test fun recordedSelftestVector() = scenario("selftest")

    @Test fun recordedResolveVector() = scenario("resolve")

    @Test fun recordedFactsVector() = scenario("facts")

    @Test fun recordedLookupVector() = scenario("lookup")

    @Test fun recordedTabVector() = scenario("tab")

    @Test fun recordedSearchVector() = scenario("search")

    @Test fun readyHostReportsItsIdentity() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport(replay = recordedScenario("ping"))
            transport.call(YtxMethod.PING, fixture.ping.toJson(), fixture.deadline(30_000))
            val status = transport.status.value
            assertEquals(HostState.READY, status.state)
            assertEquals(BUNDLED_VERSION, status.engineVersion)
            assertNotNull(status.pid)
            assertNotNull(status.python)
            transport.shutdown()
        }

    /**
     * The full prepare path: a fresh uncompiled tree announces `compiling`, the child compiles
     * it, the transport writes `.compiled-<minor>` and the store flips to COMPILED.
     */
    @Test fun firstCallCompilesThePreparedTree() =
        runBlocking {
            val fixture = fixture()
            val store = BundledEngineStore(fixture.paths, 1, fixture.clock)
            store.prepareBundled()
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            val transport =
                StdioYtxTransport(
                    fixture.spawner,
                    store,
                    fixture.settings,
                    arming = null,
                    debugHooks = true,
                    clock = fixture.clock,
                    scope = fixture.scope,
                    ping = fixture.ping,
                    idleStopMs = 500,
                    startTimeoutMs = 30_000,
                    compileTimeoutMs = 120_000,
                    hangGraceMs = 150,
                )
            val compiling =
                fixture.scope.async {
                    transport.status.first { it.state == HostState.COMPILING }
                }
            transport.call(YtxMethod.PING, fixture.ping.toJson(), fixture.deadline(180_000))
            assertTrue(withTimeoutOrNull(180_000) { compiling.await() } != null)
            assertEquals(HostState.READY, transport.status.value.state)
            val prepared = store.hostPrepared()
            assertNotNull(prepared)
            val marker = prepared.libDir.toFile().resolve(".compiled-${fixture.paths.pythonMinor}")
            assertTrue(marker.isFile, "compile marker missing")
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            transport.shutdown()
        }

    /**
     * Environment- and wall-clock-derived members do not survive a process boundary:
     * `availableAt` at any depth, plus the host's own `python`/`host` strings. Everything
     * else must match the recording exactly.
     */
    private fun normalized(json: String): JsonElement = dropEnv(Json.parseToJsonElement(json))

    private fun dropEnv(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element
                        .filterKeys { it != "availableAt" && it != "python" && it != "host" }
                        .mapValues { (_, value) -> dropEnv(value) },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::dropEnv))
            }

            else -> {
                element
            }
        }
}
