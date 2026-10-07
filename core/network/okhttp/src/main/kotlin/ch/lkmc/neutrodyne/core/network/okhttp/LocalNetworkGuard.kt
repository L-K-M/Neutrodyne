// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Thrown by the LAN guard for a request or lookup that can only reach a local-network host on
 * Android API 37+ (01 Interceptors). An `UnknownHostException` so callers treat it like DNS
 * failure; `JvmNetErrors` maps it to `NetError.LocalNetworkUnsupported`.
 */
class LocalNetworkUnsupportedException(
    host: String,
) : UnknownHostException(host)

/**
 * Fails fast on LAN hosts on Android API 37+, where the OS would otherwise let connections time
 * out (01 Interceptors). Two mechanisms, both consulted per call:
 *
 * - [dns] wraps the resolver chain and throws when **every** resolved address is local while the
 *   host is not loopback — a mixed public/private answer passes.
 * - [interceptor] is the first application interceptor and throws for IP-literal LAN hosts and
 *   `.local` names, which OkHttp never resolves through `Dns`.
 *
 * [Mode.SYNC] consults `LocalNetworkAccess.syncAllowed`: while 10's `LocalNetworkPermissionGate`
 * has granted access (below API 37 the gate sets it unconditionally), SYNC may reach LAN hosts;
 * every other client keeps failing fast. Below API 37 and on the desktop both elements are
 * pass-throughs — Android has no LAN rule there, and the desktop has none at all.
 */
@SingleIn(AppScope::class)
@Inject
class LocalNetworkGuard(
    private val access: LocalNetworkAccess,
    private val platform: PlatformInfo,
) {
    /** Which guard behaviour a client gets: [STRICT] for everything, [SYNC] for the sync client. */
    enum class Mode { STRICT, SYNC }

    /** The LAN guard engages only on Android API 37+ (`ACCESS_LOCAL_NETWORK`'s platform). */
    val active: Boolean
        get() =
            platform.kind == PlatformKind.ANDROID &&
                (platform.androidSdkInt ?: 0) >= LOCAL_NETWORK_PERMISSION_SDK

    /** The outermost `Dns`: LAN guard first, [inner] (04's `FamilyHintDns`) next. */
    fun dns(
        inner: Dns,
        mode: Mode,
    ): Dns = if (active) LocalNetworkGuardDns(inner, mode, access) else inner

    /** The first application interceptor of every client. */
    fun interceptor(mode: Mode): Interceptor = if (active) LocalNetworkGuardInterceptor(mode, access) else PASS_THROUGH

    internal companion object {
        /** `Build.VERSION_CODES`-style level at which Android gates LAN traffic (targetSdk 37). */
        const val LOCAL_NETWORK_PERMISSION_SDK = 37

        val PASS_THROUGH = Interceptor { chain -> chain.proceed(chain.request()) }
    }
}

/**
 * Wraps the resolver chain (01 Interceptors): throws when every resolved address is a LAN address
 * and the host is not loopback; a mixed public/private answer passes, as does any host whose
 * addresses are all loopback.
 */
internal class LocalNetworkGuardDns(
    private val delegate: Dns,
    private val mode: LocalNetworkGuard.Mode,
    private val access: LocalNetworkAccess,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (bypassed() || addresses.isEmpty() || addresses.all { it.isLoopbackAddress }) return addresses
        if (addresses.all(::isLanAddress)) throw LocalNetworkUnsupportedException(hostname)
        return addresses
    }

    private fun bypassed() = mode == LocalNetworkGuard.Mode.SYNC && access.syncAllowed.value
}

/**
 * Catches LAN requests OkHttp's `Dns` never sees — IP-literal hosts (`RouteSelector` returns the
 * parsed address directly) and `.local` mDNS names (01 Interceptors). Known gap, per the doc: a
 * redirect hop to an IP-literal LAN host is not seen by application interceptors.
 */
internal class LocalNetworkGuardInterceptor(
    private val mode: LocalNetworkGuard.Mode,
    private val access: LocalNetworkAccess,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (bypassed()) return chain.proceed(chain.request())
        val host = chain.request().url.host
        if (host.endsWith(".local", ignoreCase = true)) throw LocalNetworkUnsupportedException(host)
        val literal = parseIpLiteral(host)
        if (literal != null && !literal.isLoopbackAddress && isLanAddress(literal)) {
            throw LocalNetworkUnsupportedException(host)
        }
        return chain.proceed(chain.request())
    }

    private fun bypassed() = mode == LocalNetworkGuard.Mode.SYNC && access.syncAllowed.value
}

/**
 * The guard's static local ranges (01 Interceptors): IPv4 `10/8`, `172.16/12`, `192.168/16`,
 * `169.254/16`; IPv6 `fc00::/7` (ULA), `fe80::/10` (link-local), plus IPv4-mapped IPv6. A
 * conservative approximation of Android's broader definition — a miss ends as a connect timeout.
 */
internal fun isLanAddress(address: InetAddress): Boolean {
    val bytes = address.address ?: return false
    return when (bytes.size) {
        IPV4_BYTES -> isLanV4(bytes, 0)
        IPV6_BYTES -> isLanV6(bytes)
        else -> false
    }
}

private const val IPV4_BYTES = 4
private const val IPV6_BYTES = 16
private const val V4_MAPPED_PREFIX_BYTES = 10

private fun isLanV4(
    b: ByteArray,
    offset: Int,
): Boolean {
    val b0 = b[offset].toInt() and 0xFF
    val b1 = b[offset + 1].toInt() and 0xFF
    return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
}

private fun isLanV6(b: ByteArray): Boolean {
    // An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is judged by the mapped IPv4 address.
    val v4Mapped =
        (0 until V4_MAPPED_PREFIX_BYTES).all { b[it].toInt() == 0 } &&
            b[V4_MAPPED_PREFIX_BYTES].toInt() == 0xFF && b[V4_MAPPED_PREFIX_BYTES + 1].toInt() == 0xFF
    if (v4Mapped) return isLanV4(b, V4_MAPPED_PREFIX_BYTES + 2)

    val b0 = b[0].toInt() and 0xFF
    val b1 = b[1].toInt() and 0xFF
    return (b0 and 0xFE) == 0xFC || (b0 == 0xFE && (b1 and 0xC0) == 0x80)
}

/**
 * Parses an IP literal without a DNS lookup: `InetAddress.getByName` never leaves the machine for
 * a syntactically valid literal. `HttpUrl` hands us the normalized host (IPv6 uncompressed and
 * bracket-free, a `%zone` suffix possible).
 */
private fun parseIpLiteral(host: String): InetAddress? {
    if (':' in host) return runCatching { InetAddress.getByName(host.substringBefore('%')) }.getOrNull()
    val parts = host.split('.')
    val isV4 =
        parts.size == IPV4_PARTS &&
            parts.all { part ->
                part.isNotEmpty() && part.length <= IPV4_PART_DIGITS && part.all(Char::isDigit) && part.toInt() <= 255
            }
    if (!isV4) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
}

private const val IPV4_PARTS = 4
private const val IPV4_PART_DIGITS = 3
