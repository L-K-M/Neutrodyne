// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.desktop.system.LinuxMprisSession
import ch.lkmc.neutrodyne.desktop.system.LinuxSuspendMonitor
import ch.lkmc.neutrodyne.desktop.system.NativeSystemMediaSession
import ch.lkmc.neutrodyne.desktop.system.RemoteCommand
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaveResult
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.engine.Md0AudioProbe
import ch.lkmc.neutrodyne.playback.native.NativeSessionEventKind
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.name

/** Test-only hardware instrument. It is absent from every shipping classpath. */
object Md0Probe {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            require(args.size == 6) { "file backend speed skip-silence seconds report-path required" }
            val file = Path.of(args[0]).toAbsolutePath()
            require(Files.isRegularFile(file)) { "a local audio file is required: $file" }
            val backend = args[1].takeUnless { it == "default" }
            val speed = args[2].toFloat().also { require(it.isFinite() && it in 0.5f..3f) }
            val skip = args[3].toBooleanStrict()
            val seconds = args[4].toLong().also { require(it in 1..86_400) }
            val report = Path.of(args[5]).toAbsolutePath()
            Files.createDirectories(report.parent)
            val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
            val os = System.getProperty("os.name")
            val target =
                when {
                    os.startsWith("Windows") -> "windows-x64"
                    os == "Mac OS X" -> "macos-arm64"
                    System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
                    else -> "linux-x64"
                }
            val ffmpeg = root.resolve("playback/native/build/ffmpeg/$target")
            val ndmedia = root.resolve("playback/native/build/ndmedia/$target")
            PrintWriter(Files.newBufferedWriter(report, StandardOpenOption.CREATE_NEW), true).use { output ->
                fun log(
                    kind: String,
                    detail: String,
                ) {
                    val line =
                        "{\"monoNs\":${System.nanoTime()}," +
                            "\"kind\":\"$kind\",\"detail\":${JsonPrimitive(detail)}}"
                    output.println(line)
                    println(line)
                }
                log(
                    "start",
                    "target=$target backend=${backend ?: "default"} speed=$speed skip=$skip " +
                        "file=${file.name}; position store is an in-memory fake",
                )
                log("build", Files.readString(ffmpeg.resolve("ffmpeg-build.txt")))
                Md0AudioProbe(ffmpeg, ndmedia, backend, 44_100).use { engine ->
                    val clock = TestClock(nowMs = System.currentTimeMillis())
                    val store = FakePositionStore()
                    val saver = PositionSaver(store, clock, PlayedRule())
                    saver.load(1, clock.now(), PositionSource.DOWNLOAD)
                    saver.startPositionApplied()
                    saver.onPlaying()
                    var paused = false
                    val safety =
                        Md0SafetyCoordinator(
                            pause = {
                                engine.pause()
                                paused = true
                            },
                            position = engine::positionMs,
                            saver = saver,
                        )
                    val session =
                        if (os == "Linux") {
                            LinuxMprisSession.open()
                        } else {
                            NativeSystemMediaSession.open(ndmedia)
                        }
                    session.use {
                        val commands =
                            launch {
                                session.commands.collect { command ->
                                    log("command", command.toString())
                                    when (command) {
                                        RemoteCommand.Play -> {
                                            engine.resume()
                                            paused = false
                                        }

                                        RemoteCommand.Pause -> {
                                            engine.pause()
                                            paused = true
                                            saver.onPause(engine.positionMs(), null)
                                        }

                                        RemoteCommand.Toggle -> {
                                            if (paused) engine.resume() else engine.pause()
                                            paused = !paused
                                            if (paused) saver.onPause(engine.positionMs(), null)
                                        }

                                        else -> {
                                            log(
                                                "transport-only",
                                                "received $command; single-item spike has no queue controller",
                                            )
                                        }
                                    }
                                }
                            }
                        val power = if (os == "Linux") LinuxSuspendMonitor.open() else null
                        val safetyJob =
                            launch {
                                if (session is NativeSystemMediaSession) {
                                    session.systemEvents.collect { event ->
                                        log("system-event", event.toString())
                                        safety.accept(event.kind)
                                        log("fake-store-position", "position=${store.positions.value[1]?.positionMs}")
                                    }
                                } else {
                                    requireNotNull(power).events.collect { event ->
                                        log("system-event", event.toString())
                                        safety.accept(
                                            if (event == PowerEvent.Suspending) {
                                                NativeSessionEventKind.SUSPEND
                                            } else {
                                                NativeSessionEventKind.RESUME
                                            },
                                        )
                                        log("fake-store-position", "position=${store.positions.value[1]?.positionMs}")
                                    }
                                }
                            }
                        try {
                            engine.play(file, speed, skip)
                            val start = System.nanoTime()
                            var completed = false
                            while (!engine.isEnded) {
                                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                                clock.elapsedMs = elapsedMs
                                clock.nowMs = System.currentTimeMillis()
                                check(engine.outputDescription != null || elapsedMs < 10_000) {
                                    "output did not open"
                                }
                                engine.awaitEnded(0)
                                val position = engine.positionMs()
                                val snapshot =
                                    testNowPlaying().let { item ->
                                        item.copy(
                                            title = file.name,
                                            isPlaying = !paused,
                                            position =
                                                item.position.copy(
                                                    positionMs = position,
                                                    durationMs = engine.durationMs,
                                                    speed = speed,
                                                ),
                                        )
                                    }
                                session.publish(snapshot, 10_000, 30_000, listOf(0.5f, 1f, 2f, 3f))
                                saver.onTick(position, null, !paused)
                                val cpu =
                                    ProcessHandle
                                        .current()
                                        .info()
                                        .totalCpuDuration()
                                        .orElse(java.time.Duration.ZERO)
                                        .toNanos()
                                log(
                                    "sample",
                                    "elapsedMs=$elapsedMs positionMs=$position " +
                                        "underrunFrames=${engine.underrunFrames} " +
                                        "cpuNs=$cpu paused=$paused device=${engine.outputDescription}",
                                )
                                if (session is NativeSystemMediaSession) {
                                    check(session.failure.value == null) { session.failure.value.orEmpty() }
                                }
                                if (elapsedMs >= seconds * 1_000) {
                                    completed = true
                                    break
                                }
                                delay(250)
                            }
                            engine.pause()
                            saver.onPause(engine.positionMs(), null)
                            log(
                                "result",
                                "completed=$completed requestedSeconds=$seconds " +
                                    "underrunFrames=${engine.underrunFrames}; " +
                                    "no automatic hardware or clock-accuracy verdict",
                            )
                            check(completed) { "the file ended before the requested soak duration" }
                        } finally {
                            commands.cancelAndJoin()
                            safetyJob.cancelAndJoin()
                            power?.close()
                        }
                    }
                }
            }
            println("MD0 evidence: $report")
        }
}

/** Harness policy, deliberately outside the production controller until MD1. */
internal class Md0SafetyCoordinator(
    private val pause: () -> Unit,
    private val position: () -> Long,
    private val saver: PositionSaver,
) {
    suspend fun accept(event: NativeSessionEventKind) {
        when (event) {
            NativeSessionEventKind.SUSPEND, NativeSessionEventKind.REMOVED -> {
                pause()
                val result = saver.onPause(position(), null)
                if (result is PositionSaveResult.Failed) {
                    throw IllegalStateException("event position save failed", result.cause)
                }
            }

            NativeSessionEventKind.RESUME, NativeSessionEventKind.DEFAULT_CHANGED -> {
                Unit
            }

            else -> {
                error("not a power or route event: $event")
            }
        }
    }
}
