// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.content.Context
import android.content.ContextWrapper
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnginePreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()
    private val store = FakeEngineStore()
    private val transport = FakeYtxTransport()
    private val settings = FakeEngineSettings()
    private val preflight = FakeEnginePlatformPreflight()

    @Test fun pathsUseNoBackupAndCacheAndPackagedDataOnly() {
        val paths = AndroidEngineStorePaths(context)
        assertEquals(
            context.noBackupFilesDir.canonicalFile
                .resolve("ytdlp")
                .path,
            paths.root.toString(),
        )
        assertEquals(
            context.cacheDir.canonicalFile
                .resolve("yt-dlp")
                .path,
            paths.ytDlpCacheDir.toString(),
        )
        assertEquals("3.14", paths.pythonMinor)
        assertTrue(paths.bundledJson().contains("2026.08.19"))
        assertEquals("#!/usr/bin/env python3", paths.openBundledZip().buffer().use { it.readUtf8Line() })
        for (name in listOf("ytdlp/SHA2-256SUMS", "ytdlp/SHA2-256SUMS.sig", "ytdlp/yt-dlp-release-key.asc")) {
            assertFailsWith<java.io.FileNotFoundException> { context.assets.open(name).close() }
        }
    }

    @Test fun trustedParentsAreCanonicalButEngineChildLinksAreNotFollowed() {
        val real = temporary.newFolder().canonicalFile
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val wrapped =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File = alias

                override fun getCacheDir(): File = alias
            }
        val paths = AndroidEngineStorePaths(wrapped)
        assertEquals(real.resolve("ytdlp").path, paths.root.toString())
        assertEquals(real.resolve("yt-dlp").path, paths.ytDlpCacheDir.toString())
        Files.createSymbolicLink(real.resolve("ytdlp").toPath(), temporary.newFolder().toPath())
        assertEquals(paths.root, AndroidEngineStorePaths(wrapped).root)
    }

    @Test fun startupLoadsStatusOnlyAndSchedulesUniqueKeepWithDelayAndNoNetwork() =
        runTest {
            val status = EngineStatusInitializer(store)
            assertEquals(150, status.order)
            status.run()
            assertEquals(1, store.loads)
            assertEquals(0, store.preparations)
            val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
            val manager = WorkManagerImpl.getInstance(context)
            val initializer = EnginePrepareInitializer(store, manager)
            assertEquals(210, initializer.order)
            initializer.run()
            val first = manager.getWorkInfosForUniqueWork(EnginePrepareInitializer.WORK_NAME).get().single()
            initializer.run()
            assertEquals(
                first.id,
                manager
                    .getWorkInfosForUniqueWork(EnginePrepareInitializer.WORK_NAME)
                    .get()
                    .single()
                    .id,
            )
            val spec = manager.workDatabase.workSpecDao().getWorkSpec(first.id.toString())!!
            assertEquals(30_000L, spec.initialDelay)
            assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
            assertEquals(0, store.preparations)
            assertTrue(transport.calls.isEmpty())
            manager.cancelAllWork().result.get()
            store.preparation.value = EnginePreparation.COMPILED
            initializer.run()
            assertEquals(1, manager.getWorkInfosForUniqueWork(EnginePrepareInitializer.WORK_NAME).get().size)
        }

    @Test fun workerWaitsForHostAndRequiresCompileMarkerBeforeSuccess() =
        runTest {
            store.prepare = { PreparedEngine("/prepared/lib".toPath(), CompileMarker(1, "2026.08.19", "3.14")) }
            transport.answer = { "{}" }
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            assertEquals(1, store.preparations)
            assertEquals(YtxMethod.PING, transport.calls.single().method)
            transport.answer = {
                store.preparation.value = EnginePreparation.COMPILED
                "{}"
            }
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(2, store.loads)
        }

    @Test fun workerRetriesIoAndHostFailuresAndPropagatesCancellation() =
        runTest {
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            assertTrue(transport.calls.isEmpty())
            store.prepare = { PreparedEngine("/prepared/lib".toPath(), CompileMarker(1, "2026.08.19", "3.14")) }
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            transport.answer = { throw CancellationException("stop") }
            assertFailsWith<CancellationException> { worker().doWork() }
        }

    @Test fun queuedWorkRechecksEligibilityBeforePreparingOrBinding() =
        runTest {
            settings.state.value = settings.state.value.copy(enabled = false)
            val output = androidx.work.workDataOf("preparation" to "skipped-ineligible")
            val skipped = ListenableWorker.Result.success(output)
            assertEquals(skipped, worker().doWork())
            settings.state.value = settings.state.value.copy(enabled = true)
            preflight.result = ch.lkmc.neutrodyne.youtube.api.PlatformLimitation.X86_64_API_26_27
            assertEquals(skipped, worker().doWork())
            assertEquals(0, store.preparations)
            assertTrue(transport.calls.isEmpty())
        }

    @Test fun preparationPublishesRealBundleAndControlPayload() =
        runTest {
            val output = File("build/preparedShim").canonicalFile.apply { mkdirs() }
            val wrapped =
                object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this

                    override fun getNoBackupFilesDir(): File = output.resolve("noBackup").apply { mkdirs() }

                    override fun getCacheDir(): File = output.resolve("cache").apply { mkdirs() }
                }
            val realStore = BundledEngineStore(AndroidEngineStorePaths(wrapped), 1)
            transport.answer = { "{\"ready\":true}" }
            // A working ping alone cannot claim W4 has compiled the engine.
            assertEquals(ListenableWorker.Result.retry(), worker(realStore).doWork())
            assertEquals(1, transport.calls.size)
            assertEquals(EnginePreparation.PREPARING, realStore.preparation.value)
            // Gradle's preparedShimTest consumes these declared test outputs, without Kotlin exec.
            output.resolve("call.json").writeText(transport.calls.single().json)
            output.resolve("lib-path.txt").writeText(
                realStore
                    .hostLibDir()!!
                    .toFile()
                    .relativeTo(output)
                    .path,
            )
        }

    private fun worker(engineStore: EngineStore = store): EnginePrepareWorker =
        TestListenableWorkerBuilder<EnginePrepareWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        EnginePrepareWorker(
                            appContext,
                            workerParameters,
                            engineStore,
                            transport,
                            settings,
                            preflight,
                            YtxControlRequest(YtxContext("fr", "CH", "preparation-probe/1", "V6")),
                        )
                },
            ).build()
}
