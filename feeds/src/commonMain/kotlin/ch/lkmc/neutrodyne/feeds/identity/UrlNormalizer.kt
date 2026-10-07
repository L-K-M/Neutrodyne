// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import ch.lkmc.neutrodyne.feeds.text.idnaToAsciiOrNull

/**
 * Scheme-free URL identity, shared by the apps and the sync server (03 URL normalisation). Because feed
 * keys are compared across devices and by the server, the output must be identical everywhere:
 * `UrlNormalizerTest` runs in `commonTest` with fixed vectors and a `desktopTest` cross-check compares
 * the splitter with the JDK's `URI` class.
 */
public object UrlNormalizer {
    /** A change to `forIdentity` output is an identity-key version change (02). */
    public const val VERSION: Int = 1

    private val unreservedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
    private val hexDigits = "0123456789abcdefABCDEF".toSet()

    /**
     * The identity form of an HTTP(S) URL, or null when the URL is not `http(s)` or has no valid host:
     * scheme dropped (so `http` and `https` compare equal), host lowercased with IDN → ASCII and the
     * trailing dot removed, ports 80/443 dropped, empty path → `/`, percent-encoding normalised
     * (unreserved decoded, other escapes uppercased), dot segments removed, one trailing `/` removed
     * unless the path is `/`, query kept verbatim (empty `?` dropped), userinfo and fragment dropped.
     * Example: `HTTPS://Feeds.Example.com:443/Show/?a=1#x` → `feeds.example.com/Show?a=1`.
     */
    public fun forIdentity(url: String): String? = identity(url, keepQuery = true)

    /** The weak enclosure-match form: [forIdentity] without the query (03 Diff algorithm step 1). */
    public fun forIdentityNoQuery(url: String): String? = identity(url, keepQuery = false)

    /** `scheme://host[:port]` with the default port omitted, the `credential.origin` form; null if not http(s). */
    public fun origin(url: String): String? {
        val parts = splitLenient(url)
        if (parts.scheme != "http" && parts.scheme != "https") return null
        if (!portIsValid(parts)) return null
        val host = normaliseHost(parts) ?: return null
        val port = normalisePort(parts, schemeFree = false)
        val suffix = if (port == null) "" else ":$port"
        return "${parts.scheme}://$host$suffix"
    }

    /**
     * Splits `user:pass@` out of [url]: the URL without userinfo (everything else verbatim) and the
     * percent-decoded credentials, or null userinfo when the URL has none. Used by the add flow (03 Basic
     * auth and CredentialStore: input). Absolute URLs only; anything else is returned unchanged.
     */
    public fun splitUserInfo(url: String): Pair<String, UrlUserInfo?> {
        val trimmed = url.trim()
        val schemeMatch = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""").find(trimmed) ?: return trimmed to null
        val afterScheme = trimmed.substring(schemeMatch.range.last + 1)
        // The authority ends at the first `/`, `?` or `#`: text in the query or fragment is never
        // credentials, so `https://host#x@evil` carries no userinfo and its host never moves.
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val atIndex = authority.lastIndexOf('@')
        if (atIndex < 0) return trimmed to null

        val cleaned = trimmed.substring(0, schemeMatch.range.last + 1) + afterScheme.substring(atIndex + 1)
        val userInfo = authority.substring(0, atIndex)
        val username = percentDecode(userInfo.substringBefore(':', userInfo))
        val password = percentDecode(if (userInfo.contains(':')) userInfo.substringAfter(':') else "")
        return cleaned to UrlUserInfo(username, password)
    }

    private fun identity(
        url: String,
        keepQuery: Boolean,
    ): String? {
        val parts = splitLenient(url)
        if (parts.scheme != "http" && parts.scheme != "https") return null
        if (!portIsValid(parts)) return null
        val host = normaliseHost(parts) ?: return null
        val port = normalisePort(parts, schemeFree = true)
        val path = normalisePath(parts.path)
        val portSuffix = if (port == null) "" else ":$port"
        val querySuffix =
            if (keepQuery && parts.query != null) {
                "?${parts.query}"
            } else {
                ""
            }
        return "$host$portSuffix$path$querySuffix"
    }

    /** Percent-decoded, IDNA-converted, lowercased, trailing-dot-free host; IPv6 hosts keep their brackets. */
    private fun normaliseHost(parts: UrlParts): String? {
        val raw = parts.host?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.startsWith("[")) {
            return raw.takeIf { it.endsWith("]") }?.lowercase()
        }

        val decoded = percentDecode(raw)
        if (decoded.isEmpty()) return null
        val ascii = decoded.lowercase().idnaToAsciiOrNull() ?: return null
        return ascii.removeSuffix(".")
    }

    /** A non-numeric port makes the whole URL invalid (lenient about everything else). */
    private fun portIsValid(parts: UrlParts): Boolean =
        parts.port == null || (parts.port.isNotEmpty() && parts.port.all { it in '0'..'9' })

    /**
     * The port to keep: null when absent or default. Identity is scheme-free, so both well-known
     * ports drop regardless of scheme (03 URL normalisation); [origin] keeps the scheme's own default.
     */
    private fun normalisePort(
        parts: UrlParts,
        schemeFree: Boolean,
    ): String? {
        val raw = parts.port ?: return null
        val port = raw.trimStart('0').ifEmpty { "0" }
        return if (isDefaultPort(parts.scheme, port, schemeFree)) null else port
    }

    /** Empty path → `/`; percent-encoding normalised; dot segments removed; one trailing `/` removed. */
    private fun normalisePath(rawPath: String): String {
        var path = if (rawPath.isEmpty()) "/" else rawPath
        path = normalisePercentEncoding(path)
        path = removeDotSegments(path)
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        return path
    }

    /** RFC 3986 §5.2.4. */
    private fun removeDotSegments(path: String): String {
        val output = StringBuilder()
        var input = path

        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> {
                    input = input.substring(3)
                }

                input.startsWith("./") -> {
                    input = input.substring(2)
                }

                input.startsWith("/./") -> {
                    input = "/" + input.substring(3)
                }

                input == "/." -> {
                    input = "/"
                }

                input.startsWith("/../") -> {
                    input = "/" + input.substring(4)
                    output.setLength(removeLastSegment(output))
                }

                input == "/.." -> {
                    input = "/"
                    output.setLength(removeLastSegment(output))
                }

                input == "." || input == ".." -> {
                    input = ""
                }

                else -> {
                    // Move the next segment (with its leading slash) to the output.
                    val nextSlash = input.indexOf('/', 1)
                    if (nextSlash < 0) {
                        output.append(input)
                        input = ""
                    } else {
                        output.append(input, 0, nextSlash)
                        input = input.substring(nextSlash)
                    }
                }
            }
        }
        return output.toString()
    }

    private fun removeLastSegment(output: StringBuilder): Int {
        val lastSlash = output.lastIndexOf('/')
        return if (lastSlash < 0) 0 else lastSlash
    }

    /**
     * Unreserved escapes decoded, every other escape uppercased; a bare `%` not followed by two hex
     * digits stays literal (the splitter is lenient where the JDK's `URI` would throw).
     */
    private fun normalisePercentEncoding(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }

            if (i + 2 < text.length && text[i + 1] in hexDigits && text[i + 2] in hexDigits) {
                val decoded = percentDecode(text.substring(i, i + 3))
                val single = decoded.singleOrNull()
                if (single != null && single in unreservedChars) {
                    out.append(single)
                } else {
                    out.append(text.substring(i, i + 3).uppercase())
                }
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Decodes every valid `%XX` escape; invalid escapes stay literal. `+` is NOT a space here. */
    private fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val high = text.getOrNull(i + 1)?.digitToIntOrNull(16)
            val low = text.getOrNull(i + 2)?.digitToIntOrNull(16)
            if (c == '%' && high != null && low != null) {
                bytes.add(((high shl 4) or low).toByte())
                i += 3
            } else {
                for (b in c.toString().encodeToByteArray()) bytes.add(b)
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
