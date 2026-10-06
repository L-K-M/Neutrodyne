// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppInitializerTest {
    private class Fake(
        override val order: Int,
        private val name: String,
        private val calls: MutableList<String>,
        private val fail: Throwable? = null,
    ) : AppInitializer {
        override suspend fun run() {
            calls += name
            fail?.let { throw it }
        }
    }

    @AfterTest
    fun tearDown() = Log.install()

    @Test
    fun `runs sequentially in band order`() =
        runTest {
            val calls = mutableListOf<String>()
            val set =
                setOf(
                    Fake(300, "late", calls),
                    Fake(25, "early", calls),
                    Fake(80, "mid", calls),
                )
            runInitializers(set)
            assertEquals(listOf("early", "mid", "late"), calls)
        }

    @Test
    fun `a failure is logged and later initializers still run`() =
        runTest {
            val errors = mutableListOf<String>()
            Log.install(
                LogSink { level, _, message ->
                    if (level == LogLevel.ERROR) errors += message
                },
            )
            val calls = mutableListOf<String>()
            runInitializers(
                setOf(
                    Fake(25, "first", calls),
                    Fake(50, "broken", calls, fail = IllegalStateException("boom")),
                    Fake(80, "last", calls),
                ),
            )
            assertEquals(listOf("first", "broken", "last"), calls)
            assertTrue(errors.single().contains("Fake"))
        }

    @Test
    fun `cancellation propagates and stops the loop`() =
        runTest {
            val calls = mutableListOf<String>()
            assertFailsWith<CancellationException> {
                runInitializers(
                    setOf(
                        Fake(25, "first", calls, fail = CancellationException("stop")),
                        Fake(80, "never", calls),
                    ),
                )
            }
            assertEquals(listOf("first"), calls)
        }
}
