// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.common.AppDirs
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 11 Smoke mode step 1's "no URL-scheme, file-association or login-item registration"
 * rule against the real journey machinery: [ShellJourney]'s prologue — the code the
 * smoke and AOT-training modes share — must never run the graph's OS-registration
 * initializers, which on a real Windows image would rewrite the user's own HKCU keys
 * and Start-menu shortcut while testing a temporary image. The production shell
 * still runs the whole set.
 */
class ShellJourneyTest {
    @Test
    fun `cleanup does not change permissions through symbolic links`() {
        val outside = Files.createTempDirectory("test-journey-outside-")
        try {
            assumeTrue("POSIX symlink permission check", Files.getFileStore(outside).supportsFileAttributeView("posix"))
            val target = Files.writeString(outside.resolve("target"), "outside fixture")
            assertThat(target.toFile().setReadOnly()).isTrue()
            val permissions = Files.getPosixFilePermissions(target)
            val root = Files.createTempDirectory("test-journey-symlink-")
            val journey = ShellJourney("test-journey-", createRoot = { root })
            try {
                Files.createSymbolicLink(root.resolve("link"), target)

                journey.cleanUp()

                assertThat(Files.exists(root)).isFalse()
                assertThat(Files.readString(target)).isEqualTo("outside fixture")
                assertThat(Files.getPosixFilePermissions(target)).isEqualTo(permissions)
            } finally {
                journey.disarmWatchdog()
                if (Files.exists(root)) {
                    Files.deleteIfExists(root.resolve("link"))
                    Files.deleteIfExists(root)
                }
                target.toFile().setWritable(true, true)
            }
        } finally {
            Files.walk(outside).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `cleanup removes read-only compiled engine files and directories`() {
        val root = Files.createTempDirectory("test-journey-readonly-")
        val lib = Files.createDirectories(root.resolve("data/ytdlp/versions/2026.08.19/lib"))
        val compiled = Files.writeString(lib.resolve("engine.pyc"), "compiled fixture")
        val journey = ShellJourney("test-journey-", createRoot = { root })
        try {
            assertThat(compiled.toFile().setReadOnly()).isTrue()
            assertThat(lib.toFile().setReadOnly()).isTrue()

            journey.cleanUp()

            assertThat(Files.exists(root)).isFalse()
        } finally {
            journey.disarmWatchdog()
            // The pre-fix implementation cannot delete the fixture; restore test-owned modes.
            if (Files.exists(root)) {
                Files.walk(root).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    @Test
    fun `restoreWritable returns a read-only POSIX entry to owner-writable`() {
        val dir = Files.createTempDirectory("test-journey-posix-")
        try {
            assumeTrue("POSIX permission check", Files.getFileStore(dir).supportsFileAttributeView("posix"))
            val file = Files.writeString(dir.resolve("engine.pyc"), "compiled fixture")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
            assumeTrue("the read-only mode binds this account", !Files.isWritable(file))

            ShellJourney.restoreWritable(file)

            assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"))
            assertThat(Files.isWritable(file)).isTrue()
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `restoreWritable clears the DOS read-only attribute on Windows`() {
        assumeTrue("DOS host check", AppDirs.DesktopOs.current() == AppDirs.DesktopOs.WINDOWS)
        val dir = Files.createTempDirectory("test-journey-dos-")
        try {
            val file = Files.writeString(dir.resolve("engine.pyc"), "compiled fixture")
            Files.setAttribute(file, "dos:readonly", true)
            Files.setAttribute(dir, "dos:readonly", true)

            ShellJourney.restoreWritable(file)
            ShellJourney.restoreWritable(dir)

            assertThat(Files.getAttribute(file, "dos:readonly")).isEqualTo(false)
            assertThat(Files.getAttribute(dir, "dos:readonly")).isEqualTo(false)
        } finally {
            runCatching { Files.setAttribute(dir.resolve("engine.pyc"), "dos:readonly", false) }
            runCatching { Files.setAttribute(dir, "dos:readonly", false) }
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `restoreWritable never restores through a symbolic link`() {
        val outside = Files.createTempDirectory("test-journey-link-target-")
        try {
            assumeTrue("POSIX symlink permission check", Files.getFileStore(outside).supportsFileAttributeView("posix"))
            val permissions = PosixFilePermissions.fromString("r--r--r--")
            val target = Files.writeString(outside.resolve("target"), "outside fixture")
            Files.setPosixFilePermissions(target, permissions)
            val inside = Files.createTempDirectory("test-journey-link-")
            try {
                val link = Files.createSymbolicLink(inside.resolve("link"), target)

                ShellJourney.restoreWritable(link)

                assertThat(Files.getPosixFilePermissions(target)).isEqualTo(permissions)
            } finally {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
                Files.walk(inside).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        } finally {
            Files.walk(outside).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `restoreWritable leaves a writable mode alone`() {
        val dir = Files.createTempDirectory("test-journey-writable-")
        try {
            assumeTrue("POSIX permission check", Files.getFileStore(dir).supportsFileAttributeView("posix"))
            val file = Files.writeString(dir.resolve("engine.pyc"), "compiled fixture")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))

            ShellJourney.restoreWritable(file)

            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `restoreWritable accepts an entry that already vanished`() {
        val dir = Files.createTempDirectory("test-journey-vanished-")
        try {
            ShellJourney.restoreWritable(dir.resolve("gone"))
        } finally {
            Files.deleteIfExists(dir)
        }
    }

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
