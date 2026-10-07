// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.add

import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.identity.UrlUserInfo
import kotlin.io.encoding.Base64

/** What [AddInputNormalizer] produced (03 Input normalisation). */
internal sealed interface NormalizedInput {
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

/**
 * The add-input normaliser of 03 Input normalisation. Deviation (2026-10-07): the design places
 * it in `:feeds/discovery`; it lives here for M1a because the `:feeds` half was fixed by the
 * parser package. It moves to `:feeds` with the M7 discovery work.
 */
internal object AddInputNormalizer {
    /** 03 step 1: share-intent text is trimmed and capped. */
    private const val MAX_INPUT_CHARS = 4_096

    private val urlToken = Regex("""^(?:(?:https?|feed|pcast|podcast|itpc|neutrodyne):|[^\s/@]+\.[^\s@]+)""")

    fun normalize(raw: String): NormalizedInput {
        var text = raw.trim().take(MAX_INPUT_CHARS)

        // Step 1: share intents ("Listen to X https://…") — take the first URL-looking token.
        if (text.any { it.isWhitespace() }) {
            val token =
                text.split(Regex("""\s+""")).firstOrNull { candidate ->
                    !candidate.startsWith("@") && urlToken.containsMatchIn(candidate)
                } ?: return NormalizedInput.NotAUrl(text)
            text = token
        }

        // Steps 2–5 unwrap wrappers and scheme variants; each returns the fetch URL it produced.
        var schemeGuessed = false
        var url = unwrap(text)
        if (url == null) return invalidOrNotAUrl(text)
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

    /** The wrapper/scheme chain of 03 steps 2–4; null when no rule applies. */
    private fun unwrap(input: String): String? {
        val lower = input.lowercase()
        when {
            lower.startsWith("neutrodyne://subscribe") -> {
                val enc = input.substringAfter("url=", "")
                if (enc.isEmpty()) return null
                return percentDecode(enc.substringBefore('&'))
            }

            lower.startsWith("feed:") -> {
                // `feed://host/…` unwraps like `feed:http(s)://…` (03 step 3): an optional `//`.
                return input.substring("feed:".length).removePrefix("//")
            }

            lower.startsWith("pcast://") -> {
                return input.substring("pcast://".length)
            }

            lower.startsWith("podcast://") -> {
                return input.substring("podcast://".length)
            }

            lower.startsWith("itpc://") -> {
                return input.substring("itpc://".length)
            }
        }

        // Subscribe-page wrappers (03 step 4).
        val hostPath = lower.removePrefix("https://").removePrefix("http://")
        when {
            hostPath.startsWith("antennapod.org/deeplink/subscribe") -> {
                val enc = input.substringAfter("url=", "")
                if (enc.isEmpty()) return null
                return percentDecode(enc.substringBefore('&'))
            }

            hostPath.startsWith("subscribeonandroid.com/") -> {
                return input.substringAfter("subscribeonandroid.com/")
            }

            hostPath.startsWith("www.subscribeonandroid.com/") -> {
                return input.substringAfter("www.subscribeonandroid.com/")
            }

            hostPath.startsWith("podcasts.google.com/feed/") -> {
                return decodeGoogleFeed(input.substringAfter("/feed/"))
            }
        }
        return input
    }

    /** `host.tld` or `host.tld/…` (with optional port/userinfo); rejects scheme-less garbage. */
    private fun looksLikeHost(text: String): Boolean {
        val host = text.substringBefore('/').substringAfterLast('@').substringBefore(':')
        return host.contains('.') && host.none { it.isWhitespace() }
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

    /** Query-parameter percent decoding (`+` is a space, unlike path decoding). */
    private fun percentDecode(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val high = text.getOrNull(i + 1)?.digitToIntOrNull(16)
            val low = text.getOrNull(i + 2)?.digitToIntOrNull(16)
            if (c == '%' && high != null && low != null) {
                out.append(((high shl 4) or low).toChar())
                i += 3
            } else if (c == '+') {
                out.append(' ')
                i++
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
