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
    private val schemeWithAuthority = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
    private const val MAX_PORT = 65_535L
    private const val IPV6_GROUPS = 8

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
        val schemeMatch = schemeWithAuthority.find(trimmed) ?: return trimmed to null
        val afterScheme = trimmed.substring(schemeMatch.range.last + 1)
        // The authority ends at the first `/`, `?` or `#`: text in the query or fragment is never
        // credentials, so `https://host#x@evil` carries no userinfo and its host never moves.
        // For http(s), `\` ends it too — the fetch client (OkHttp) treats it as a path separator.
        var authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (schemeMatch.value.dropLast(3).lowercase() in setOf("http", "https")) {
            authority = authority.substringBefore('\\')
        }
        val atIndex = authority.lastIndexOf('@')
        if (atIndex < 0) return trimmed to null
        // Credentials need a host to belong to: `https://user:pass@/feed` or `…@:8080` must not
        // surface a `UrlUserInfo` that would be stored against an origin that does not exist.
        if (authority.substring(atIndex + 1).substringBefore(':').isEmpty()) return trimmed to null

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
            return normaliseBracketedHost(raw)
        }

        val decoded = percentDecode(raw)
        if (decoded.isEmpty()) return null
        val ascii = decoded.lowercase().idnaToAsciiOrNull() ?: return null
        return ascii.removeSuffix(".")
    }

    /**
     * Full RFC 3986 `IP-literal` validation plus RFC 5952 canonicalisation — a character allowlist
     * would let `:::`, empty literals and `v`-futures through unfetchable. The RFC 6874 zone id is
     * kept verbatim (case included) behind a canonical `%25` marker; the address is emitted
     * lowercase with the longest leftmost zero run of ≥2 groups as `::` and an embedded IPv4 tail
     * as two hex groups, like the URL spec's IPv6 serialiser. Pure string work: no DNS or
     * interface lookup, identical on every target.
     */
    private fun normaliseBracketedHost(raw: String): String? {
        if (!raw.endsWith("]")) return null
        val literal = raw.substring(1, raw.lastIndex)
        val pct = literal.indexOf('%')
        val address = if (pct < 0) literal else literal.substring(0, pct)
        var zone: String? = null
        if (pct >= 0) {
            val after = literal.substring(pct + 1)
            zone = if (after.startsWith("25")) after.substring(2) else after
            if (zone.isEmpty() || zone.any { it !in unreservedChars && it != '%' }) return null
        }
        val groups = parseIpv6Groups(address) ?: return null
        return buildString {
            append('[')
            append(formatIpv6(groups))
            if (zone != null) append("%25").append(zone)
            append(']')
        }
    }

    /** Parses an IPv6 address into its eight 16-bit groups, or null when the grammar is violated. */
    private fun parseIpv6Groups(address: String): IntArray? {
        val dc = address.indexOf("::")
        if (dc >= 0 && address.indexOf("::", dc + 2) >= 0) return null // `::` may appear once
        val headText = if (dc < 0) address else address.substring(0, dc)
        val tailText = if (dc < 0) null else address.substring(dc + 2)

        // An embedded IPv4 is only legal as the last group, so only the tail side (or the whole
        // address when no `::` exists) may end in one.
        val head = parseIpv6Side(headText, allowIpv4 = tailText == null) ?: return null
        val tail = if (tailText == null) IntArray(0) else parseIpv6Side(tailText, allowIpv4 = true) ?: return null
        if (tailText == null) {
            if (head.size != IPV6_GROUPS) return null
        } else if (head.size + tail.size >= IPV6_GROUPS) {
            return null // `::` must compress at least one group
        }
        val groups = IntArray(IPV6_GROUPS)
        head.copyInto(groups)
        tail.copyInto(groups, IPV6_GROUPS - tail.size)
        return groups
    }

    /** The colon-separated groups of one side of a `::`; [allowIpv4] permits a dotted tail group. */
    private fun parseIpv6Side(
        part: String,
        allowIpv4: Boolean,
    ): IntArray? {
        if (part.isEmpty()) return IntArray(0)
        val segs = part.split(':')
        val out = IntArray(segs.size + 1)
        var n = 0
        for (i in segs.indices) {
            val group = segs[i]
            if (group.isEmpty()) return null
            if (group.indexOf('.') >= 0) {
                if (!allowIpv4 || i != segs.lastIndex) return null
                val v4 = parseIpv4Tail(group) ?: return null
                out[n++] = v4 ushr 16
                out[n++] = v4 and 0xffff
            } else {
                if (group.length > 4 || group.any { it !in hexDigits }) return null
                out[n++] = group.toInt(16)
            }
        }
        return out.copyOf(n)
    }

    /** A strict RFC 3986 `dec-octet` quad (no leading zeros); returns the 32-bit value or null. */
    private fun parseIpv4Tail(text: String): Int? {
        val octets = text.split('.')
        if (octets.size != 4) return null
        var value = 0
        for (octet in octets) {
            if (octet.isEmpty() || octet.length > 3 || octet.any { it !in '0'..'9' }) return null
            if (octet.length > 1 && octet[0] == '0') return null
            val v = octet.toInt()
            if (v > 255) return null
            value = value shl 8 or v
        }
        return value
    }

    /** RFC 5952 form: lowercase, no leading zeros, longest leftmost zero run of ≥2 as `::`. */
    private fun formatIpv6(groups: IntArray): String {
        var bestStart = -1
        var bestLen = 1 // only runs of ≥2 qualify
        var i = 0
        while (i < groups.size) {
            if (groups[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < groups.size && groups[j] == 0) j++
            if (j - i > bestLen) { // strict: the leftmost run wins a tie
                bestStart = i
                bestLen = j - i
            }
            i = j
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val head = groups.copyOfRange(0, bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.copyOfRange(bestStart + bestLen, groups.size).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    /**
     * A non-numeric or out-of-range port makes the whole URL invalid (lenient about everything
     * else): it can never be fetched, so it gets no identity.
     */
    private fun portIsValid(parts: UrlParts): Boolean {
        val raw = parts.port ?: return true
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return false
        val value = raw.toLongOrNull() ?: return false
        return value <= MAX_PORT
    }

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

    /**
     * Empty path → `/`; `\` → `/` like the fetch client ([identity] only ever sees http(s));
     * percent-encoding normalised; dot segments removed; one trailing `/` removed.
     */
    private fun normalisePath(rawPath: String): String {
        var path = if (rawPath.isEmpty()) "/" else rawPath.replace('\\', '/')
        path = normalisePercentEncoding(path)
        path = removeDotSegments(path)
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        return path
    }

    /**
     * RFC 3986 §5.2.4 as an indexed scan: the position moves, the input is never re-sliced, so
     * `"../" * N` costs linear time instead of quadratic substring copies.
     */
    private fun removeDotSegments(path: String): String {
        val output = StringBuilder(path.length)
        var i = 0
        val end = path.length

        while (i < end) {
            when {
                path.startsWith("../", i) -> {
                    i += 3
                }

                path.startsWith("./", i) -> {
                    i += 2
                }

                // `/./` collapses to `/`: skip the `/.` and keep scanning at the second slash.
                path.startsWith("/./", i) -> {
                    i += 2
                }

                i + 2 == end && path.startsWith("/.", i) -> {
                    output.append('/')
                    i = end
                }

                path.startsWith("/../", i) -> {
                    i += 3
                    output.setLength(removeLastSegment(output))
                }

                i + 3 == end && path.startsWith("/..", i) -> {
                    output.setLength(removeLastSegment(output))
                    output.append('/')
                    i = end
                }

                (i + 1 == end && path[i] == '.') || (i + 2 == end && path.startsWith("..", i)) -> {
                    i = end
                }

                else -> {
                    // Move the next segment (with its leading slash) to the output.
                    val nextSlash = path.indexOf('/', i + 1)
                    if (nextSlash < 0) {
                        output.append(path, i, end)
                        i = end
                    } else {
                        output.append(path, i, nextSlash)
                        i = nextSlash
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
