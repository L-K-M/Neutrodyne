// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Base64
import androidx.core.content.ContextCompat
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Compiled only with -Pneutrodyne.systemTests=true. No exported component or hook is
 * packaged in a normal APK. The shell-only receiver accepts loopback fixtures, not file paths.
 * E10 owns a non-FGS runner so am kill can actually kill it without a graceful stop callback.
 * UIDT/WorkManager lifecycle coverage remains with their device suites.
 */
@ContributesIntoSet(AppScope::class)
@Inject
internal class SystemTestDownloadInitializer(
    private val app: Application,
    private val components: Lazy<DownloadComponents>,
    private val db: Lazy<NeutrodyneDatabase>,
    private val roots: StorageRoots,
    private val registry: LaneRegistry,
    private val scheduler: Lazy<AndroidDownloadScheduler>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 135
    private val dao get() = db.value.downloadDao()
    private val commands = Mutex()
    private var drain: Job? = null
    private val marker get() = File(app.filesDir, MARKER)
    private val guard = RunnerToken("system-test:guard:${Process.myPid()}")

    override suspend fun run() {
        // Before the regular reconcile/scheduler initializers: retain the actual reset result
        // and park this fixture row until the test asks for resume. No stale token is live.
        if (marker.exists()) {
            registry.register(guard)
            components.value.reconciler.reconcile()
            val row = checkNotNull(dao.byEpisode(marker.readText().toLong()))
            File(app.filesDir, RESET).writeText(snapshot(row).toString())
            // A finite wake time avoids overflow in Android scheduling while the test holds the row.
            val parked = row.copy(nextAttemptAt = System.currentTimeMillis() + PARK_DELAY_MS)
            check(dao.updateUnclaimed(parked, row.state) == 1)
        }
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val pending = goAsync()
                    scope.launch(Dispatchers.IO) {
                        try {
                            val result = withTimeout(COMMAND_TIMEOUT_MS) { commands.withLock { command(intent) } }
                            pending.resultCode = RESULT_OK
                            pending.resultData = encode(result)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            pending.resultCode = RESULT_ERROR
                            pending.resultData = encode(JSONObject().put("error", error.toString()))
                        } finally {
                            pending.finish()
                        }
                    }
                }
            },
            IntentFilter(ACTION),
            "android.permission.DUMP",
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private suspend fun command(intent: Intent): JSONObject =
        when (intent.getStringExtra("command")) {
            "ready" -> {
                JSONObject().put("pid", Process.myPid())
            }

            "seed" -> {
                seed(checkNotNull(intent.getStringExtra("url")))
            }

            "resume" -> {
                val id = marker.readText().toLong()
                val row = checkNotNull(dao.byEpisode(id))
                check(row.state == DownloadState.QUEUED)
                check(dao.updateUnclaimed(row.copy(nextAttemptAt = null), row.state) == 1)
                startDrain()
                JSONObject().put("episodeId", id)
            }

            "status" -> {
                val rows = dao.allRows()
                val id = if (marker.exists()) marker.readText().toLong() else rows.singleOrNull()?.episodeId
                val row = id?.let { dao.byEpisode(it) }
                (row?.let { snapshot(it) } ?: JSONObject()).apply {
                    put("pid", Process.myPid())
                    put("runnerError", runnerError)
                    val reset = File(app.filesDir, RESET)
                    if (reset.exists()) put("reconciled", JSONObject(reset.readText()))
                }
            }

            else -> {
                error("Unknown system-test command")
            }
        }

    private suspend fun seed(url: String): JSONObject {
        val uri = java.net.URI(url)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0)
        check(!marker.exists())
        check(dao.allRows().isEmpty())
        registry.register(guard)
        val now = System.currentTimeMillis()
        val podcast =
            db.value
                .podcastDao()
                .allForExport()
                .single()
        val episode = checkNotNull(db.value.episodeDao().byIdentityKey(podcast.id, "g:w10-smoke"))
        check(episode.enclosureUrl == url)
        val id = episode.id
        dao.insert(
            DownloadEntity(
                episodeId = id,
                lane = DownloadLane.MANUAL,
                state = DownloadState.QUEUED,
                priority = MANUAL_PRIORITY,
                requestedAt = now,
                sourceKind = SourceKind.RSS_ENCLOSURE,
                sourceRef = url,
                rootId = roots.current(),
                allowMetered = true,
            ),
        )
        marker.writeText(id.toString())
        startDrain()
        return JSONObject().put("episodeId", id)
    }

    @Volatile private var runnerError: String? = null

    private fun startDrain() {
        check(drain?.isActive != true)
        drain =
            scope.launch(Dispatchers.IO) {
                try {
                    components.value.engine.drain(
                        DrainRequest(
                            lanes = setOf(DownloadLane.MANUAL),
                            runnerToken = RunnerToken("system-test:${UUID.randomUUID()}"),
                            softDeadlineElapsed = null,
                            waitHorizonMs = 0,
                            reporter =
                                object : DrainReporter {
                                    override fun active(episodeIds: Set<Long>) = Unit

                                    override fun bytes(
                                        downloaded: Long,
                                        total: Long?,
                                    ) = Unit
                                },
                            exit = scheduler.value.exit(),
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    runnerError = error.toString()
                }
            }
    }

    private suspend fun snapshot(row: DownloadEntity): JSONObject {
        val root = checkNotNull(roots.resolve(row.rootId)).absolutePath
        val part = File(root, row.tempPath ?: DownloadPaths.part(row.episodeId))
        val final = row.relativePath?.let { File(root, it) }
        return JSONObject().apply {
            put("episodeId", row.episodeId)
            put("state", row.state.name)
            put("waitReason", row.waitReason.name)
            put("token", row.runnerToken ?: JSONObject.NULL)
            put("etag", row.etag ?: JSONObject.NULL)
            put("partBytes", if (part.exists()) part.length() else 0)
            put("lastStopReason", row.lastStopReason ?: JSONObject.NULL)
            put("finalBytes", final?.length() ?: 0)
            if (row.state == DownloadState.COMPLETED && final != null) {
                val hash = MessageDigest.getInstance("SHA-256")
                final.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                put("sha256", hash.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
            }
        }
    }

    private fun encode(json: JSONObject): String = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)

    private companion object {
        const val PARK_DELAY_MS = 24L * 60 * 60 * 1000
        const val MANUAL_PRIORITY = 100
        const val ACTION = "ch.lkmc.neutrodyne.SYSTEM_TEST"
        const val MARKER = "system-test-episode"
        const val RESET = "system-test-reset.json"
        const val COMMAND_TIMEOUT_MS = 8_000L
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
    }
}
