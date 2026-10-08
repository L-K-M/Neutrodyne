// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Context
import android.os.Process
import androidx.annotation.VisibleForTesting
import androidx.work.WorkManager
import org.acra.ACRA
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The inert process-start probe of 01's Testing table (`:ytx` start row, D73). It records the
 * start-up milestones of whichever app process it runs in, but only after
 * [NeutrodyneApplication.attachBaseContext] finds the arm file an instrumented test wrote into
 * `noBackupFilesDir` first — a file is the one channel that reaches a new process before
 * `attachBaseContext` (a bind `Intent` arrives at `onBind`, after every recordable event). The
 * file never exists in production, so the probe is a no-op there and in release builds — the
 * same inert seam as 04's `YtxTestHooks`, kept in `main` for that reason (N7).
 *
 * `attach` arms the probe, `record` appends a milestone while armed and `writeReport` drops a
 * `report-<role>.json` beside the marker; `YtxProcessStartTest` reads it back over the shared
 * UID (same `noBackupFilesDir` for every process of the app).
 */
internal object ProcessStartProbe {
    /** Start-up milestones the test proves present in `:ytx` (the branch itself) or absent (main-process work). */
    enum class Event {
        ATTACH_BASE_CONTEXT,
        APP_GRAPH_REQUESTED,
        INITIALIZERS_LAUNCHED,
        WORK_MANAGER_CONFIG_REQUESTED,
        ACRA_INSTALLED,
        YTX_GRAPH_CREATED,
    }

    private const val PROBE_DIR = "process-start-probe"
    private const val MARKER_NAME = "armed"
    private const val REPORT_PREFIX = "report-"
    private const val REPORT_SUFFIX = ".json"

    private const val PROC_FD = "/proc/self/fd"
    private const val PROC_CMDLINE = "/proc/self/cmdline"
    private const val NUL = '\u0000'

    private val events = mutableListOf<String>()

    @Volatile
    private var armed = false

    @Volatile
    private var role = ProcessRole.MAIN

    /** Arms the probe when the marker file exists; called once from `attachBaseContext`. */
    fun attach(
        context: Context,
        role: ProcessRole,
    ) {
        this.role = role
        if (!markerFile(context).exists()) return
        synchronized(events) {
            armed = true
            events.clear()
        }
        record(Event.ATTACH_BASE_CONTEXT)
    }

    /** Records [event]; a no-op while unarmed (every production process). */
    fun record(event: Event) {
        if (!armed) return
        synchronized(events) { events += event.name }
    }

    /**
     * Writes the armed process's report; a no-op while unarmed. Called at the end of
     * `Application.onCreate`, by which time every recorded event has happened: providers install
     * before `onCreate`, ACRA lands in `attachBaseContext`, and both graphs and the initializer
     * launch sit inside `onCreate`.
     */
    fun writeReport(context: Context) {
        if (!armed) return
        // Collect the fd scan before opening the report for writing, so the report file's own
        // descriptor never appears in openDataFiles.
        val snapshot = synchronized(events) { events.toList() }
        val report =
            JSONObject()
                .put("pid", Process.myPid())
                .put("process", processName())
                .put("role", role.name)
                .put("events", JSONArray(snapshot))
                .put("workManagerInitialized", WorkManager.isInitialized())
                .put("acraInstalled", ACRA.isInitialised)
                .put("openDataFiles", JSONArray(openDataFiles(context)))
        reportFile(context, role).writeText(report.toString())
    }

    /** The marker the instrumentation test writes before binding `YtxService`. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun markerFile(context: Context): File = File(dir(context), MARKER_NAME)

    /** `noBackupFilesDir/process-start-probe/report-<role>.json`, written by [writeReport]. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun reportFile(
        context: Context,
        role: ProcessRole,
    ): File = File(dir(context), REPORT_PREFIX + role.name + REPORT_SUFFIX)

    /** The probe directory under `noBackupFilesDir`; the test deletes it to clear stale state. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun dir(context: Context): File = File(context.noBackupFilesDir, PROBE_DIR)

    private fun processName(): String = File(PROC_CMDLINE).readText().substringBefore(NUL)

    /** Data-dir paths this process holds open right now — an opened database or DataStore file shows. */
    private fun openDataFiles(context: Context): List<String> {
        val dataDir = context.applicationInfo.dataDir
        return File(PROC_FD)
            .listFiles()
            ?.mapNotNull { fd -> runCatching { fd.canonicalPath }.getOrNull() }
            ?.filter { it.startsWith(dataDir) }
            ?.sorted()
            .orEmpty()
    }
}
