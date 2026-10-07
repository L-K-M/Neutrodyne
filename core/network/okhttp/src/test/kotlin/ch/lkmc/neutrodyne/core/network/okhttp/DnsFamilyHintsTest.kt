// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.IpFamily
import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import org.junit.Test
import java.net.InetAddress

class DnsFamilyHintsTest {
    private val v4a = InetAddress.getByName("93.184.216.34")
    private val v4b = InetAddress.getByName("93.184.216.35")
    private val v6a = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")

    private fun dnsReturning(vararg addresses: InetAddress) = Dns { addresses.toList() }

    @Test
    fun `set, suffix matching and clearing`() {
        val hints = DnsFamilyHints()
        hints.set("googlevideo.com", IpFamily.V6)
        assertThat(hints.familyFor("googlevideo.com")).isEqualTo(IpFamily.V6)
        assertThat(hints.familyFor("rr1---sn-x.googlevideo.com")).isEqualTo(IpFamily.V6)
        // Suffixes match on a label boundary only.
        assertThat(hints.familyFor("notgooglevideo.com")).isNull()
        assertThat(hints.familyFor("other.net")).isNull()

        hints.set("googlevideo.com", null)
        assertThat(hints.familyFor("rr1---sn-x.googlevideo.com")).isNull()
    }

    @Test
    fun `matching is case-insensitive and the longest suffix wins`() {
        val hints = DnsFamilyHints()
        hints.set("GOOGLEVIDEO.COM", IpFamily.V6)
        hints.set("a.googlevideo.com", IpFamily.V4)
        assertThat(hints.familyFor("X.GoogleVideo.Com")).isEqualTo(IpFamily.V6)
        assertThat(hints.familyFor("x.a.googlevideo.com")).isEqualTo(IpFamily.V4)
    }

    @Test
    fun `FamilyHintDns filters to the hinted family and falls back when it is empty`() {
        val dns = FamilyHintDns(dnsReturning(v4a, v6a, v4b)) { IpFamily.V6 }
        assertThat(dns.lookup("x.googlevideo.com")).containsExactly(v6a)

        val onlyV4 = FamilyHintDns(dnsReturning(v4a, v4b)) { IpFamily.V6 }
        // The hinted family has no records — return every record rather than nothing.
        assertThat(onlyV4.lookup("x.googlevideo.com")).containsExactly(v4a, v4b)

        val noHint = FamilyHintDns(dnsReturning(v4a, v6a)) { null }
        assertThat(noHint.lookup("x.googlevideo.com")).containsExactly(v4a, v6a)
    }

    @Test
    fun `FamilyHintDns reads the shared hints`() {
        val hints = DnsFamilyHints()
        hints.set("googlevideo.com", IpFamily.V4)
        val dns = FamilyHintDns(dnsReturning(v4a, v6a), hints)
        assertThat(dns.lookup("x.googlevideo.com")).containsExactly(v4a)
        assertThat(dns.lookup("unrelated.net")).containsExactly(v4a, v6a)
    }

    @Test
    fun `pinnedToFamily pins dns and reuses dispatcher and pool`() {
        val base = newNetworkClients().api
        val pinned4 = base.pinnedToFamily(IpFamily.V4)
        val pinned6 = base.pinnedToFamily(IpFamily.V6)

        assertThat(base.pinnedToFamily(IpFamily.V4)).isSameInstanceAs(pinned4)
        assertThat(pinned4).isNotSameInstanceAs(pinned6)
        assertThat(pinned4.dispatcher).isSameInstanceAs(base.dispatcher)
        assertThat(pinned4.connectionPool).isSameInstanceAs(base.connectionPool)
        // Pinning doesn't rewire the original client.
        assertThat(pinned4.dns).isNotSameInstanceAs(base.dns)
    }

    @Test
    fun `pinnedToFamily filters every host to the family`() {
        val base =
            newNetworkClients()
                .api
                .newBuilder()
                .dns(dnsReturning(v4a, v6a))
                .build()
        val pinned = base.pinnedToFamily(IpFamily.V4)
        assertThat(pinned.dns.lookup("anything.example")).containsExactly(v4a)
        assertThat(base.pinnedToFamily(IpFamily.V6).dns.lookup("anything.example"))
            .containsExactly(v6a)
    }
}
