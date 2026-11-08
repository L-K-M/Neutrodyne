// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.identity.WindowsShortcutIdentityInitializer
import ch.lkmc.neutrodyne.desktop.links.UrlSchemeRegistrationInitializer
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.os.OsRegistrationInitializer
import ch.lkmc.neutrodyne.desktop.os.withoutOsRegistrations
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test

/**
 * 11 Smoke mode step 1's "no URL-scheme, file-association or login-item registration"
 * rule against the real journey machinery: [ShellJourney]'s prologue — the code the
 * smoke and AOT-training modes share — must never run the graph's OS-registration
 * initializers, which on a real Windows image would rewrite the user's own HKCU keys
 * and Start-menu shortcut while testing a temporary image. The production shell
 * still runs the whole set.
 */
class ShellJourneyTest {
    @After
    fun tearDown() {
        Log.install()
    }

    @Test
    fun `the prologue logs the OS registrations it skips`() {
        val messages = mutableListOf<String>()
        Log.install(LogSink { _, _, message -> messages += message })

        val journey = ShellJourney("test-journey-")
        try {
            journey.runPrologue()
        } finally {
            journey.disarmWatchdog()
            runCatching { journey.cleanUp() }
        }

        val skipped = messages.filter { it.startsWith(SKIPPED_PREFIX) }
        assertThat(skipped).hasSize(1)
        assertThat(skipped.single()).contains("UrlSchemeRegistrationInitializer")
        assertThat(skipped.single()).contains("WindowsShortcutIdentityInitializer")
    }

    @Test
    fun `a smoke run logs the skip, applies the process identity and exits 0`() {
        val messages = mutableListOf<String>()
        val lines = mutableListOf<String>()
        Log.install(LogSink { _, _, message -> messages += message })

        val exit = SmokeMode(output = lines::add, windowStep = { FIRST_FRAME_MS }).run()

        assertThat(exit).isEqualTo(0)
        assertThat(messages.any { it.startsWith(SKIPPED_PREFIX) }).isTrue()
        val steps = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject["steps"]!!.jsonObject
        assertThat(steps.keys).contains("processIdentity")
    }

    @Test
    fun `the journey selection drops only the OS registrations`() {
        val dirs = tempAppDirs().also { it.ensureCreated() }
        val buildInfo = BuildInfoLoader.load()
        val graph =
            createDesktopGraph(dirs, buildInfo, DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer()))
        try {
            // Metro materialises new contributions per access — capture one set.
            val all = graph.initializers
            val marked = all.filterIsInstance<OsRegistrationInitializer>()
            val selected = all.withoutOsRegistrations()

            // The production shell runs the graph's full set — both registrations stay in
            // it and they are its only OS-registration initializers.
            assertThat(marked.map { it::class })
                .containsExactly(
                    UrlSchemeRegistrationInitializer::class,
                    WindowsShortcutIdentityInitializer::class,
                )
            assertThat(selected.map { it::class })
                .containsNoneIn(marked.map { it::class })
            assertThat(selected).hasSize(all.size - marked.size)
        } finally {
            graph.appScope.cancel()
        }
    }

    private companion object {
        /** The prologue's report of the OS registrations it excluded (11 Smoke mode step 1). */
        private const val SKIPPED_PREFIX = "controlled journey: skipped host OS registrations:"
        private const val FIRST_FRAME_MS = 42L
    }
}
