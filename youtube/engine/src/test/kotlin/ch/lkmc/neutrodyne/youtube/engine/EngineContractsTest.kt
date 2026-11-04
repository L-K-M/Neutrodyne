// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeEngineStorePaths
import ch.lkmc.neutrodyne.core.testing.FakeYtxTestArming
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineContractsTest {
    @Test fun goldenWireUnitsAndRedaction() {
        val text = checkNotNull(javaClass.getResource("/contract-v1/resolve.json")).readText()
        val result = Json.decodeFromString<YtxResolve>(text)
        assertEquals("140-drc", result.formats.single().formatId)
        assertEquals(12.5, result.duration)
        assertEquals(1_800_000_000.5, result.formats.single().availableAt)
        assertFalse(result.toString().contains("secret"))
        assertEquals(result, Json.decodeFromString<YtxResolve>(Json.encodeToString(result)))
        val request = YtxVideoRequest(YtxContext("en", "US", "private-agent"), "abcdefghijk")
        assertEquals(
            """{"ctx":{"hl":"en","gl":"US","ua":"private-agent"},"videoId":"abcdefghijk"}""",
            Json.encodeToString(request),
        )
        assertFalse(request.toString().contains("private-agent"))
        assertFalse(YtxCursorRequest(request.ctx, "secret").toString().contains("secret"))
        assertFalse(YtxCallException("https://secret").toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> { YtxContext("en", "US", "ua", "bad") }
    }

    @Test fun factsCarryTruncationAndSecondsWithoutFormats() {
        val facts =
            Json.decodeFromString<YtxFacts>(
                """{"videoId":"abcdefghijk","duration":12.5,"releaseTs":1800000000,"description":"0:00 intro","descriptionTruncated":true}""",
            )
        assertTrue(facts.descriptionTruncated)
        assertEquals(1_800_000_000L, facts.releaseTs)
        assertEquals(32 * 1024, YtxLimits.DESCRIPTION_UTF8_BYTES)
        assertEquals(16 * 1024, YtxLimits.PAYLOAD_BYTES)
        assertEquals(128 * 1024, YtxLimits.RESULT_BYTES)
    }

    @Test fun transportFakeForwardsAbsoluteDeadlineFailureAndCancellation() =
        runTest {
            val fake = FakeYtxTransport()
            assertFailsWith<YtxCallException> { fake.call(YtxMethod.RESOLVE, "secret", YtxDeadline(123)) }
            assertEquals(YtxDeadline(123), fake.calls.single().deadline)
            assertFalse(
                fake.calls
                    .single()
                    .toString()
                    .contains("secret"),
            )
            fake.answer = { CompletableDeferred<String>().await() }
            val call = async { fake.call(YtxMethod.FACTS, "{}", YtxDeadline(456)) }
            runCurrent()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            fake.cancel(7)
            fake.prewarm(PrewarmReason.SCREEN)
            fake.shutdown()
            assertEquals(listOf(fake.calls.last().id, 7L), fake.cancelled)
            assertEquals(listOf(PrewarmReason.SCREEN), fake.prewarms)
            assertEquals(1, fake.shutdowns)
        }

    @Test fun storeSettingsPreflightAndArmingHaveExplicitStates() =
        runTest {
            val paths = FakeEngineStorePaths()
            paths.zip = byteArrayOf(1, 2)
            assertEquals(1, paths.openBundledZip().buffer().use { it.readByte().toInt() })
            assertEquals("3.14", paths.pythonMinor)
            val store = FakeEngineStore()
            assertNull(store.hostLibDir())
            assertFailsWith<IllegalStateException> { store.prepareBundled() }
            val marker = CompileMarker(1, "bundled", "3.14")
            val prepared = PreparedEngine("/test/lib".toPath(), marker)
            store.prepare = { prepared }
            assertEquals(prepared, store.prepareBundled())
            store.loadStatus()
            assertEquals(1, store.loads)
            val settings = FakeEngineSettings()
            settings.awaitLoaded()
            settings.recordStartFailures(marker, 3)
            assertEquals(listOf(marker to 3), settings.writes)
            val preflight = FakeEnginePlatformPreflight()
            preflight.result = PlatformLimitation.X86_64_API_26_27
            assertEquals(preflight.result, preflight.limitation())
            val arming = FakeYtxTestArming()
            assertNull(arming.directory)
            arming.arm("/recorded".toPath())
            assertEquals("/recorded".toPath(), arming.directory)
            arming.clear()
            assertNull(arming.directory)
        }
}
