// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crash

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** ACRA's own log lines pass the same redaction as ours (review 2026-10-06). */
class RedactingAcraLogTest {
    private val lines = mutableListOf<String>()

    @After
    fun tearDown() = Log.install()

    @Test
    fun uncaughtExceptionLoggedByAcraIsRedacted() {
        Log.install(LogSink { _, _, message -> lines += message })

        RedactingAcraLog.e("ACRA", "ACRA caught a RuntimeException", RuntimeException("GET $SECRET_URL"))
        RedactingAcraLog.d("ACRA", "collecting for $SECRET_URL")

        assertThat(lines).hasSize(2)
        lines.forEach { assertThat(it).doesNotContain(TOKEN) }
        assertThat(RedactingAcraLog.getStackTraceString(IllegalStateException(SECRET_URL))).doesNotContain(TOKEN)
    }

    private companion object {
        const val TOKEN = "SECRETTOKEN"
        const val SECRET_URL = "https://feeds.example.invalid/rss?token=$TOKEN"
    }
}
