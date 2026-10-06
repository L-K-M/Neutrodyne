// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Parses IP address literals without ever hitting DNS (unknown names yield `null`, and callers
 * treat an unparseable peer as untrusted). Ktor reports peers as literals; `localhost` is the one
 * name handled separately.
 */
internal object IpLiterals {
    fun parse(text: String): InetAddress? {
        val value = text.trim().removePrefix("[").removeSuffix("]")
        if (value.isEmpty()) return null
        val isIpv4 = value.count { it == '.' } == 3 && value.all { it.isDigit() || it == '.' }
        val isIpv6 = value.contains(':') && value.all { it.isHexDigit() || it == ':' || it == '.' }
        if (!isIpv4 && !isIpv6) return null
        return try {
            InetAddress.getByName(value)
        } catch (_: Exception) {
            null
        }
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Whether a host string names this machine's loopback interface: the `localhost` name, a
 * `127.0.0.0/8` address or IPv6 `::1` (10 TLS stance: loopback is always allowed).
 */
internal fun isLoopbackHost(host: String): Boolean {
    if (host.equals(LOCALHOST, ignoreCase = true)) return true
    return IpLiterals.parse(host)?.isLoopbackAddress ?: false
}

private const val LOCALHOST = "localhost"

/**
 * One CIDR block of `NEUTRODYNE_SERVER_TRUSTED_PROXIES`; a bare address is a full-length block
 * (`127.0.0.1` means `127.0.0.1/32`). Only same-family addresses can match.
 */
internal class IpCidr private constructor(
    private val network: ByteArray,
    private val prefixBits: Int,
    private val ipv6: Boolean,
) {
    fun matches(address: InetAddress): Boolean {
        if (ipv6 != (address is Inet6Address)) return false
        val bytes = address.address
        if (bytes.size != network.size) return false
        // Compare whole bytes while the prefix lasts, then the top bits of the straddling byte.
        for (index in bytes.indices) {
            val bitsBefore = index * BITS_PER_BYTE
            if (bitsBefore + BITS_PER_BYTE <= prefixBits) {
                if (bytes[index] != network[index]) return false
            } else if (bitsBefore < prefixBits) {
                val significantBits = prefixBits - bitsBefore
                val mask = MASK_ALL_BITS shl (BITS_PER_BYTE - significantBits) and MASK_ALL_BITS
                if (bytes[index].toInt() and mask != network[index].toInt() and mask) return false
            } else {
                return true
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean =
        other is IpCidr && other.ipv6 == ipv6 && other.prefixBits == prefixBits && other.network.contentEquals(network)

    override fun hashCode(): Int = 31 * prefixBits + network.contentHashCode() + if (ipv6) 1 else 0

    override fun toString(): String {
        val address = InetAddress.getByAddress(network).hostAddress
        return "$address/$prefixBits"
    }

    internal companion object {
        private const val BITS_PER_BYTE = 8
        private const val MASK_ALL_BITS = 0xFF

        fun parse(text: String): IpCidr? {
            val trimmed = text.trim()
            val (addressText, prefixText) =
                if (trimmed.contains('/')) {
                    val parts = trimmed.split('/', limit = 2)
                    parts[0] to parts[1]
                } else {
                    trimmed to null
                }
            val address = IpLiterals.parse(addressText) ?: return null
            val addressBytes = address.address
            val maxBits = addressBytes.size * BITS_PER_BYTE
            val bits = prefixText?.trim()?.toIntOrNull() ?: maxBits
            if (bits < 0 || bits > maxBits) return null

            val network = addressBytes.copyOf()
            // Clear every bit beyond the prefix so `matches` is a plain byte comparison.
            var remaining = bits
            for (index in network.indices) {
                if (remaining >= BITS_PER_BYTE) {
                    remaining -= BITS_PER_BYTE
                } else if (remaining == 0) {
                    network[index] = 0
                } else {
                    val mask = MASK_ALL_BITS shl (BITS_PER_BYTE - remaining) and MASK_ALL_BITS
                    network[index] = (network[index].toInt() and mask).toByte()
                    remaining = 0
                }
            }
            return IpCidr(network, bits, address is Inet6Address)
        }

        fun parseAll(text: String): List<IpCidr> = text.split(',').mapNotNull { entry -> parse(entry) }
    }
}
