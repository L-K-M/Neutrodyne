// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The redaction contract of 01 Logging and redaction. Cases mirror the reference implementation
 * built on `java.net.URI` — same outputs on the listed inputs.
 */
class RedactorTest {
    @Test
    fun `user info is masked before the host`() {
        assertEquals(
            "https://***@example.com/feed",
            Redactor.url("https://user:pass@example.com/feed"),
        )
    }

    @Test
    fun `user info keeps scheme host and port`() {
        assertEquals(
            "https://***@example.com:8443/rss/…xQ",
            Redactor.url("https://u:p@example.com:8443/rss/a8F3kq09ZpLm2xQ"),
        )
    }

    @Test
    fun `query values are masked while names stay`() {
        assertEquals(
            "https://example.com/f?token=…&id=…&flag",
            Redactor.url("https://example.com/f?token=abc123&id=7&flag"),
        )
    }

    @Test
    fun `long digit-bearing path segments are masked`() {
        assertEquals(
            "https://example.com/rss/…xQ",
            Redactor.url("https://example.com/rss/a8F3kq09ZpLm2xQ"),
        )
    }

    @Test
    fun `ordinary path segments survive`() {
        assertEquals(
            "https://example.com/feed/podcast",
            Redactor.url("https://example.com/feed/podcast"),
        )
    }

    @Test
    fun `long segment without a digit survives`() {
        assertEquals(
            "https://example.com/rss/abcdefghijklmnopq",
            Redactor.url("https://example.com/rss/abcdefghijklmnopq"),
        )
    }

    @Test
    fun `short digit-bearing segment survives`() {
        assertEquals(
            "https://example.com/ep/123",
            Redactor.url("https://example.com/ep/123"),
        )
    }

    @Test
    fun `fragment is dropped with its hash`() {
        assertEquals(
            "https://example.com/f?a=…",
            Redactor.url("https://example.com/f?a=1#section-two"),
        )
    }

    @Test
    fun `http and https schemes are kept`() {
        assertEquals("http://example.com/f", Redactor.url("http://example.com/f"))
        assertEquals("https://example.com/f", Redactor.url("https://example.com/f"))
    }

    @Test
    fun `ipv6 literal host and port survive`() {
        assertEquals(
            "https://[2001:db8::1]:8080/feed",
            Redactor.url("https://[2001:db8::1]:8080/feed"),
        )
    }

    @Test
    fun `unparsable input logs length only`() {
        val raw = "not a url at all"
        assertEquals("<unparsable url, ${raw.length} chars>", Redactor.url(raw))
        assertFalse(Redactor.url(raw).contains("not"))
    }

    @Test
    fun `feed scheme wraps an inner url`() {
        assertEquals(
            "feed:https://***@example.com/rss/…xQ",
            Redactor.url("feed:https://u:p@example.com/rss/a8F3kq09ZpLm2xQ"),
        )
    }

    @Test
    fun `url redaction is idempotent`() {
        val inputs =
            listOf(
                "https://u:p@example.com:8443/rss/a8F3kq09ZpLm2xQ?token=abc#frag",
                "http://example.com/feed/podcast",
                "feed:https://u:p@example.com/f?t=9",
                "not a url",
            )
        for (input in inputs) {
            val once = Redactor.url(input)
            assertEquals(once, Redactor.url(once), "not idempotent for $input")
        }
    }

    @Test
    fun `free text redacts two urls and keeps the trailing period`() {
        assertEquals(
            "refreshed https://a.test/feed ok, then https://b.test/rss/…Z9 failed.",
            Redactor.text("refreshed https://a.test/feed ok, then https://b.test/rss/aaZZaaZZaaZZaaZ9 failed."),
        )
    }

    @Test
    fun `free text leaves non-url words alone`() {
        assertEquals("plain message, no links", Redactor.text("plain message, no links"))
    }

    @Test
    fun `free text redacts feed urls too`() {
        assertEquals(
            "subscribed feed:https://***@h.test/f?key=…",
            Redactor.text("subscribed feed:https://u:p@h.test/f?key=sekrit"),
        )
    }

    @Test
    fun `authorization and cookie headers lose their values`() {
        assertEquals(
            "req failed Authorization: …",
            Redactor.text("req failed Authorization: Basic dXNlcjpwYXNz"),
        )
        assertEquals(
            "sent Cookie: …",
            Redactor.text("sent Cookie: session=abc123; theme=dark"),
        )
    }

    @Test
    fun `free text redacts urls with a bracketed ipv6 host`() {
        val text = Redactor.text("failed: https://[2001:db8::1]:8443/rss/a8F3kq09ZpLm2xQ?token=SECRET (retrying)")

        assertEquals("failed: https://[2001:db8::1]:8443/rss/…xQ?token=… (retrying)", text)
    }

    @Test
    fun `free text redacts user info before a bracketed ipv6 host`() {
        val text = Redactor.text("GET https://alice:s3cret@[::1]/feed")

        assertEquals("GET https://***@[::1]/feed", text)
    }

    @Test
    fun `free text redacts credentials and query around a bracketed ipv6 host`() {
        val text =
            Redactor.text(
                "GET https://alice:password@[2001:db8::1]:8443/rss/a8F3kq09ZpLm2xQ?token=SECRET failed",
            )

        assertEquals("GET https://***@[2001:db8::1]:8443/rss/…xQ?token=… failed", text)
    }

    @Test
    fun `free text redacts an ipv6 literal inside a feed-wrapped url`() {
        val text = Redactor.text("sub feed:https://u:p@[2001:db8::1]/f?key=sekrit")

        assertEquals("sub feed:https://***@[2001:db8::1]/f?key=…", text)
    }

    @Test
    fun `free text redacts a url bracketed after a label`() {
        val text = Redactor.text("URL:[https://alice:pass@example.test/feed?token=SECRET]")

        assertEquals("URL:[https://***@example.test/feed?token=…]", text)
    }

    @Test
    fun `free text redacts a url glued to a label`() {
        val text = Redactor.text("Error:https://alice:pass@example.test/feed?token=SECRET")

        assertEquals("Error:https://***@example.test/feed?token=…", text)
    }

    // Review 4 (2026-10-06): adversarial free-text inputs that leaked through the regex scan.

    @Test
    fun `free text redacts around a percent-encoded ipv6 zone id`() {
        val text = Redactor.text("https://u:p@[fe80::1%25en%30]/f?token=SECRET")

        assertEquals("https://***@[fe80::1%25en%30]/f?token=…", text)
    }

    @Test
    fun `free text keeps an apostrophe inside the url run`() {
        val text = Redactor.text("https://alice:p'ass@example.test/feed?token=SECRET")

        assertEquals("https://***@example.test/feed?token=…", text)
    }

    @Test
    fun `free text redacts each of two urls joined by a comma or semicolon`() {
        assertEquals(
            "https://a.test/f,https://***@b.test/g?token=…",
            Redactor.text("https://a.test/f,https://alice:pass@b.test/g?token=SECRET"),
        )
        assertEquals(
            "https://a.test/f;https://***@b.test/g?token=…",
            Redactor.text("https://a.test/f;https://alice:pass@b.test/g?token=SECRET"),
        )
    }

    @Test
    fun `nested feed schemes unwrap to the inner url`() {
        val raw = "feed:podcast:https://alice:pass@example.test/feed?token=SECRET"
        val expected = "feed:podcast:https://***@example.test/feed?token=…"

        assertEquals(expected, Redactor.text(raw))
        assertEquals(expected, Redactor.url(raw))
    }

    @Test
    fun `user info that looks like a scheme is still masked`() {
        assertEquals("https://***@host.test/f", Redactor.text("https://http:pass@host.test/f"))
    }

    @Test
    fun `a very long url run does not exhaust the stack`() {
        val raw = "https://h.test/" + "a".repeat(LONG_RUN_LENGTH)

        assertEquals(raw, Redactor.text(raw))
    }

    @Test
    fun `many labels before a url neither recurse deeply nor leak`() {
        val labels = "x:".repeat(LABEL_COUNT)

        assertEquals("${labels}https://***@h.test/f?t=…", Redactor.text("${labels}https://u:p@h.test/f?t=SECRET"))
    }

    @Test
    fun `free text keeps a bare scheme word with nothing after its colon`() {
        val text = Redactor.text("expected https: or feed: here")

        assertEquals("expected https: or feed: here", text)
    }

    @Test
    fun `url masks user info on a bracketed ipv6 host with port`() {
        assertEquals(
            "https://***@[::1]:8443/f?a=…",
            Redactor.url("https://u:p@[::1]:8443/f?a=1"),
        )
    }

    private companion object {
        const val LONG_RUN_LENGTH = 100_000
        const val LABEL_COUNT = 10_000
    }
}
