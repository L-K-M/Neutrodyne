// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

/**
 * The local-network destination classes the add pipeline must never reach on its own (03
 * implementation note 2026-11-02): a shared page or a discovered candidate could otherwise turn
 * the automatic fetches into LAN requests. Pure string classification — no JVM or Android APIs,
 * no DNS — so it behaves identically on every target.
 *
 * Known limit: a public hostname that *resolves* to a LAN address is not visible to this
 * predicate; only the Android 37+ `LocalNetworkGuard`'s DNS check covers that case.
 */
public object LocalDestination {
    private val schemeWithAuthority = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
    private val hexDigits = "0123456789abcdefABCDEF".toSet()

    private const val IPV4_PARTS = 4
    private const val IPV4_MAPPED_PREFIX_GROUPS = 5
    private const val IPV4_MAPPED_MARKER = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val ULA_MASK = 0xFE00
    private const val ULA_PREFIX = 0xFC00
    private const val LINK_LOCAL_MASK = 0xFFC0
    private const val LINK_LOCAL_PREFIX = 0xFE80

    /**
     * True when [url] is an absolute `scheme://…` URL whose host names a local destination:
     * `localhost`, `*.localhost` and `*.local`; the IPv4 literals of 0/8, 10/8, 100.64/10, 127/8,
     * 169.254/16, 172.16/12 and 192.168/16; the IPv6 literals `::`, `::1`, fc00::/7 and fe80::/10
     * including their `::ffff:` IPv4-mapped forms; and any numeric host that is not a plain
     * four-part decimal address (`2130706433`, `0x7f.0.0.1`, `127.1`), because resolvers read
     * such spellings as IP addresses whose value cannot be verified here. Public hostnames and
     * public dotted quads are not local, and neither is input without `scheme://`.
     */
    public fun isLocal(url: String): Boolean {
        if (schemeWithAuthority.find(url) == null) return false
        // Classify the host the transport will actually dial: percent-decoded, IDNA-mapped,
        // numeric spellings canonicalised (03 note 2026-11-02). `http://%31%32%37.0.0.1/` and
        // `http://１２７.０.０.１/` both come back as `127.0.0.1`.
        UrlNormalizer.transportHost(url)?.let { return isLocalHost(it) }
        // The normaliser only refuses spellings the transport rejects or whose numeric grammar
        // is ambiguous (0x… parts, >4 groups, bad ports); those still get the conservative
        // authority-host pass so an unreadable numeric spelling stays local.
        val raw = authorityHost(url) ?: return false
        val lowered = raw.removeSuffix(".").lowercase()
        if (lowered == "localhost" || lowered.endsWith(".localhost") || lowered.endsWith(".local")) {
            return true
        }
        if (lowered.startsWith("[")) {
            val literal = lowered.substringAfter('[').substringBefore(']').substringBefore('%')
            val groups = UrlNormalizer.parseIpv6Groups(literal) ?: return false
            return isLocalIpv6(groups)
        }
        if (!isNumericHost(lowered)) return false
        val parts = lowered.split('.')
        if (parts.size == IPV4_PARTS && parts.all(::isPlainOctet)) {
            return isLocalIpv4(parts.map(String::toInt))
        }
        // A numeric host that is not a plain dotted quad — resolvers may still read it as an
        // address (127.1 → 127.0.0.1, 0x7f.0.0.1, 2130706433), and the spelling cannot be trusted.
        return true
    }

    /**
     * The local-destination classification of a host already run through the transport's own
     * normalisation: lowercased, decoded, IDNA-mapped, numeric forms canonicalised to a dotted
     * quad; IPv6 literals keep their brackets.
     */
    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host.startsWith("[")) {
            val literal = host.substring(1, host.lastIndex).substringBefore('%')
            val groups = UrlNormalizer.parseIpv6Groups(literal) ?: return false
            return isLocalIpv6(groups)
        }
        val parts = host.split('.')
        if (parts.size == IPV4_PARTS && parts.all { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }) {
            return isLocalIpv4(parts.map(String::toInt))
        }
        return false
    }

    /**
     * The URL's authority host: userinfo stripped at the last `@`, the port at `:`, and the
     * authority itself at the first `/`, `?`, `#` or `\` (the fetch stack reads `\` as a path
     * separator). IPv6 literals keep their brackets; `null` when there is no `scheme://`.
     */
    private fun authorityHost(url: String): String? {
        val match = schemeWithAuthority.find(url) ?: return null
        val authority =
            url
                .substring(match.range.last + 1)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore('\\')
                .substringAfterLast('@')
        return when {
            authority.startsWith("[") -> authority.substringBefore(']') + "]"
            else -> authority.substringBefore(':').takeIf { it.isNotEmpty() }
        }
    }

    /** A numeric host: every dot-part is decimal digits or `0x`+hex, never a DNS name (WHATWG). */
    private fun isNumericHost(host: String): Boolean = host.split('.').all { it.isDecimalPart() || it.isHexPart() }

    private fun String.isDecimalPart(): Boolean = isNotEmpty() && all { it in '0'..'9' }

    private fun String.isHexPart(): Boolean = length > 2 && startsWith("0x") && drop(2).all { it in hexDigits }

    /** A plain decimal octet — no leading zero, whose spelling would invite an octal/hex read. */
    private fun isPlainOctet(part: String): Boolean =
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it in '0'..'9' } &&
            !(part.length > 1 && part[0] == '0') &&
            part.toInt() <= BYTE_MASK

    private fun isLocalIpv4(octets: List<Int>): Boolean =
        when {
            // 0/8, 10/8, 127/8
            octets[0] == 0 || octets[0] == 10 || octets[0] == 127 -> true

            // CGNAT 100.64/10
            octets[0] == 100 && octets[1] in 64..127 -> true

            // link-local 169.254/16
            octets[0] == 169 && octets[1] == 254 -> true

            // 172.16/12
            octets[0] == 172 && octets[1] in 16..31 -> true

            // 192.168/16
            octets[0] == 192 && octets[1] == 168 -> true

            else -> false
        }

    private fun isLocalIpv6(groups: IntArray): Boolean =
        when {
            // :: unspecified
            groups.all { it == 0 } -> {
                true
            }

            // ::1 loopback
            groups.dropLast(1).all { it == 0 } && groups.last() == 1 -> {
                true
            }

            // fc00::/7 unique-local
            groups[0] and ULA_MASK == ULA_PREFIX -> {
                true
            }

            // fe80::/10 link-local
            groups[0] and LINK_LOCAL_MASK == LINK_LOCAL_PREFIX -> {
                true
            }

            // ::ffff:a.b.c.d — the IPv4-mapped spelling gets the IPv4 ranges.
            groups.take(IPV4_MAPPED_PREFIX_GROUPS).all { it == 0 } && groups[5] == IPV4_MAPPED_MARKER -> {
                isLocalIpv4(
                    listOf(
                        groups[6] shr 8,
                        groups[6] and BYTE_MASK,
                        groups[7] shr 8,
                        groups[7] and BYTE_MASK,
                    ),
                )
            }

            else -> {
                false
            }
        }
}
