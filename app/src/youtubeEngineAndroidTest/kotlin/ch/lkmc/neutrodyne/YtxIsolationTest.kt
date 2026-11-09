// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.ActivityManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.crash.installAcra
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.YtDlpErrorMapper
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.ytdlp.AndroidEngineStorePaths
import ch.lkmc.neutrodyne.youtube.ytdlp.BinderYtxTransport
import ch.lkmc.neutrodyne.youtube.ytdlp.YtxTestHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.acra.ACRA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real host failures while a main-process Media3 playlist continues. No live YouTube traffic. */
@RunWith(AndroidJUnit4::class)
class YtxIsolationTest {
    @Test fun killedHungAndCrashingHostsLeaveMainPlaybackRunning() =
        runBlocking {
            assumeFalse(
                "CPython cannot load on x86_64 Android 8.x",
                Build.SUPPORTED_ABIS.first() == "x86_64" && Build.VERSION.SDK_INT < 28,
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as NeutrodyneApplication
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val settings = app.graph.settingsRepository
            val previous = settings.get(YouTubeSettingKeys.ENGINE_ENABLED)
            val root = app.noBackupFilesDir.resolve("ytx-test/${UUID.randomUUID()}").apply { mkdirs() }
            val audio = root.resolve("audio.mp3")
            instrumentation.context.assets.open("media/cbr_128k_2s.mp3").use { input ->
                audio.outputStream().use { output -> input.copyTo(output) }
            }
            val transitions = AtomicInteger()
            val player =
                withContext(Dispatchers.Main) {
                    ExoPlayer.Builder(app).build().apply {
                        addListener(
                            object : Player.Listener {
                                override fun onMediaItemTransition(
                                    mediaItem: MediaItem?,
                                    reason: Int,
                                ) {
                                    transitions.incrementAndGet()
                                }
                            },
                        )
                        volume = 0f
                        val item = MediaItem.fromUri(Uri.fromFile(audio))
                        setMediaItems(listOf(item, item))
                        repeatMode = Player.REPEAT_MODE_ALL
                        prepare()
                        play()
                    }
                }
            val clock =
                object : Clock {
                    override fun now() = System.currentTimeMillis()

                    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
                }
            if (!ACRA.isInitialised) {
                withContext(Dispatchers.Main) {
                    installAcra(app, "ytx-isolation-test@invalid")
                }
            }
            assertTrue(ACRA.isInitialised)
            ACRA.errorReporter.setEnabled(true)
            val reports = app.getDir("ACRA-unapproved", 0)
            val before: Set<String> = reports.list()?.toSet() ?: emptySet()
            try {
                settings.set(YouTubeSettingKeys.ENGINE_ENABLED, false)
                withTimeout(10_000) {
                    app.graph.youtubeEngine.status
                        .first { it.availability == EngineAvailability.DISABLED }
                }
                for (vector in IsolationCase.entries) {
                    val fault =
                        if (vector == IsolationCase.PYTHON_CRASH) {
                            YtxTestHooks.Fault.PYTHON_CRASH
                        } else {
                            YtxTestHooks.Fault.HANG
                        }
                    val transitionsBefore = transitions.get()
                    val paths = AndroidEngineStorePaths(app)
                    val store = BundledEngineStore(paths, app.graph.buildInfo.versionCode, clock)
                    store.loadStatus()
                    store.prepareBundled()
                    val directory = root.resolve(UUID.randomUUID().toString()).apply { mkdirs() }
                    directory.resolve("http.json").writeText("{\"bodies\":[],\"exchanges\":[]}")
                    YtxTestHooks.arm(app, directory, fault)
                    val ctx = YtxContext("en", "US", "Neutrodyne isolation test")
                    val transport =
                        BinderYtxTransport(
                            app,
                            store,
                            CompileMarker(
                                app.graph.buildInfo.versionCode,
                                "2026.08.19",
                                "3.14",
                            ),
                            clock,
                            scope,
                            YtxControlRequest(ctx),
                            FakeEngineSettings(),
                        )
                    try {
                        transport.call(
                            YtxMethod.PING,
                            YtxControlRequest(ctx).toJson(),
                            YtxDeadline(clock.elapsedRealtime() + YtxLimits.COMPILE_MS),
                        )
                        val pending =
                            scope.async {
                                try {
                                    transport.call(
                                        YtxMethod.RESOLVE,
                                        """{"ctx":{"hl":"en","gl":"US","ua":"test"},"videoId":"aaaaaaaaaaA"}""",
                                        YtxDeadline(clock.elapsedRealtime() + 1000),
                                    )
                                    error("Injected host failure returned success")
                                } catch (failure: YtxCallException) {
                                    YtDlpErrorMapper(clock).map(failure.code, "aaaaaaaaaaA")
                                }
                            }
                        if (vector == IsolationCase.KILLED) {
                            delay(100)
                            Process.killProcess(requireNotNull(transport.status.value.pid).toInt())
                        }
                        val result = withTimeout(10_000) { pending.await() }
                        assertTrue(result is ResolveResult.Transient)
                        assertTrue(
                            (result as ResolveResult.Transient).kind in
                                setOf(
                                    TransientKind.TIMEOUT,
                                    TransientKind.ENGINE_UNAVAILABLE,
                                ),
                        )
                        withTimeout(10_000) {
                            while (transitions.get() <= transitionsBefore ||
                                !withContext(Dispatchers.Main) { player.isPlaying }
                            ) {
                                delay(50)
                            }
                        }
                        assertEquals(ProcessRole.MAIN, ProcessRole.current())
                        assertEquals(before, reports.list()?.toSet() ?: emptySet<String>())
                        val manager = app.getSystemService(ActivityManager::class.java)
                        val processes = manager.runningAppProcesses.orEmpty()
                        assertTrue(processes.none { it.processName == "${app.packageName}:acra" })
                    } finally {
                        transport.shutdown()
                    }
                }
            } finally {
                ACRA.errorReporter.setEnabled(false)
                withContext(Dispatchers.Main) { player.release() }
                YtxTestHooks.clear()
                settings.set(YouTubeSettingKeys.ENGINE_ENABLED, previous)
                scope.cancel()
                root.deleteRecursively()
            }
        }

    private enum class IsolationCase { KILLED, HUNG, PYTHON_CRASH }
}
