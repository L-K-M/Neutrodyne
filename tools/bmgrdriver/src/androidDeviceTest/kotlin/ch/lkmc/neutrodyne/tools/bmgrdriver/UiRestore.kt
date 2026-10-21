// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

/**
 * UiAutomator driving of the production restore surface (05 Receiving files / Restore preview).
 * Selectors only use the published English text and standard semantics — no test tags, no
 * target internals.
 */
internal object UiRestore {
    // Production English strings (core/ui compose resources `restore.xml`).
    private const val TITLE = "Restore backup"
    private const val MODE_MERGE = "Merge"
    const val CAT_HISTORY = "Listening history"
    const val CAT_UP_NEXT = "Up next"
    const val CAT_SETTINGS = "Settings"
    private const val ACTION_RESTORE = "Restore"

    private val RESTORE_EXACT: Pattern = Pattern.compile("^$ACTION_RESTORE\$")

    private const val WAIT_LONG = 120_000L
    private const val WAIT_SHORT = 15_000L
    private const val WAIT_CLICK = 5_000L

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Grant + launch the production `ExternalImportActivity` on a vault file's URI. */
    fun importViaProductionUi(file: File): Uri {
        val uri = FileProvider.getUriForFile(context, SeedVault.AUTHORITY, file)
        context.grantUriPermission(
            BmgrArming.TARGET_PACKAGE,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/zip")
                .setPackage(BmgrArming.TARGET_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        return uri
    }

    /** Wait for the restore preview (top bar title + Merge radio row). */
    fun awaitPreview() {
        check(device.wait(Until.hasObject(By.text(TITLE)), WAIT_LONG)) {
            "restore preview title '$TITLE' never appeared"
        }
        check(device.wait(Until.hasObject(By.text(MODE_MERGE)), WAIT_SHORT)) {
            "restore preview mode row '$MODE_MERGE' missing"
        }
    }

    /**
     * The category row is one merged `toggleable(Role.Checkbox)` semantics node carrying the
     * label text and the checked state; [want] drives click-or-leave so both orders of the
     * default-check matrix work.
     */
    fun setCategory(
        label: String,
        want: Boolean,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_SHORT
        while (true) {
            val node =
                device.wait(Until.findObject(By.text(label)), WAIT_CLICK)
                    ?: throw IllegalStateException("category row '$label' never resolved")
            if (node.isChecked == want) return
            node.click()
            check(System.currentTimeMillis() < deadline) {
                "category row '$label' never reached checked=$want"
            }
            Thread.sleep(300)
        }
    }

    fun selectMerge() {
        val node =
            device.wait(Until.findObject(By.text(MODE_MERGE)), WAIT_CLICK)
                ?: throw IllegalStateException("mode row '$MODE_MERGE' never resolved")
        if (!node.isSelected) node.click()
    }

    /** Click the Restore action and wait until the request is accepted (button gone). */
    fun requestRestore() {
        val button =
            device.wait(Until.findObject(By.text(RESTORE_EXACT)), WAIT_CLICK)
                ?: throw IllegalStateException("restore action never resolved")
        button.click()
        check(device.wait(Until.gone(By.text(RESTORE_EXACT)), WAIT_SHORT)) {
            "restore request was never accepted"
        }
    }

    /** Negative leg: the error dialog text wins; the restore preview must never appear. */
    fun awaitRejection(timeout: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (
                device.hasObject(By.text(SeedVault.TEXT_UNSUPPORTED)) ||
                device.hasObject(By.text(SeedVault.TEXT_HOSTILE))
            ) {
                check(!device.hasObject(By.text(TITLE))) { "restore preview shown for encrypted blob" }
                return true
            }
            check(!device.hasObject(By.text(TITLE))) { "restore preview shown for encrypted blob" }
            Thread.sleep(250)
        }
        return false
    }
}
