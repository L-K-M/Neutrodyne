// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.ResolvedUrlCache
import ch.lkmc.neutrodyne.youtube.engine.YtDlpClient
import ch.lkmc.neutrodyne.youtube.engine.YtDlpEngine
import ch.lkmc.neutrodyne.youtube.engine.YtDlpErrorMapper
import ch.lkmc.neutrodyne.youtube.engine.YtDlpStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import kotlinx.coroutines.CoroutineScope

/** Real portable owners behind API ports, so Android consumer tests need no engine dependency. */
class PortableYouTubePlayback(
    clock: Clock,
    scope: CoroutineScope,
    healthFactory: (() -> Unit) -> YouTubeExtractionHealth,
) {
    private lateinit var resolver: YtDlpStreamResolver
    private val transport = FakeYtxTransport()
    private val store = FakeEngineStore()
    private val settings = FakeEngineSettings()
    private val preflight = FakeEnginePlatformPreflight()
    val health = healthFactory { resolver.invalidateAll() }
    private val engine =
        YtDlpEngine(
            transport,
            store,
            settings,
            preflight,
            CompileMarker(42, "2026.08.19", "3.14"),
            health,
            scope,
        )
    val capabilities: YouTubeCapabilitiesSource get() = engine
    val streams: YouTubeStreamResolver get() = resolver

    init {
        val hints =
            object : IpFamilyHints {
                private var family: IpFamily? = null

                override fun current() = family

                override fun set(family: IpFamily?) {
                    this.family = family
                }
            }
        resolver =
            YtDlpStreamResolver(
                YtDlpClient(transport, clock, scope),
                engine,
                health,
                ResolvedUrlCache(clock),
                YtDlpErrorMapper(clock),
                hints,
                { YtxContext("en", "US", "test-agent") },
                clock,
                scope,
            )
    }

    fun failWith(code: String) {
        transport.answer = {
            throw ch.lkmc.neutrodyne.youtube.engine
                .YtxCallException(code)
        }
    }

    fun nativeCalls(): Int = transport.calls.size

    fun cancellations(): Int = transport.cancelled.size

    fun reply(block: suspend () -> String) {
        transport.answer = { block() }
    }
}
