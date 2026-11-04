// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.playback.engine.DesktopSourceResolver
import ch.lkmc.neutrodyne.playback.engine.EngineItem
import ch.lkmc.neutrodyne.playback.engine.ResolvedSource

/** JDK 25 engine contract fake; compiled by desktop engine consumers' test source set. */
class FakeDesktopSourceResolver : DesktopSourceResolver {
    val calls = mutableListOf<Pair<EngineItem, Int>>()
    val sources = mutableMapOf<Pair<Long, Int>, ResolvedSource>()

    override suspend fun resolve(
        item: EngineItem,
        attempt: Int,
    ): ResolvedSource =
        synchronized(this) {
            calls += item to attempt
            requireNotNull(sources[item.episodeId to attempt]) { "no source for ${item.episodeId}, attempt $attempt" }
        }
}
