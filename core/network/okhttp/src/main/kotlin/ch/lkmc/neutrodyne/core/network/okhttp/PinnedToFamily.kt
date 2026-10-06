// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.IpFamily
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.EnumMap
import java.util.WeakHashMap

// Keyed weakly: a pinned clone never outlives the client that produced it, and a dead
// CoreClients leaves nothing behind. Two-level so one call per family per client returns
// the same instance — "derived for the same family" is a stable, shareable client (04).
private val pinCache = Collections.synchronizedMap(WeakHashMap<OkHttpClient, EnumMap<IpFamily, OkHttpClient>>())

/**
 * Returns a client that resolves every host to [family] only — `PyHttp`'s per-call pin, where two
 * concurrent calls may want different families and `DnsFamilyHints` is the wrong tool (01
 * Interceptors). The derived client shares the receiver's dispatcher, connection pool and
 * interceptors; repeated calls for the same family return the same instance.
 */
fun OkHttpClient.pinnedToFamily(family: IpFamily): OkHttpClient =
    // `synchronizedMap.getOrPut` is not atomic — two concurrent pins for one family would race.
    synchronized(pinCache) {
        pinCache
            .getOrPut(this) { EnumMap(IpFamily::class.java) }
            .getOrPut(family) { newBuilder().dns(FamilyHintDns(dns) { family }).build() }
    }
