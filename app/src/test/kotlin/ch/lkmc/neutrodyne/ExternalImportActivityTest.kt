// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.FrameLayout
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.HostileInput
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.model.ImportSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = ImportHostApplication::class, sdk = [34])
class ExternalImportActivityTest {
    private val app get() = RuntimeEnvironment.getApplication() as ImportHostApplication
    private val uri = Uri.parse("content://sender/opaque-id")

    @Test
    fun coldStartResolvesTheDatabaseBackedRepositoryOffMain() {
        var resolvedOnMain: Boolean? = null
        app.importsProvider =
            lazy {
                resolvedOnMain = Looper.myLooper() == Looper.getMainLooper()
                app.imports
            }
        val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, view(uri)).create()
        pumpUntil { controller.get().isFinishing }
        assertThat(resolvedOnMain).isFalse()
        assertCleanHandoff(shadowOf(controller.get()).nextStartedActivity, 42L)
        controller.destroy()
    }

    @Test
    @Config(sdk = [26])
    fun extensionAliasIsDisabledBeforePathSuffixExists() {
        assertThat(app.resources.getBoolean(R.bool.neutrodyne_api31_or_newer)).isFalse()
    }

    @Test
    fun extensionAliasIsEnabledOnModernAndroid() {
        assertThat(app.resources.getBoolean(R.bool.neutrodyne_api31_or_newer)).isTrue()
    }

    @Test
    fun viewCopiesContentWithoutMetadataBeforeFinishingAndNeverRetainsTheGrant() {
        assertCopiesContent(view(uri))
    }

    @Test
    fun sendCopiesContentWithoutMetadataBeforeHandingOff() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(GRANT_FLAGS)
        assertCopiesContent(intent)
    }

    private fun assertCopiesContent(intent: Intent) {
        val graph = app.productionGraph
        runBlocking(Dispatchers.IO) { graph.databaseOpener.awaitOpen() }
        app.imports = graph.importRepository
        var closed = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = OPML.toByteArray()
        val stream =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Copy was not released" }
                    return super.read(bytes, offset, length)
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
        shadowOf(app.contentResolver).registerInputStream(uri, stream)
        val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, intent).create()
        try {
            pumpUntil { entered.count == 0L }
            assertThat(controller.get().isFinishing).isFalse()
            release.countDown()
            pumpUntil { controller.get().isFinishing }
            assertThat(closed).isTrue()
            val handoff = shadowOf(controller.get()).nextStartedActivity
            val id = handoff.data!!.lastPathSegment!!.toLong()
            // The source can disappear immediately; the session and its payload are app-owned.
            shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf()))
            val payload = File(app.cacheDir, "import/$id.bin")
            assertThat(payload.readBytes()).isEqualTo(bytes)
            val session = runBlocking { graph.importRepository.observeSession(id).first() }
            assertThat(session).isNotNull()
            assertThat(app.contentResolver.persistedUriPermissions).isEmpty()
            assertCleanHandoff(handoff, id)
        } finally {
            release.countDown()
            controller.destroy()
            graph.appScope.cancel()
            graph.databaseOpener.requireDatabase().close()
        }
    }

    @Test
    fun sendExtraStreamAndClipFallbackUseTheSameCreatePath() {
        val received = mutableListOf<ImportSource>()
        app.imports =
            stubImports {
                received += it
                Outcome.Success(42L)
            }
        val other = Uri.parse("content://sender/clip")
        for (intent in listOf(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri),
            Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("list", other) },
        )) {
            val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, intent).create()
            pumpUntil { controller.get().isFinishing }
            assertCleanHandoff(shadowOf(controller.get()).nextStartedActivity, 42L)
            controller.destroy()
        }
        assertThat(
            received,
        ).containsExactly(
            ImportSource(uri.toString(), displayName = null),
            ImportSource(other.toString(), displayName = null),
        ).inOrder()
    }

    @Test
    fun fileUriUsesTheProductionAcquisitionSeam() {
        val graph = app.productionGraph
        runBlocking(Dispatchers.IO) { graph.databaseOpener.awaitOpen() }
        app.imports = graph.importRepository
        val file = File(app.cacheDir, "old-sender.opml").apply { writeText(OPML) }
        val controller =
            Robolectric
                .buildActivity(
                    ExternalImportActivity::class.java,
                    view(Uri.fromFile(file)),
                ).create()
        try {
            pumpUntil { controller.get().isFinishing }
            file.delete()
            val handoff = shadowOf(controller.get()).nextStartedActivity
            val id = handoff.data!!.lastPathSegment!!.toLong()
            assertThat(File(app.cacheDir, "import/$id.bin").readText()).isEqualTo(OPML)
            assertCleanHandoff(handoff, id)
        } finally {
            controller.destroy()
            graph.appScope.cancel()
            graph.databaseOpener.requireDatabase().close()
        }
    }

    @Test
    fun missingStreamsTextAndMultipleSendFinishWithNothingToImport() {
        app.imports = stubImports { error("Invalid input must never create a session") }
        for (intent in listOf(
            Intent(Intent.ACTION_VIEW),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.org/list.opml"),
            view(Uri.parse("https://example.org/list.opml")),
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri)),
        )) {
            val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, intent).create()
            assertThat(controller.get().isFinishing).isTrue()
            assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Nothing to import")
            assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
            controller.destroy()
        }
    }

    @Test
    fun preSessionFailuresReachMainActivityWithoutUriOrGrant() {
        for ((error, code) in listOf(
            ImportError.Unreadable to ImportAcquisitionError.UNREADABLE,
            ImportError.TooLarge to ImportAcquisitionError.TOO_LARGE,
            ImportError.Unsupported(UnsupportedSource.UNKNOWN) to ImportAcquisitionError.UNSUPPORTED,
            ImportError.Unsupported(UnsupportedSource.SINGLE_FEED) to ImportAcquisitionError.SINGLE_FEED,
            ImportError.Hostile(HostileInput.ZIP_PATH) to ImportAcquisitionError.HOSTILE,
            ImportError.Empty to ImportAcquisitionError.EMPTY,
            ImportError.Storage to ImportAcquisitionError.STORAGE,
        )) {
            app.imports = stubImports { Outcome.Failure(error) }
            val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, view(uri)).create()
            pumpUntil { controller.get().isFinishing }
            val handoff = shadowOf(controller.get()).nextStartedActivity
            assertThat(handoff.component!!.className).isEqualTo(MainActivity::class.java.name)
            assertThat(handoff.getStringExtra(IMPORT_ERROR_EXTRA)).isEqualTo(code.name)
            assertThat(handoff.data).isNull()
            assertThat(handoff.clipData).isNull()
            assertThat(handoff.flags and GRANT_FLAGS).isEqualTo(0)
            controller.destroy()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rotationKeepsOneCopyAndProgressWaitsThreeHundredMilliseconds() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val result = CompletableDeferred<Outcome<Long, ImportError>>()
                val copies = AtomicInteger()
                app.imports =
                    stubImports {
                        copies.incrementAndGet()
                        result.await()
                    }
                val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, view(uri)).setup()
                try {
                    // Anchor the delay at a controlled coroutine instant, not elapsed activity setup time.
                    runCurrent()
                    pumpUntil {
                        runCurrent()
                        copies.get() == 1
                    }
                    assertThat(copies.get()).isEqualTo(1)
                    assertThat(controller.get().findViewById<FrameLayout>(android.R.id.content).childCount).isEqualTo(0)
                    advanceTimeBy(299)
                    runCurrent()
                    assertThat(controller.get().findViewById<FrameLayout>(android.R.id.content).childCount).isEqualTo(0)
                    advanceTimeBy(1)
                    runCurrent()
                    assertThat(controller.get().findViewById<FrameLayout>(android.R.id.content).childCount).isEqualTo(1)
                    controller.recreate()
                    runCurrent()
                    assertThat(copies.get()).isEqualTo(1)
                    assertThat(controller.get().isFinishing).isFalse()
                    result.complete(Outcome.Success(42L))
                    pumpUntil {
                        runCurrent()
                        controller.get().isFinishing
                    }
                    assertThat(copies.get()).isEqualTo(1)
                    assertCleanHandoff(shadowOf(controller.get()).nextStartedActivity, 42L)
                } finally {
                    controller.pause().stop().destroy()
                    result.cancel()
                    runCurrent()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun finishingDuringAcquisitionCancelsTheCaller() {
        val waiting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        app.imports =
            stubImports {
                started.complete(Unit)
                try {
                    waiting.await()
                    Outcome.Success(42L)
                } finally {
                    cancelled.complete(Unit)
                }
            }
        val controller = Robolectric.buildActivity(ExternalImportActivity::class.java, view(uri)).create()
        pumpUntil { started.isCompleted }
        controller.get().finish()
        controller.destroy()
        pumpUntil { cancelled.isCompleted }
        assertThat(cancelled.isCompleted).isTrue()
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
    }

    private fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).addFlags(GRANT_FLAGS)

    private fun assertCleanHandoff(
        handoff: Intent,
        id: Long,
    ) {
        assertThat(handoff.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(handoff.component!!.className).isEqualTo(MainActivity::class.java.name)
        assertThat(handoff.dataString).isEqualTo("neutrodyne://open/import/$id")
        assertThat(handoff.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertThat(handoff.clipData).isNull()
        assertThat(handoff.extras).isNull()
    }

    private companion object {
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        const val OPML =
            "<opml version=\"2.0\"><body><outline text=\"Show\" type=\"rss\" " +
                "xmlUrl=\"https://example.org/feed\"/></body></opml>"
    }
}
