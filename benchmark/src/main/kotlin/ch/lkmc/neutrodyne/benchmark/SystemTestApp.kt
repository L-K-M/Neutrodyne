// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.benchmark

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shell commands run outside the target process. */
internal class SystemTestApp {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)

    fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText().trim() }

    fun reset() {
        val info = instrumentation.context.packageManager.getApplicationInfo(PACKAGE, 0)
        assertEquals(
            "target must be minified non-debuggable fallback",
            0,
            info.flags and ApplicationInfo.FLAG_DEBUGGABLE,
        )
        if (Build.VERSION.SDK_INT >= 29) assertTrue("target must be profileable fallback", info.isProfileableByShell)
        assertTrue("clear target data", shell("pm clear $PACKAGE").contains("Success"))
        shell("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        // Stable English selectors; the target's saved per-app language was cleared above.
        shell("cmd locale set-app-locales $PACKAGE --user 0 --locales en-US")
        launch()
    }

    fun launch() {
        val output = shell("am start -W -n $PACKAGE/ch.lkmc.neutrodyne.MainActivity")
        assertTrue(output, output.contains("Status: ok"))
        await("fixture receiver ready") { probe("ready").getInt("pid") > 0 }
        node(By.res("nav_feeds"))
    }

    fun background() {
        val output = shell("am start -W -n $TEST_PACKAGE/.BackgroundStandInActivity")
        assertTrue(output, output.contains("Status: ok"))
        // ProcessLifecycleOwner debounces onStop; a still-visible target could schedule UIDT.
        SystemClock.sleep(BACKGROUND_SETTLE_MS)
    }

    fun probe(
        command: String,
        url: String? = null,
    ): JSONObject {
        require(command in setOf("ready", "seed", "resume", "status"))
        require(url == null || url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/episode\\.wav")))
        val output =
            shell(
                "am broadcast --receiver-foreground -a ch.lkmc.neutrodyne.SYSTEM_TEST -p $PACKAGE" +
                    " --es command $command" + (url?.let { " --es url $it" } ?: ""),
            )
        val data = checkNotNull(Regex("data=\"([A-Za-z0-9+/=]+)\"").find(output)) { output }
        val json = JSONObject(String(Base64.decode(data.groupValues[1], Base64.DEFAULT)))
        check(output.contains("result=1") && !json.has("error")) { json.toString() }
        check(json.isNull("runnerError")) { json.toString() }
        return json
    }

    fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), UI_TIMEOUT_MS)) {
            "Missing UI node $selector"
        }

    fun clickText(text: String) = node(By.text(text)).click()

    fun await(
        description: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastFailure: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (condition()) return
            } catch (error: Exception) {
                lastFailure = error
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Timed out: $description", lastFailure)
    }

    fun clear() {
        assertTrue("clear target data", shell("pm clear $PACKAGE").contains("Success"))
    }

    fun subscribe(server: FixtureServer) {
        node(By.res("nav_discover")).click()
        clickText("Add by URL")
        node(By.clazz("android.widget.EditText")).text = server.feedUrl
        device.pressBack()
        clickText("Subscribe")
        node(By.text(FixtureServer.PODCAST))
        clickText("Subscribe")
        clickText("Open")
        node(By.text(FixtureServer.EPISODE))
    }

    companion object {
        const val PACKAGE = "ch.lkmc.neutrodyne"
        const val TEST_PACKAGE = "ch.lkmc.neutrodyne.benchmark"
        const val UI_TIMEOUT_MS = 30_000L
        const val TRANSFER_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 200L
        private const val BACKGROUND_SETTLE_MS = 1_500L
    }
}
