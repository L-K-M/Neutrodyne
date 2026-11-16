// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.nio.file.AccessDeniedException
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopEnginePreparationObserverTest {
    private val store = FakeEngineStore()
    private val transport = FakeYtxTransport()
    private val settings = FakeEngineSettings()

    @Test fun failedPreparationLogsItsStepWithoutAHomePath() =
        runTest {
            val records = mutableListOf<Pair<LogLevel, String>>()
            Log.install(
                LogSink { level, tag, message ->
                    if (tag == "DesktopEnginePreparation") records += level to message
                },
            )
            try {
                store.prepare = { throw AccessDeniedException("$HOME/Library/Application Support/ytdlp") }
                observer(backgroundScope).run()
                runCurrent()
                store.prepare = { PreparedEngine("/prepared/lib".toPath(), CompileMarker(1, "2026.08.19", "3.14")) }
                transport.answer = { throw YtxCallException("ENGINE_UNAVAILABLE") }
                reenable()

                // The step tells an extraction failure from a compile one. The desktop log sinks
                // do not write the home directory as `~/…` yet, so no trace carries the path.
                assertEquals(
                    listOf(
                        LogLevel.WARN to "Engine preparation failed at EXTRACT: AccessDeniedException",
                        LogLevel.WARN to "Engine preparation failed at COMPILE: ENGINE_UNAVAILABLE",
                    ),
                    records,
                )
            } finally {
                Log.install()
            }
        }

    private fun TestScope.reenable() {
        settings.state.value = settings.state.value.copy(enabled = false)
        runCurrent()
        settings.state.value = settings.state.value.copy(enabled = true)
        runCurrent()
    }

    private fun observer(scope: CoroutineScope) =
        DesktopEnginePreparationObserver(
            store = store,
            transport = transport,
            settings = settings,
            preflight = FakeEnginePlatformPreflight(),
            pingRequest = { YtxControlRequest(YtxContext("en", "US", "preparation-probe/1")) },
            clock = TestClock(),
            scope = scope,
        )

    private companion object {
        const val HOME = "/Users/someone"
    }
}
