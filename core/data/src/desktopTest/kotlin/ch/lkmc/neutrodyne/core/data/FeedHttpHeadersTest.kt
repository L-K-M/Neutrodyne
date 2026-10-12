// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.fetch.FeedHttpHeaders
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedHttpHeadersTest {
    @Test
    fun `max-age matches the directive name, not a prefix`() {
        assertThat(FeedHttpHeaders.maxAgeSec("max-agency=120")).isNull()
        assertThat(FeedHttpHeaders.maxAgeSec("s-maxage=5")).isNull()
        assertThat(FeedHttpHeaders.maxAgeSec("max-age=120")).isEqualTo(120L)
        assertThat(FeedHttpHeaders.maxAgeSec("no-cache, MAX-AGE=30")).isEqualTo(30L)
        assertThat(FeedHttpHeaders.maxAgeSec("max-age")).isNull()
        assertThat(FeedHttpHeaders.maxAgeSec("max-age=abc")).isNull()
    }

    @Test
    fun `retry-after clamps negative and overflowing deltas`() {
        assertThat(FeedHttpHeaders.retryAfterMs("-5", 0L)).isEqualTo(0L)
        assertThat(FeedHttpHeaders.retryAfterMs("0", 0L)).isEqualTo(0L)
        assertThat(FeedHttpHeaders.retryAfterMs("90", 0L)).isEqualTo(90_000L)
        assertThat(FeedHttpHeaders.retryAfterMs("9999999999999999", 0L)).isEqualTo(Long.MAX_VALUE)
        assertThat(FeedHttpHeaders.retryAfterMs("9223372036854775807", 0L)).isEqualTo(Long.MAX_VALUE)
    }
}
