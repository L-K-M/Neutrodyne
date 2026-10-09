// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import coil3.ComponentRegistry
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Coil's OkHttp fetcher on the island's `IMAGE` client (08; no OkHttp `Cache`, D10, 01). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class OkHttpImageNetworkComponent(
    private val clients: NetworkClients,
) : ImageNetworkComponent {
    override fun install(builder: ComponentRegistry.Builder) {
        builder.add(OkHttpNetworkFetcherFactory(callFactory = { clients.image }))
    }
}
