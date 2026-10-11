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
    private const val UTF16_PAIR_UNITS = 2

    /** RFC 3986 pchar + `/` — every other path char is percent-encoded (UTF-8) for identity. */
    private const val PATH_SAFE = "!$&'()*+,;=:@/"

    /** RFC 3986 query = pchar + `/` + `?`. */
    private const val QUERY_SAFE = "!$&'()*+,;=:@/?"

    private const val HEX_UPPER = "0123456789ABCDEF"

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
        // A bare `@` carries no credential text: strip it like a real userinfo, but surface no
        // credentials so an empty pair is never stored against the origin.
        if (userInfo.isEmpty()) return cleaned to null
        val username = percentDecode(userInfo.substringBefore(':', userInfo))
        val password = percentDecode(if (userInfo.contains(':')) userInfo.substringAfter(':') else "")
        // `:@` and every spelling that decodes to empty/empty is the same non-credential.
        if (username.isEmpty() && password.isEmpty()) return cleaned to null
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
                // Same unsafe-char encoding as the path, with the wider RFC query allowlist
                // (`/` and `?` too): `?a b` and `?a%20b` fetch identically, so they key alike.
                "?" + normalisePercentEncoding(percentEncodeUnsafe(parts.query, QUERY_SAFE))
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
        if (hasNumericTail(decoded)) {
            // A numeric last label commits the host to IPv4 (WHATWG): "127.1", "0x7f.1",
            // "2130706433" and "0177.0.0.1" all resolve to 127.0.0.1 on the fetch client, so
            // identity must share that canonical form — and a numeric tail that fails the
            // IPv4 grammar is an invalid host, never a domain name.
            return parseIpv4NumberHost(decoded)
        }
        val ascii = decoded.lowercase().idnaToAsciiOrNull() ?: return null
        // toASCII runs without UseSTD3ASCIIRules, so characters like '/' and '$' pass through
        // and "." strips to empty: either would collide or forge identities. LDH, dots and
        // '_' are the only characters a valid post-IDNA host can contain.
        return ascii.removeSuffix(".").takeIf {
            it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' }
        }
    }

    /**
     * Full RFC 3986 `IP-literal` validation plus RFC 5952 canonicalisation — a character allowlist
     * would let `:::`, empty literals and `v`-futures through unfetchable. The RFC 6874 zone id is
     * kept verbatim (case included) behind a canonical `%25` marker; the address is emitted
     * lowercase with the longest leftmost zero run of ≥2 groups as `::` and an embedded IPv4 tail
     * as two hex groups — a deliberate divergence from the dotted RFC 5952 §5 / WHATWG form, so
     * these hosts are not byte-identical to browser/OkHttp canonical forms. Pure string work:
     * no DNS or interface lookup, identical on every target.
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
            // Every `%` must head a two-hex-digit escape (RFC 6874): a dangling or short
            // escape would emit an identity no strict URI parser accepts.
            var zi = 0
            while (zi < zone.length) {
                if (zone[zi] == '%' &&
                    (zi + 2 >= zone.length || zone[zi + 1] !in hexDigits || zone[zi + 2] !in hexDigits)
                ) {
                    return null
                }
                zi++
            }
        }
        val groups = parseIpv6Groups(address) ?: return null
        return buildString {
            append('[')
            append(formatIpv6(groups))
            if (zone != null) append("%25").append(zone)
            append(']')
        }
    }

    /**
     * WHATWG's ends-in-a-number test: the last non-empty label is all digits or `0x`-prefixed.
     * Such a host is an IPv4 address, not a DNS name — see [parseIpv4NumberHost].
     */
    private fun hasNumericTail(host: String): Boolean {
        val tail = host.substringAfterLast('.')
        if (tail.isEmpty()) {
            // Trailing dot: the label before it decides.
            val head = host.substringBeforeLast('.')
            return head.isNotEmpty() && hasNumericTail(head)
        }
        return tail.all { it in '0'..'9' } || (tail.length >= 2 && tail.startsWith("0x", true))
    }

    /**
     * Parses a WHATWG IPv4 number host — 1–4 dot-separated decimal, `0x` hex or leading-`0`
     * octal parts, only the last allowed to exceed 255 — and emits canonical dotted-quad.
     * Unlike the strict [parseIpv4Tail] used inside IPv6 literals, octal/hex spellings are
     * legal here. Null when the grammar or a range bound fails.
     */
    private fun parseIpv4NumberHost(host: String): String? {
        var labels = host.split('.')
        if (labels.size > 1 && labels.last().isEmpty()) labels = labels.dropLast(1)
        if (labels.isEmpty() || labels.size > 4 || labels.any { it.isEmpty() }) return null
        val numbers = labels.map { ipv4Number(it) ?: return null }
        for (i in 0 until numbers.size - 1) if (numbers[i] > 0xFF) return null
        // The last part fills all remaining octets.
        if (numbers.last() >= (1L shl (8 * (5 - numbers.size)))) return null
        var value = numbers.last()
        for (i in 0 until numbers.size - 1) value += numbers[i] shl (8 * (3 - i))
        return buildString {
            for (i in 0..3) {
                if (i > 0) append('.')
                append((value shr (8 * (3 - i))) and 0xFF)
            }
        }
    }

    /** One IPv4 part: `0x` hex, leading-`0` octal, else decimal; empty or bad digits → null. */
    private fun ipv4Number(part: String): Long? {
        val (radix, digits) =
            when {
                part.length > 2 && part.startsWith("0x", true) -> 16 to part.substring(2)
                part.length > 1 && part.startsWith('0') -> 8 to part.substring(1)
                else -> 10 to part
            }
        // toLongOrNull accepts a leading sign; WHATWG's grammar does not.
        if (digits.isEmpty() || digits.any { it.digitToIntOrNull(radix) == null }) return null
        return digits.toLongOrNull(radix)
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
        // Port 0 is a reserved, unconnectable port: same "unfetchable → no identity" rule.
        return value in 1..MAX_PORT
    }

    /**
     * The port to keep: null when absent or default. Identity is scheme-free, so both well-known
     * ports drop regardless of scheme (03 URL normalisation); [origin] keeps the scheme's own
     * default. Only http(s) reach the scheme-free path: [identity] rejects other schemes first.
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
        // A raw space is what the fetcher percent-encodes on the wire — encode it the same way so
        // the space and `%20` spellings of one resource share an identity. The same applies to
        // every other char outside the path allowlist (non-ASCII, `"`, `<`, `>`, `|`, …).
        var path = if (rawPath.isEmpty()) "/" else percentEncodeUnsafe(rawPath.replace('\\', '/'), PATH_SAFE)
        path = normalisePercentEncoding(path)
        path = removeDotSegments(path)
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        return path
    }

    /**
     * UTF-8 `%XX`-encodes every char outside [allowed] — unreserved chars and `%` never encode,
     * so an existing escape passes through for [normalisePercentEncoding] to canonicalize and a
     * raw char and its pre-encoded spelling collapse to the same identity. UTF-16 surrogate pairs
     * encode as one UTF-8 sequence, never as two `?` replacements.
     */
    private fun percentEncodeUnsafe(
        text: String,
        allowed: String,
    ): String {
        if (text.none { it !in unreservedChars && it !in allowed && it != '%' }) return text
        val out = StringBuilder(text.length + UTF16_PAIR_UNITS)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in unreservedChars || c in allowed || c == '%') {
                out.append(c)
                i++
                continue
            }
            val pair = c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() == true
            val end = if (pair) i + UTF16_PAIR_UNITS else i + 1
            for (byte in text.substring(i, end).encodeToByteArray()) {
                val b = byte.toInt() and 0xFF
                out.append('%').append(HEX_UPPER[b shr 4]).append(HEX_UPPER[b and 0xF])
            }
            i = end
        }
        return out.toString()
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

    /**
     * Decodes every valid `%XX` escape; invalid escapes stay literal. `+` is NOT a space here.
     * A valid escape of invalid UTF-8 (e.g. `%FF`) also stays literal rather than silently
     * mangling the stored credential to U+FFFD.
     */
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
                // Encoding each surrogate half alone replaces raw Unicode in mixed credentials.
                val next =
                    if (c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() == true) {
                        i + UTF16_PAIR_UNITS
                    } else {
                        i + 1
                    }
                for (b in text.substring(i, next).encodeToByteArray()) bytes.add(b)
                i = next
            }
        }
        val decoded = bytes.toByteArray()
        return runCatching { decoded.decodeToString(throwOnInvalidSequence = true) }.getOrDefault(text)
    }
}
