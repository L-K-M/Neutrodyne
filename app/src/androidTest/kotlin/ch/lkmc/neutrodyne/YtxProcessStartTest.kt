// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxService
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 01 Testing's `:ytx` start row (D73, verified in its verification log): arms [ProcessStartProbe]
 * through the marker file in `noBackupFilesDir` — the only channel that reaches the new `:ytx`
 * process before `attachBaseContext` — binds [YtxService], and reads the armed process's
 * `report-YTX.json` back over the shared UID.
 *
 * Proven in `:ytx`: no `AndroidAppGraph`, no `AppInitializer` launch, WorkManager never
 * initialised, ACRA never installed, no DataStore file and no database file opened (the M0a
 * tree has no `NeutrodyneDatabase` yet, so absent `databases/` and no `.db` fd stand in). The
 * ContentProvider half is static: the platform only instantiates in `:ytx` a provider whose
 * `processName` is `:ytx` or that is `multiprocess`, and [noProviderTargetsYtx] asserts the
 * merged manifest has none. `Process.killProcess` on the `:ytx` pid leaves this (main) process
 * running, the same-UID premise of 04's idle stop and hang kill.
 *
 * No `Python.start` is needed: `status()` answers without the interpreter, so the test also
 * runs on the API 26 x86_64 GMD where CPython cannot load (01 S7's seccomp finding) — unlike
 * `YtxReleaseSmokeTest`, which skips it.
 */
@RunWith(AndroidJUnit4::class)
class YtxProcessStartTest {
    private lateinit var context: Context
    private var connection: ServiceConnection? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // A stale marker or an already-running :ytx would miss the probe window.
        ProcessStartProbe.dir(context).deleteRecursively()
        killYtx()
    }

    @After
    fun tearDown() {
        connection?.let { runCatching { context.unbindService(it) } }
        ytxPid()?.let(Process::killProcess)
        ProcessStartProbe.dir(context).deleteRecursively()
    }

    @Test
    fun ytxStartsLeanAndDiesWithoutHarmingMain() {
        // Instrumentation runs in the app's process, which classifies itself as MAIN.
        assertThat(ProcessRole.current()).isEqualTo(ProcessRole.MAIN)

        ProcessStartProbe.markerFile(context).run {
            parentFile?.mkdirs()
            writeText("armed")
        }
        val engine = bind()

        val ytxPid = JSONObject(engine.status()).getInt("pid")
        assertThat(ytxPid).isNotEqualTo(Process.myPid())
        assertThat(cmdline(ytxPid)).isEqualTo("${context.packageName}:ytx")

        val report = readReport()
        assertThat(report.getInt("pid")).isEqualTo(ytxPid)
        assertThat(report.getString("role")).isEqualTo(ProcessRole.YTX.name)
        assertThat(report.getString("process")).isEqualTo("${context.packageName}:ytx")

        // The positive controls prove the probe was armed and the YTX branch ran; every
        // main-process milestone must be absent.
        val events = report.getJSONArray("events").toStrings()
        assertThat(events).contains(ProcessStartProbe.Event.ATTACH_BASE_CONTEXT.name)
        assertThat(events).contains(ProcessStartProbe.Event.YTX_GRAPH_CREATED.name)
        assertThat(events)
            .containsNoneOf(
                ProcessStartProbe.Event.APP_GRAPH_REQUESTED.name,
                ProcessStartProbe.Event.INITIALIZERS_LAUNCHED.name,
                ProcessStartProbe.Event.WORK_MANAGER_CONFIG_REQUESTED.name,
                ProcessStartProbe.Event.ACRA_INSTALLED.name,
            )
        assertThat(report.getBoolean("workManagerInitialized")).isFalse()
        assertThat(report.getBoolean("acraInstalled")).isFalse()

        // :ytx holds no DataStore or database file open; and the M0a tree has no database
        // yet, so databases/ is absent unless :ytx created one.
        for (path in report.getJSONArray("openDataFiles").toStrings()) {
            assertThat(path).doesNotContainMatch(DATA_FILE_RE)
        }
        assertThat(databasesDir().exists()).isFalse()

        // 04's idle stop and hang kill rely on a same-UID kill leaving the main process alone.
        Process.killProcess(ytxPid)
        assertTrue(":ytx is still running after killProcess", awaitYtxGone())
        assertThat(ProcessRole.current()).isEqualTo(ProcessRole.MAIN)
    }

    @Test
    fun noProviderTargetsYtx() {
        // The platform instantiates a provider in :ytx only when the provider's processName is
        // :ytx or it declares multiprocess — so a manifest with neither proves no
        // default-process ContentProvider can be created there.
        val spawning = providers().filter { it.processName.endsWith(YTX_SUFFIX) || it.multiprocess }
        assertThat(spawning.map { it.name }).isEmpty()
    }

    private fun bind(): IYtxEngine {
        val latch = CountDownLatch(1)
        val binder = AtomicReference<IBinder>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    binder.set(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val bound = context.bindService(Intent(context, YtxService::class.java), conn, Context.BIND_AUTO_CREATE)
        assertTrue("YtxService did not bind", bound)
        connection = conn
        assertTrue("bind timed out", latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return IYtxEngine.Stub.asInterface(binder.get())
    }

    /** The armed `:ytx` report; `onCreate` writes it before the bind completes, so the poll rarely waits. */
    private fun readReport(): JSONObject {
        val file = ProcessStartProbe.reportFile(context, ProcessRole.YTX)
        val deadline = System.currentTimeMillis() + REPORT_TIMEOUT_MS
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile) {
                try {
                    return JSONObject(file.readText())
                } catch (e: Exception) {
                    lastError = e // a torn write is retried until the deadline
                }
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no readable :ytx report at $file" + (lastError?.let { ": ${it.message}" } ?: ""))
    }

    private fun killYtx() {
        val pid = ytxPid() ?: return
        Process.killProcess(pid)
        assertTrue(":ytx (pid $pid) did not die", awaitYtxGone())
    }

    private fun awaitYtxGone(): Boolean {
        val deadline = System.currentTimeMillis() + GONE_TIMEOUT_MS
        while (ytxPid() != null && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return ytxPid() == null
    }

    /** The `:ytx` pid as the kernel names it — the same name `ProcessRole.current()` classified. */
    private fun ytxPid(): Int? {
        val want = "${context.packageName}:ytx"
        return File("/proc")
            .listFiles()
            ?.firstOrNull { it.name.toIntOrNull() != null && cmdline(it.name.toInt()) == want }
            ?.name
            ?.toInt()
    }

    private fun cmdline(pid: Int): String =
        runCatching { File("/proc/$pid/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    /** The app data dir's `databases/` — Room puts `NeutrodyneDatabase` there once M1 adds it. */
    private fun databasesDir(): File = File(context.applicationInfo.dataDir, DATABASES_DIR)

    @Suppress("DEPRECATION") // the int-flag getPackageInfo is the only call under API 33
    private fun providers(): Array<ProviderInfo> {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()),
                )
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            }
        return info.providers ?: emptyArray()
    }

    private fun JSONArray.toStrings(): List<String> = List(length()) { getString(it) }

    private companion object {
        const val YTX_SUFFIX = ":ytx"

        /** The app data dir's `databases/` subdirectory (Room's location for `NeutrodyneDatabase`). */
        const val DATABASES_DIR = "databases"

        /** Data-dir opens of `NeutrodyneDatabase` or a DataStore file (as substring match). */
        const val DATA_FILE_RE = "datastore|databases|\\.db"

        const val BIND_TIMEOUT_MS = 30_000L
        const val REPORT_TIMEOUT_MS = 30_000L
        const val GONE_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
