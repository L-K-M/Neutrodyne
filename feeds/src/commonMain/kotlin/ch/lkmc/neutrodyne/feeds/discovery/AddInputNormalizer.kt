// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.discovery

import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.identity.UrlUserInfo
import okio.Buffer
import kotlin.io.encoding.Base64

/** What [AddInputNormalizer] produced (03 Input normalisation). */
sealed interface NormalizedInput {
    /** A fetchable `http(s)` URL; [schemeGuessed] retries `http://` once on transport failure. */
    data class Url(
        val url: String,
        val schemeGuessed: Boolean,
        /** `user:pass@` credentials extracted from the URL (03 step 7). */
        val credentials: UrlUserInfo?,
    ) : NormalizedInput

    /** URL-shaped input with a non-`http(s)` scheme after unwrapping → `InvalidUrl`. */
    data object Invalid : NormalizedInput

    /** No URL-like token at all → `NotAUrl(query)` (directory search, M7). */
    data class NotAUrl(
        val query: String,
    ) : NormalizedInput
}

/** Pure add-input normalisation shared by Add and import (03). */
object AddInputNormalizer {
    /** 03 step 1: share-intent text is trimmed and capped. */
    private const val MAX_INPUT_CHARS = 4_096

    private val urlToken = Regex("""^(?:(?:https?|feed|pcast|podcast|itpc|neutrodyne):|[^\s/@]+\.[^\s@]+)""")

    fun normalize(raw: String): NormalizedInput = normalize(raw, NeutrodynePass.INITIAL)

    private enum class NeutrodynePass { INITIAL, RECURSIVE }

    private fun normalize(
        raw: String,
        pass: NeutrodynePass,
    ): NormalizedInput {
        var text = if (pass == NeutrodynePass.INITIAL) raw.trim().take(MAX_INPUT_CHARS) else raw

        // Step 1 applies only to shared text; decoded query values may legitimately contain spaces.
        if (pass == NeutrodynePass.INITIAL && text.any { it.isWhitespace() }) {
            val token =
                text.split(Regex("""\s+""")).firstOrNull { candidate ->
                    !candidate.startsWith("@") && urlToken.containsMatchIn(candidate)
                } ?: return NormalizedInput.NotAUrl(text)
            text = token
        }

        // Step 2 permits one recursive normalisation, not an unbounded wrapper chain.
        if (text.startsWith("neutrodyne://subscribe", ignoreCase = true)) {
            text = queryUrl(text) ?: return NormalizedInput.Invalid
            if (pass == NeutrodynePass.INITIAL) return normalize(text, NeutrodynePass.RECURSIVE)
        }

        // Steps 3–5 consume the preceding step's result without re-decoding query values.
        var schemeGuessed = false
        var url = unwrapSubscribePage(unwrapScheme(text)) ?: return NormalizedInput.Invalid
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            if (url.contains("://")) return NormalizedInput.Invalid
            // Scheme-less `host.tld/…` tries https first (03 step 5).
            if (!looksLikeHost(url)) return invalidOrNotAUrl(url)
            url = "https://$url"
            schemeGuessed = true
        }
        // `HTTPS://…` fetches fine only with a lowercase scheme (Ktor compares literally).
        url = url.substringBefore("://").lowercase() + url.substring(url.indexOf("://"))

        // Step 7: userinfo becomes credentials; the persisted URL never carries it.
        val (clean, credentials) = UrlNormalizer.splitUserInfo(url)
        return NormalizedInput.Url(clean, schemeGuessed, credentials)
    }

    /** Step 3 precedes subscribe-page extraction, so `feed:` may wrap an AntennaPod page. */
    private fun unwrapScheme(input: String): String {
        for (prefix in listOf("feed:", "pcast://", "podcast://", "itpc://")) {
            if (input.startsWith(prefix, ignoreCase = true)) {
                val suffix = input.substring(prefix.length)
                return if (prefix == "feed:") suffix.removePrefix("//") else suffix
            }
        }
        return input
    }

    /** Step 4 matches hostnames without changing the case-sensitive path or embedded URL. */
    private fun unwrapSubscribePage(input: String): String? {
        val schemeLength =
            when {
                input.startsWith("https://", ignoreCase = true) -> "https://".length
                input.startsWith("http://", ignoreCase = true) -> "http://".length
                else -> 0
            }
        val hostPath = input.substring(schemeLength)
        val host = hostPath.substringBefore('/')
        val path = hostPath.substring(host.length)
        return when {
            host.equals("antennapod.org", ignoreCase = true) && path.startsWith("/deeplink/subscribe") -> {
                queryUrl(input)
            }

            (
                host.equals("subscribeonandroid.com", ignoreCase = true) ||
                    host.equals("www.subscribeonandroid.com", ignoreCase = true)
            ) && path.startsWith('/') -> {
                input.substring(schemeLength + host.length + 1)
            }

            host.equals("podcasts.google.com", ignoreCase = true) && path.startsWith("/feed/") -> {
                decodeGoogleFeed(input.substring(schemeLength + host.length + "/feed/".length))
            }

            else -> {
                input
            }
        }
    }

    /** Only the first exact `url` query parameter counts; fragments never supply parameters. */
    private fun queryUrl(input: String): String? {
        val query = input.substringBefore('#').substringAfter('?', "")
        val encoded =
            query
                .split('&')
                .firstOrNull { it.substringBefore('=') == "url" }
                ?.substringAfter('=', "") ?: return null
        return encoded.takeUnless { it.isEmpty() }?.let(::percentDecode)
    }

    /** `host.tld` or `host.tld/…` (with optional port/userinfo); rejects scheme-less garbage. */
    private fun looksLikeHost(text: String): Boolean {
        val host = text.substringBefore('/').substringAfterLast('@').substringBefore(':')
        return host.trimEnd('.').contains('.') && host.none { it.isWhitespace() }
    }

    private fun invalidOrNotAUrl(text: String): NormalizedInput =
        if (text.contains("://")) NormalizedInput.Invalid else NormalizedInput.NotAUrl(text)

    /** `podcasts.google.com/feed/<base64url>` → the decoded feed URL (03 step 4, encoding unverified). */
    private fun decodeGoogleFeed(encoded: String): String? {
        val padded =
            encoded
                .substringBefore('?')
                .substringBefore('#')
                .let { s -> s.padEnd(s.length + (4 - s.length % 4) % 4, '=') }
        return runCatching { Base64.UrlSafe.decode(padded).decodeToString() }.getOrNull()
    }

    /** Decode one UTF-8 query component; `+` is a space and malformed percent escapes stay literal. */
    private fun percentDecode(text: String): String {
        val out = Buffer()
        var i = 0
        var literalStart = 0
        while (i < text.length) {
            val c = text[i]
            val high = text.getOrNull(i + 1)?.digitToIntOrNull(16)
            val low = text.getOrNull(i + 2)?.digitToIntOrNull(16)
            if (c == '%' && high != null && low != null) {
                out.writeUtf8(text, literalStart, i)
                out.writeByte((high shl 4) or low)
                i += 3
                literalStart = i
            } else if (c == '+') {
                out.writeUtf8(text, literalStart, i)
                out.writeByte(' '.code)
                i++
                literalStart = i
            } else {
                i++
            }
        }
        // Write literal runs together so a supplementary character is not split into surrogates.
        out.writeUtf8(text, literalStart, text.length)
        return out.readUtf8()
    }
}
