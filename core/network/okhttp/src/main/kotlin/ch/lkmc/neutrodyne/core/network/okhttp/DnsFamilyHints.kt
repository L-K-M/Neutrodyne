// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.IpFamily
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory `hostSuffix → IpFamily` map for 04's IP-family matching (01 Interceptors): the main
 * process's resolver records which family each resolved media host used, and [FamilyHintDns]
 * steers later lookups to the same family. `:ytx` never writes here — `PyHttp` pins per call with
 * `OkHttpClient.pinnedToFamily` instead, because two concurrent calls may want different families.
 */
@SingleIn(AppScope::class)
@Inject
class DnsFamilyHints {
    private val hints = ConcurrentHashMap<String, IpFamily>()

    /** Records [family] for [hostSuffix]; `null` clears the hint. */
    fun set(
        hostSuffix: String,
        family: IpFamily?,
    ) {
        val key = hostSuffix.lowercase().trimStart('.')
        if (family == null) hints.remove(key) else hints[key] = family
    }

    /**
     * The hinted family for [host] — a suffix `h` matches `host == h` and `host` ending in `.h`,
     * so `googlevideo.com` covers `rr1---sn-x.googlevideo.com` but never `notgooglevideo.com`.
     * The longest matching suffix wins.
     */
    fun familyFor(host: String): IpFamily? {
        val key = host.lowercase()
        return hints.entries
            .filter { key == it.key || key.endsWith("." + it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }
}

/**
 * `Dns` returning only the A ([IpFamily.V4]) or only the AAAA ([IpFamily.V6]) records for a hinted
 * host, and every record when the hinted family has none or no hint exists (01 Interceptors).
 */
internal class FamilyHintDns(
    private val delegate: Dns,
    private val familyFor: (String) -> IpFamily?,
) : Dns {
    constructor(delegate: Dns, hints: DnsFamilyHints) : this(delegate, hints::familyFor)

    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val family = familyFor(hostname) ?: return all
        return all.filter { it.isFamily(family) }.ifEmpty { all }
    }
}

private fun InetAddress.isFamily(family: IpFamily): Boolean =
    when (family) {
        IpFamily.V4 -> this is Inet4Address
        IpFamily.V6 -> this is Inet6Address
    }
