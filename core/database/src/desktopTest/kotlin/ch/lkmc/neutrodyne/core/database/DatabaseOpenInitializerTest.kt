// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Band 100 holds the existing sequence, rather than retrying or reinstalling higher bands. */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseOpenInitializerTest {
    @Test
    fun failedOpenSuspendsHigherBandsUntilGateRetry() =
        runTest {
            val fixture = Fixture()
            val opener = fixture.opener(StandardTestDispatcher(testScheduler))
            val calls = mutableListOf<Int>()
            fixture.storage = Storage.FAILED
            val startup = launch { runInitializers(initializers(opener, calls)) }
            try {
                runCurrent()
                assertIs<DatabaseOpenState.Failed>(opener.openState.value)
                assertFalse(startup.isCompleted)
                assertEquals(listOf(0), calls)

                // A failed gate retry does not advance the loop or trigger an automatic open.
                assertFailsWith<DatabaseOpenException> { opener.awaitOpen() }
                runCurrent()
                assertEquals(listOf(0), calls)
                assertEquals(2, fixture.attempts)

                fixture.storage = Storage.READY
                opener.awaitOpen()
                startup.join()
                assertEquals(listOf(0, 120, 200, 220), calls)
                opener.awaitOpen()
                opener.awaitOpen()
                runCurrent()
                assertEquals(listOf(0, 120, 200, 220), calls)
                assertEquals(3, fixture.attempts)
            } finally {
                startup.cancelAndJoin()
                fixture.close(opener)
            }
        }

    @Test
    fun healthyOpenPreservesBandOrder() =
        runTest {
            val fixture = Fixture()
            val opener = fixture.opener(StandardTestDispatcher(testScheduler))
            val calls = mutableListOf<Int>()
            try {
                runInitializers(initializers(opener, calls))
                assertEquals(listOf(0, 120, 200, 220), calls)
                assertIs<DatabaseOpenState.Opened>(opener.openState.value)
                opener.awaitOpen()
                assertEquals(1, fixture.attempts)
            } finally {
                fixture.close(opener)
            }
        }

    @Test
    fun cancellationWhileWaitingStopsHigherBands() =
        runTest {
            val fixture = Fixture()
            val opener = fixture.opener(StandardTestDispatcher(testScheduler))
            val calls = mutableListOf<Int>()
            fixture.storage = Storage.FAILED
            val startup = launch { runInitializers(initializers(opener, calls)) }
            try {
                runCurrent()
                assertIs<DatabaseOpenState.Failed>(opener.openState.value)
                startup.cancelAndJoin()
                fixture.storage = Storage.READY
                opener.awaitOpen()
                runCurrent()
                assertEquals(listOf(0), calls)
            } finally {
                startup.cancelAndJoin()
                fixture.close(opener)
            }
        }

    private fun initializers(
        opener: DatabaseOpener,
        calls: MutableList<Int>,
    ): Set<AppInitializer> =
        setOf(DatabaseOpenInitializer(opener)) +
            listOf(220, 0, 200, 120).map { band ->
                object : AppInitializer {
                    override val order = band

                    override suspend fun run() {
                        calls += band
                        if (band > 100) assertIs<DatabaseOpenState.Opened>(opener.openState.value)
                    }
                }
            }

    private class Fixture {
        private val root = Files.createTempDirectory("nd-open-initializer")
        private val dirs = AppDirs(root, root, root, root, root, root)
        private val delegate = DesktopDatabaseFactory(dirs)
        var storage = Storage.READY
        var attempts = 0
            private set

        private val factory =
            object : DatabaseFactory by delegate {
                override fun exists(): Boolean {
                    attempts += 1
                    if (storage == Storage.FAILED) throw IOException("storage unavailable")
                    return delegate.exists()
                }
            }

        fun opener(io: kotlinx.coroutines.CoroutineDispatcher) =
            DatabaseOpener(
                factory,
                BundledSQLiteDriver(),
                io,
                TestClock(),
                strictMigrations = true,
            )

        fun close(opener: DatabaseOpener) {
            if (opener.openState.value is DatabaseOpenState.Opened) opener.requireDatabase().close()
            root.toFile().deleteRecursively()
        }
    }

    private enum class Storage { FAILED, READY }
}
