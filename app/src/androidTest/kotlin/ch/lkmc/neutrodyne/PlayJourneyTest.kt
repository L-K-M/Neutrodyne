// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.play
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.util.UUID

/** E3: group Play, queue-first order, sheet controls, notification pause and persisted position. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class) // enableAccessibilityChecks
class PlayJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val device = UiDevice.getInstance(instrumentation)
    private val server = MockWebServer()
    private var controller: MediaController? = null
    private var cover: File? = null
    private var phase = "setup"
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val audio get() = app.getSystemService(AudioManager::class.java)

    // Off-main collection keeps the shared playback state hot, like the service harness.
    private val observation = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val groupUuid = UUID.randomUUID().toString()
    private val groupName = "Journey group ${groupUuid.take(8)}"
    private var groupId = 0L
    private var podcastId = 0L
    private var artworkKey = ""
    private var queued = emptyList<Long>()
    private var feedIds = emptyList<Long>()

    @After
    fun stopJourney() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                runCatching { graph.playbackController.pause() }
                controller?.release()
                controller = null
            }
            runCatching { graph.playbackController.dismiss() }
            runCatching { graph.queueRepository().clearUpNext() }
            runCatching { graph.databaseOpener.requireDatabase() }.getOrNull()?.let { db ->
                runCatching { if (groupId > 0) db.groupDao().deleteGroup(groupId) }
                runCatching {
                    if (podcastId > 0) {
                        db.podcastDao().deleteCascade(podcastId, System.currentTimeMillis())
                    }
                }
                runCatching { if (artworkKey.isNotEmpty()) db.artworkDao().delete(artworkKey) }
            }
            runCatching { app.stopService(Intent(app, NeutrodynePlaybackService::class.java)) }
            observation.cancel()
            server.close()
            cover?.delete()
            device.wakeUp()
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun groupFeedPlayQueueFirstSheetShadePauseAndReopen() =
        runCase {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                compose.enableAccessibilityChecks()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    app.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            observation.launch { graph.playbackStateSource.nowPlaying.collect {} }
            withTimeout(OPEN_TIMEOUT_MS) { graph.databaseOpener.awaitOpen() }
            val playLabel = getString(Res.string.play)
            phase("seed")
            seed()

            // The visible group tab, then that feed page's own Play action.
            phase("feed/tab")
            awaitNode(hasTestTag(tabTag))
            compose.onNodeWithTag(tabTag).performClick()
            val playAction = hasText(playLabel).and(hasAnyAncestor(hasTestTag(pageTag)))
            awaitNode(playAction)
            compose.onNode(playAction).performClick()

            // M4 AC6: the queue's head is promoted and its remaining order is kept.
            phase("start/queue-first")
            await { nowPlaying()?.episodeId == queued[0] }
            val queue = graph.playSessionStore().virtualQueue(queued.size + feedIds.size)
            assertThat(queue.current?.episodeId).isEqualTo(queued[0])
            assertThat(queue.upNext.map { it.episodeId }).isEqualTo(listOf(queued[1]))
            assertThat(queue.contextTail.map { it.episodeId }).isEqualTo(feedIds)
            val start = checkNotNull(nowPlaying())
            assertThat(start.context?.type).isEqualTo(ContextType.GROUP)
            assertThat(start.context?.id).isEqualTo(groupId)
            assertThat(start.context?.title).isEqualTo(groupName)
            awaitAudio()

            phase("player/mini")
            awaitNode(hasTestTag(TAG_MINI_PLAYER))
            compose.onNodeWithTag(TAG_MINI_PLAYER).assertIsDisplayed()

            phase("player/expand")
            compose.onNodeWithTag(TAG_MINI_PLAYER).performClick()
            awaitNode(hasTestTag(TAG_FULL_PLAYER))
            compose.onNodeWithTag(TAG_FULL_PLAYER).assertIsDisplayed()
            compose
                .onNode(
                    hasText(groupName).and(hasAnyAncestor(hasTestTag(TAG_PLAYER_SHEET))),
                ).assertIsDisplayed()
            // The speed row ("Speed 1.0×"); count, not onNode, since the attribution line can repeat it.
            awaitNode(
                hasText("Speed ", substring = true)
                    .and(hasAnyAncestor(hasTestTag(TAG_FULL_PLAYER))),
            )

            phase("player/transport")
            val pauseAction = hasContentDescription("Pause").and(hasAnyAncestor(hasTestTag(TAG_FULL_PLAYER)))
            val resumeAction = hasContentDescription("Play").and(hasAnyAncestor(hasTestTag(TAG_FULL_PLAYER)))
            awaitNode(pauseAction)
            compose.onNode(pauseAction).performClick()
            await { nowPlaying()?.isPlaying == false && nowPlaying()?.playWhenReady == false }
            awaitNode(resumeAction)
            compose.onNode(resumeAction).performClick()
            await { nowPlaying()?.isPlaying == true }

            phase("player/collapse")
            pressBack()
            awaitNode(hasTestTag(TAG_MINI_PLAYER))
            compose.onNodeWithTag(TAG_MINI_PLAYER).assertIsDisplayed()

            phase("notification/assert")
            val notification = awaitNotification(PLAYBACK_NOTIFICATION)
            assertThat(notification.channelId).isEqualTo(CHANNEL_PLAYBACK)
            assertThat(notification.flags and Notification.FLAG_FOREGROUND_SERVICE).isNotEqualTo(0)
            val artwork = withContext(Dispatchers.Main) { connect().mediaMetadata.artworkUri }
            assertThat(artwork?.scheme).isEqualTo("content")
            assertThat(artwork?.authority).isEqualTo("${app.packageName}.artwork")
            assertThat(artwork?.lastPathSegment).isEqualTo(artworkKey)
            assertThat(nowPlaying()?.stream).isEqualTo(StreamKind.STREAM)

            phase("background/shade-pause")
            pressHomeAndAwaitBackground(device, compose.activity, WAIT_MS)
            tapNotification("Pause")
            await { nowPlaying()?.isPlaying == false && nowPlaying()?.playWhenReady == false }

            phase("reopen")
            val launch = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launch)
            awaitNode(hasTestTag(TAG_MINI_PLAYER))
            compose.onNodeWithTag(TAG_MINI_PLAYER).assertIsDisplayed()
            await {
                val current = nowPlaying()
                current != null &&
                    current.episodeId == queued[0] &&
                    current.phase == PlayerPhase.READY &&
                    !current.isPlaying &&
                    !current.playWhenReady
            }
            await {
                graph.databaseOpener
                    .requireDatabase()
                    .positionDao()
                    .byEpisode(queued[0])
                    ?.positionMs
                    ?.let { it > 0 } == true
            }
            val saved =
                checkNotNull(
                    graph.databaseOpener
                        .requireDatabase()
                        .positionDao()
                        .byEpisode(queued[0]),
                )
            assertThat(saved.positionMs).isGreaterThan(0)
        }

    private val tabTag get() = "feed-tab:group:$groupUuid"
    private val pageTag get() = "feed-page:group:$groupUuid"

    private suspend fun seed() {
        val frame =
            instrumentation.context.assets
                .open("media/cbr_128k_2s.mp3")
                .use { it.readBytes() }
        val body = Buffer().apply { repeat(STREAM_REPEATS) { write(frame) } }.readByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset =
                        request.headers["Range"]
                            ?.substringAfter("bytes=")
                            ?.substringBefore('-')
                            ?.toIntOrNull() ?: 0
                    if (offset !in body.indices) return MockResponse(code = HTTP_RANGE_NOT_SATISFIABLE)
                    return MockResponse
                        .Builder()
                        .code(if (offset == 0) HTTP_OK else HTTP_PARTIAL)
                        .addHeader("Content-Type", "audio/mpeg")
                        .addHeader("Accept-Ranges", "bytes")
                        .apply {
                            if (offset > 0) {
                                addHeader("Content-Range", "bytes $offset-${body.lastIndex}/${body.size}")
                            }
                        }.body(Buffer().write(body, offset, body.size - offset))
                        .build()
                }
            }
        server.start()
        val db = graph.databaseOpener.requireDatabase()
        val coverUrl = server.url("/cover.png").toString()
        artworkKey = ArtworkKeys.forUrl(coverUrl)
        val image = File(app.filesDir, "artwork/$artworkKey.png").also { cover = it }
        image.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(COVER_SIDE, COVER_SIDE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        db.artworkDao().upsert(
            ArtworkEntity(
                artworkKey,
                url = coverUrl,
                localPath = image.name,
                width = COVER_SIDE,
                height = COVER_SIDE,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        podcastId =
            db.podcastDao().insertPodcast(
                podcastEntity(title = PODCAST_TITLE, artworkKey = artworkKey) {
                    copy(artworkUrl = coverUrl)
                },
            )
        val duration = STREAM_REPEATS * 2_000L
        queued =
            (1..2).map { number ->
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcastId, title = "Queued $number", sortDate = SORT_BASE - number) {
                        copy(enclosureUrl = server.url("/queued$number.mp3").toString(), durationMs = duration)
                    },
                )
            }
        feedIds =
            (1..2).map { number ->
                db.episodeDao().insertStub(
                    episodeEntity(
                        podcastId = podcastId,
                        title = "Feed $number",
                        sortDate = SORT_BASE - 10 - number,
                    ) {
                        copy(enclosureUrl = server.url("/feed$number.mp3").toString(), durationMs = duration)
                    },
                )
            }
        groupId = db.groupDao().insert(groupEntity(uuid = groupUuid, name = groupName, orderKey = "p8"))
        db.groupDao().insertMember(memberEntity(groupId = groupId, podcastId = podcastId, orderKey = "a"))
        // A shared real database: drop any inherited current/context/queue before seeding.
        graph.playbackController.dismiss()
        graph.queueRepository().clearUpNext()
        assertThat(graph.queueRepository().addLast(queued)).isEqualTo(AddResult.Added(queued.size))
    }

    private fun nowPlaying() = graph.playbackStateSource.nowPlaying.value

    private fun awaitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(WAIT_MS) {
            compose
                .onAllNodes(matcher)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private suspend fun connect(): MediaController {
        controller?.let { return it }
        return MediaController
            .Builder(
                app,
                SessionToken(app, ComponentName(app, NeutrodynePlaybackService::class.java)),
            ).buildAsync()
            .await()
            .also { controller = it }
    }

    private suspend fun awaitAudio() {
        await { nowPlaying()?.isPlaying == true }
        val before = snapshotPosition()
        await { snapshotPosition() > before + 200 }
        assertWithMessage("Android must report an active audio track")
            .that(
                audio.activePlaybackConfigurations.any {
                    it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH
                },
            ).isTrue()
    }

    private suspend fun snapshotPosition() = withContext(Dispatchers.Main) { connect().currentPosition }

    private suspend fun await(condition: suspend () -> Boolean) =
        withTimeout(WAIT_MS) {
            while (!condition()) delay(100)
        }

    private suspend fun awaitNotification(id: Int): Notification {
        var notification: Notification? = null
        await {
            notification =
                notifications.activeNotifications
                    .firstOrNull { it.id == id }
                    ?.notification
            notification != null
        }
        return checkNotNull(notification)
    }

    private fun tapNotification(label: String) {
        phase("notification/action-$label")
        check(device.openNotification()) { "Notification shade could not be opened" }
        // Action buttons live in the SystemUI window; scope every locator to it so an episode
        // row containing "Play" in app content can never be clicked instead.
        val deadline = SystemClock.elapsedRealtime() + 2 * WAIT_MS
        var staleAttempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val action =
                device.wait(
                    Until.findObject(By.descContains(label).pkg(SYSTEM_UI_PACKAGE)),
                    minOf(1_000L, remaining),
                )
                    ?: device.findObject(By.text(label).pkg(SYSTEM_UI_PACKAGE))
                    ?: continue
            try {
                action.click()
                device.pressBack()
                return
            } catch (stale: StaleObjectException) {
                if (++staleAttempts == 3) throw stale
            }
        }
        error("Notification action $label is missing")
    }

    private fun runCase(block: suspend () -> Unit) =
        runBlocking {
            try {
                phase("image/ready")
                val probe = shell("printf ND_STDERR_PROBE >&2; exit 7")
                check(probe.exitCode == 7 && probe.text.contains("ND_STDERR_PROBE"))
                val systemUi = shell("pm path $SYSTEM_UI_PACKAGE")
                check(systemUi.exitCode == 0 && systemUi.text.lineSequence().any { it.startsWith("package:") }) {
                    "Play journey requires a full image with SystemUI: ${systemUi.text}"
                }
                block()
            } catch (failure: Throwable) {
                runCatching { captureFailure() }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }

    private fun phase(value: String) {
        phase = value
        Log.i(
            "PlayJourneyEvidence",
            "phase=$value elapsed=${SystemClock.elapsedRealtime()} fingerprint=${Build.FINGERPRINT}",
        )
    }

    private fun stateSnapshot(): String {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val current = nowPlaying()
        return "phase=$phase uid=${Process.myUid()} pid=${Process.myPid()} importance=${process.importance} " +
            "playWhenReady=${current?.playWhenReady} isPlaying=${current?.isPlaying} " +
            "playerPhase=${current?.phase} issue=${current?.issue}"
    }

    private fun captureFailure() {
        fun record(
            name: String,
            value: () -> String,
        ) {
            logEvidence(name, runCatching(value).getOrElse { "Capture failed: $it" })
        }
        record("state", ::stateSnapshot)
        record("hierarchy") {
            val path = File(app.cacheDir, "play-window.xml")
            try {
                device.dumpWindowHierarchy(path)
                path.readText()
            } finally {
                path.delete()
            }
        }
        record("notification") { shell("dumpsys -t 5 notification").text }
        record("service") { shell("dumpsys -t 5 activity services ${app.packageName}/$SERVICE_CLASS").text }
        record("uid") { shell("cmd activity get-uid-state ${Process.myUid()}").text }
        record("processes") { shell("dumpsys -t 5 activity processes").text }
    }

    private fun logEvidence(
        name: String,
        value: String,
    ) {
        val chunks = value.chunked(1_000)
        chunks.forEachIndexed { index, chunk -> Log.i("PlayJourneyEvidence", "$name[$index/${chunks.size}] $chunk") }
        Log.i("PlayJourneyEvidence", "$name complete chars=${value.length}")
    }

    private data class ShellResult(
        val text: String,
        val exitCode: Int,
    )

    private fun shell(command: String): ShellResult {
        val marker = "ND_PLAY_SHELL_EXIT="
        val script = "($command) 2>&1; result=\$?; printf '\\n$marker%d\\n' \"\$result\""
        val file = File.createTempFile("play-shell-", ".sh", checkNotNull(app.externalCacheDir))
        try {
            file.writeText(script)
            val output = device.executeShellCommand("sh ${file.absolutePath}")
            val exit =
                output
                    .lineSequence()
                    .lastOrNull { it.startsWith(marker) }
                    ?.removePrefix(marker)
                    ?.trim()
                    ?.toIntOrNull()
                    ?: error("Shell command returned no exit evidence: $command\n$output")
            return ShellResult(output.substringBeforeLast("\n$marker"), exit)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val TAG_MINI_PLAYER = "mini_player"
        const val TAG_PLAYER_SHEET = "player_sheet"
        const val TAG_FULL_PLAYER = "full_player"
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val CHANNEL_PLAYBACK = "playback"
        const val PLAYBACK_NOTIFICATION = 4000
        const val SERVICE_CLASS = "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService"
        const val PODCAST_TITLE = "Play journey fixture"
        const val COVER_SIDE = 256
        const val STREAM_REPEATS = 60
        const val SORT_BASE = 1_700_000_000_000L
        const val WAIT_MS = 30_000L
        const val OPEN_TIMEOUT_MS = 60_000L

        /** Covers the sequential worst case of every bounded wait plus seed writes. */
        const val TEST_TIMEOUT_MS = 300_000L
    }
}
