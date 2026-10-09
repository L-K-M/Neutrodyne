// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import coil3.ComponentRegistry

/**
 * The fetcher half of the Coil pipeline (08 Coil ImageLoader): the platform source sets install
 * the OkHttp network fetcher on the island's `IMAGE` client, so common code never sees OkHttp.
 */
public interface ImageNetworkComponent {
    /** Adds this platform's fetcher factories to the [ComponentRegistry.Builder]. */
    public fun install(builder: ComponentRegistry.Builder)
}
