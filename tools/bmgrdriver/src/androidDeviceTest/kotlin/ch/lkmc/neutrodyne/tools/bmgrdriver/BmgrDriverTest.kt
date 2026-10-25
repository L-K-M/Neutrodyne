// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The armed bmgr driver phases (05 Testing with bmgr; owner decision 2026-10-21). Every method
 * re-verifies its arming triple, builds fixtures with the real `:feeds` codec in *this* process,
 * drives the published release app's `ExternalImportActivity` over UiAutomator and leaves a
 * durable status JSON plus a `bmgrDriver=` stream marker. The host owns all verification —
 * these methods only act, record, and fail loudly when the production UI does not behave.
 */
@RunWith(AndroidJUnit4::class)
class BmgrDriverTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedImport() {
        val nonce = BmgrArming.requireArmed("seed", "seedImport", InstrumentationRegistry.getArguments())
        val recorder = StatusRecorder("seed", nonce, context.packageName)
        try {
            val (content, seed) = SeedFixture.seedZip(nonce, File(SeedVault.dir(context), "seed.zip"))
            val sha = SeedFixture.sha256(seed)
            SeedFixture.writeSidecar(
                File(SeedVault.dir(context), "seed.expected.json"),
                SeedSidecar(
                    nonce = nonce,
                    api = Build.VERSION.SDK_INT,
                    seedSha256 = sha,
                    seedBytes = seed.length(),
                    fixture = content,
                ),
            )
            recorder.step("built", sha)
            UiRestore.importViaProductionUi(seed)
            recorder.step("launched")
            UiRestore.awaitPreview()
            recorder.step("preview")
            UiRestore.selectMerge()
            UiRestore.setCategory(UiRestore.CAT_HISTORY, true)
            UiRestore.setCategory(UiRestore.CAT_UP_NEXT, true)
            UiRestore.setCategory(UiRestore.CAT_SETTINGS, true)
            recorder.step("categories", "MERGE+{HISTORY,UP_NEXT,SETTINGS}")
            UiRestore.requestRestore()
            recorder.step("requested")
            finish("seed", nonce, recorder, "ok")
        } catch (t: Throwable) {
            finish("seed", nonce, recorder, "error:${t.javaClass.simpleName}:${t.message}", t)
        }
    }

    @Test
    fun settingsFlip() {
        val nonce = BmgrArming.requireArmed("flip", "settingsFlip", InstrumentationRegistry.getArguments())
        val recorder = StatusRecorder("flip", nonce, context.packageName)
        try {
            val flip = SeedFixture.flipZip(nonce, File(SeedVault.dir(context), "flip.zip"))
            recorder.step("built", SeedFixture.sha256(flip))
            UiRestore.importViaProductionUi(flip)
            recorder.step("launched")
            UiRestore.awaitPreview()
            recorder.step("preview")
            UiRestore.selectMerge()
            UiRestore.setCategory(UiRestore.CAT_HISTORY, false)
            UiRestore.setCategory(UiRestore.CAT_UP_NEXT, false)
            UiRestore.setCategory(UiRestore.CAT_SETTINGS, true)
            recorder.step("categories", "MERGE+{SETTINGS}")
            UiRestore.requestRestore()
            recorder.step("requested")
            finish("flip", nonce, recorder, "ok")
        } catch (t: Throwable) {
            finish("flip", nonce, recorder, "error:${t.javaClass.simpleName}:${t.message}", t)
        }
    }

    @Test
    fun encryptedImportRejected() {
        val nonce =
            BmgrArming.requireArmed("encrypted", "encryptedImportRejected", InstrumentationRegistry.getArguments())
        val recorder = StatusRecorder("encrypted", nonce, context.packageName)
        try {
            val blob = SeedFixture.encryptedBlob(File(SeedVault.dir(context), "encrypted.zip"))
            recorder.step("built", SeedFixture.sha256(blob))
            UiRestore.importViaProductionUi(blob)
            recorder.step("launched")
            check(UiRestore.awaitRejection(60_000L)) { "encrypted blob never produced the error dialog" }
            recorder.step("rejected")
            finish("encrypted", nonce, recorder, "ok")
        } catch (t: Throwable) {
            finish("encrypted", nonce, recorder, "error:${t.javaClass.simpleName}:${t.message}", t)
        }
    }

    private fun finish(
        phase: String,
        nonce: String,
        recorder: StatusRecorder,
        result: String,
        failure: Throwable? = null,
    ) {
        if (failure != null) {
            runCatching { UiRestore.captureFailure(phase) }
                .onFailure { recorder.step("ui-capture-error", it.toString()) }
        }

        recorder.write(SeedVault.dir(context), result)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("bmgrDriver", phaseStatusToken(phase, nonce, result)) },
        )
        failure?.let { throw it }
    }
}
