// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStorePaths
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.HostExit
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trial compile-budget boundary (04 host contract): an authorized self-test on a staged
 * candidate that has no compile marker yet runs inside the 120 s first-compile cap, while a
 * compiled host keeps the 15 s start limit and ordinary traffic never binds unprepared. The
 * store is the real [BundledEngineStore] on a real fixture tree; only the Binder endpoint is
 * scripted, with virtual time driving the delays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BinderTrialCompileBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun allowCleanup() {
        temporary.root.walkTopDown().forEach { it.setWritable(true, true) }
    }

    @Test fun authorizedTrialSelftestSurvivesASlowFirstCompile() =
        runTest {
            val f = Fixture(this)
            compileBundled(f)
            stageCandidate(f)
            f.store.beginTrial(CANDIDATE)
            // First import of the uncompiled candidate answers the startup ping after 90 s:
            // past the 15 s start limit, within the 120 s first-compile cap.
            f.endpoint.pingDelayMs = 90_000
            val call = async { f.selftest(budgetMs = 150_000) }
            runCurrent()
            advanceTimeBy(YtxLimits.COMPILE_MS)
            runCurrent()
            assertEquals("""{"ok":true,"missing":[]}""", call.await())
            assertEquals(HostState.READY, f.transport.status.value.state)
            assertEquals(1, f.connection.binds)
        }

    @Test fun compiledServingEngineStalledPastThePingKeepsTheFifteenSecondBudget() =
        runTest {
            val f = Fixture(this)
            compileBundled(f)
            f.endpoint.pingDelayMs = 90_000
            val call = async { runCatching { f.resolve(budgetMs = 150_000) }.exceptionOrNull() }
            advanceTimeBy(YtxLimits.START_MS + 1)
            runCurrent()
            assertEquals("ENGINE_UNAVAILABLE", (call.await() as YtxCallException).code)
            assertEquals(HostExit.START_FAILED, f.transport.status.value.lastExit)
            assertEquals(1, f.connection.binds)
        }

    @Test fun uncompiledTrialRejectsOrdinaryResolveWithoutBinding() =
        runTest {
            val f = Fixture(this)
            compileBundled(f)
            stageCandidate(f)
            f.store.beginTrial(CANDIDATE)
            assertEquals(EnginePreparation.PREPARING, f.store.preparation.value)
            assertEquals(
                CANDIDATE,
                f.store
                    .hostPrepared()!!
                    .marker.engineVersion,
            )
            val failure = assertFailsWith<YtxCallException> { f.resolve(budgetMs = 150_000) }
            assertEquals("ENGINE_UNAVAILABLE", failure.code)
            assertEquals(0, f.connection.binds)
        }

    @Test fun compiledCandidateTrialKeepsTheFifteenSecondBudget() =
        runTest {
            val f = Fixture(this)
            compileBundled(f)
            val candidate = stageCandidate(f)
            compileTree(candidate)
            f.store.beginTrial(CANDIDATE)
            assertEquals(EnginePreparation.COMPILED, f.store.preparation.value)
            f.endpoint.pingDelayMs = 20_000
            val call = async { runCatching { f.selftest(budgetMs = 150_000) }.exceptionOrNull() }
            advanceTimeBy(YtxLimits.START_MS + 1)
            runCurrent()
            assertEquals("ENGINE_UNAVAILABLE", (call.await() as YtxCallException).code)
            assertEquals(HostExit.START_FAILED, f.transport.status.value.lastExit)
        }

    @Test fun clearTrialRestoresTheCompiledActiveWithoutARejection() =
        runTest {
            val f = Fixture(this)
            val active = compileBundled(f)
            stageCandidate(f)
            f.store.beginTrial(CANDIDATE)
            assertEquals(EnginePreparation.PREPARING, f.store.preparation.value)
            f.store.clearTrial()
            assertEquals(EnginePreparation.COMPILED, f.store.preparation.value)
            assertEquals(active, f.store.hostPrepared())
            assertNull(f.store.active.value.trial)
            assertEquals(CANDIDATE, f.store.active.value.staged)
            assertTrue(
                f.store.active.value.rejected
                    .isEmpty(),
            )
        }

    private inner class Fixture(
        val scope: TestScope,
    ) {
        val settings = FakeEngineSettings()
        val endpoint = Endpoint(scope)
        val connection = Connection(endpoint)
        val paths =
            FakeEngineStorePaths().apply {
                root =
                    temporary.root.canonicalFile
                        .resolve("ytdlp")
                        .path
                        .toPath()
                ytDlpCacheDir =
                    temporary.root.canonicalFile
                        .resolve("cache/yt-dlp")
                        .path
                        .toPath()
                arm(this, BUNDLED)
            }
        val clock = TestClock.from(scope.testScheduler)

        // The store's IO hop runs on the shared virtual scheduler: the real filesystem and
        // logic, but one deterministic timeline with the transport's timers.
        val store = BundledEngineStore(paths, 1, clock, StandardTestDispatcher(scope.testScheduler))
        val transport =
            BinderYtxTransport(
                connection,
                store,
                clock,
                scope.backgroundScope,
                YtxControlRequest(YtxContext("en", "US", "trial-budget/1")),
                settings,
            )

        suspend fun selftest(budgetMs: Long): String =
            transport.call(
                YtxMethod.SELFTEST,
                """{"ctx":{"hl":"en","gl":"US","ua":"trial-budget/1"}}""",
                YtxDeadline(clock.elapsedRealtime() + budgetMs),
            )

        suspend fun resolve(budgetMs: Long): String =
            transport.call(
                YtxMethod.RESOLVE,
                """{"ctx":{"hl":"en","gl":"US","ua":"trial-budget/1"},"videoId":"abc"}""",
                YtxDeadline(clock.elapsedRealtime() + budgetMs),
            )
    }

    private class Connection(
        val endpoint: Endpoint,
    ) : YtxConnection {
        var binds = 0

        override suspend fun bind(died: () -> Unit): IYtxEngine {
            binds++
            return endpoint
        }

        override fun stop(pid: Long?) {}
    }

    /** The startup ping answers only after [pingDelayMs] of virtual time: the first compile. */
    private class Endpoint(
        private val scope: TestScope,
    ) : IYtxEngine.Stub() {
        var pingDelayMs = 0L

        override fun status() = """{"pid":4321}"""

        override fun call(
            id: Long,
            method: String?,
            payload: String?,
            deadline: Long,
            callback: IYtxCallback?,
        ) {
            when (method) {
                "ping" -> {
                    scope.launch {
                        delay(pingDelayMs)
                        callback?.onResult(id, """{"ready":true}""")
                    }
                }

                "selftest" -> {
                    callback?.onResult(id, """{"ok":true,"missing":[]}""")
                }

                else -> {
                    callback?.onResult(id, "{}")
                }
            }
        }

        override fun cancel(id: Long) {}
    }

    /** Extracts and compiles the bundled tree, leaving the active engine COMPILED and served. */
    private suspend fun compileBundled(f: Fixture): PreparedEngine {
        val prepared = f.store.prepareBundled()
        compileTree(prepared)
        f.store.loadStatus()
        assertEquals(EnginePreparation.COMPILED, f.store.preparation.value)
        return prepared
    }

    private suspend fun stageCandidate(f: Fixture): PreparedEngine =
        f.store.stageVersion(CANDIDATE, fixtureZip(CANDIDATE), "0.8.0", 1)

    /** py -> pyc plus the compile marker, as the :ytx host's configure() would leave the tree. */
    private fun compileTree(prepared: PreparedEngine) {
        prepared.libDir.toFile().walkTopDown().filter { it.extension == "py" }.forEach {
            File(it.parentFile, "${it.name}c").writeText("fake bytecode, no Python executed")
            assertTrue(it.delete())
        }
        EngineCompileMarkers.write(prepared)
    }

    private fun arm(
        paths: FakeEngineStorePaths,
        version: String,
    ) {
        paths.zip = fixtureZip(version)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(paths.zip).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        paths.metadata = """{"version":"$version","tag":"$version","sha256":"$hash","ejsVersion":"0.8.0"}"""
    }

    private fun fixtureZip(version: String): ByteArray {
        val info = "ORIGIN = 'yt-dlp/yt-dlp'\nCHANNEL = 'stable'\n" + "__version__ = '$version'\n"
        val entries =
            linkedMapOf(
                "__main__.py" to "# inert fixture".toByteArray(),
                "yt_dlp/__init__.py" to "# inert fixture".toByteArray(),
                "yt_dlp/version.py" to info.toByteArray(),
                "yt_dlp_ejs/__init__.py" to "# inert fixture".toByteArray(),
                "yt_dlp_ejs/_version.py" to "__version__ = version = '0.8.0'\n".toByteArray(),
            )
        return ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    entries.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
    }

    private companion object {
        const val BUNDLED = "2026.08.19"
        const val CANDIDATE = "2026.09.01"
    }
}
