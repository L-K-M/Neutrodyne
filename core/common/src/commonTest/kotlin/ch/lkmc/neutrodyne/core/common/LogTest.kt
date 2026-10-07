// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {
    private data class Recording(
        val level: LogLevel,
        val tag: String,
        val message: String,
    )

    private val records = mutableListOf<Recording>()
    private val sink =
        LogSink { level, tag, message ->
            records += Recording(level, tag, message)
        }

    @AfterTest
    fun tearDown() = Log.install()

    @Test
    fun `installed sinks receive level tag and the message with the rendered throwable`() {
        Log.install(sink)
        Log.w("Tag", IllegalStateException("x")) { "hello" }

        val record = records.single()
        assertEquals(LogLevel.WARN, record.level)
        assertEquals("Tag", record.tag)
        assertTrue(record.message.startsWith("hello\n"))
        assertTrue(record.message.contains("IllegalStateException: x"))
    }

    @Test
    fun `throwable messages and causes are redacted before any sink sees them`() {
        Log.install(sink)
        val cause = IllegalArgumentException("cause https://bob:hunter2@cdn.example/a.mp3?auth=CAUSESECRET")
        val failure = RuntimeException("GET https://u:password@example.com/feed?token=SECRET", cause)

        Log.w("Net", failure) { "fetch failed" }

        val message = records.single().message
        assertFalse(message.contains("password"))
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("CAUSESECRET"))
    }

    @Test
    fun `the lazy message is not evaluated without sinks`() {
        Log.install()
        var evaluated = false
        Log.d("Tag") {
            evaluated = true
            "never"
        }
        assertFalse(evaluated)
    }

    @Test
    fun `every message passes through the redactor`() {
        Log.install(sink)
        Log.i("Net") { "GET https://u:p@example.com/rss/a8F3kq09ZpLm2xQ?token=abc" }
        assertEquals(
            "GET https://***@example.com/rss/…xQ?token=…",
            records.single().message,
        )
    }

    @Test
    fun `install replaces earlier sinks`() {
        val second = mutableListOf<String>()
        Log.install(sink)
        Log.install(LogSink { _, _, message -> second += message })
        Log.d("Tag") { "hi" }
        assertTrue(records.isEmpty())
        assertEquals(listOf("hi"), second)
    }

    @Test
    fun `a throwing sink does not break other sinks or callers`() {
        val seen = mutableListOf<String>()
        Log.install(
            LogSink { _, _, _ -> throw RuntimeException("sink boom") },
            LogSink { _, _, message -> seen += message },
        )
        Log.e("Tag") { "still delivered" }
        assertEquals(listOf("still delivered"), seen)
    }
}
