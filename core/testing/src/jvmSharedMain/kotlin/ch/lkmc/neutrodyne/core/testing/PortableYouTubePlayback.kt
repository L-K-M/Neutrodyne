// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.ResolvedUrlCache
import ch.lkmc.neutrodyne.youtube.engine.YtDlpChannelLookup
import ch.lkmc.neutrodyne.youtube.engine.YtDlpChannelSearch
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
    private lateinit var channelLookup: YtDlpChannelLookup
    private lateinit var channelSearch: YtDlpChannelSearch
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
    val lookup: ExtractorChannelLookup get() = channelLookup
    val search: YouTubeChannelSearch get() = channelSearch
    val control: ch.lkmc.neutrodyne.youtube.api.YouTubeEngine get() = engine

    /** A persisted compiled-store result, without performing interpreter preparation. */
    fun compiledStore() {
        store.preparation.value = ch.lkmc.neutrodyne.youtube.engine.EnginePreparation.COMPILED
    }

    /** Flips the durable enable flag so [capabilities] republishes like a real settings write. */
    fun setEngineEnabled(enabled: Boolean) {
        settings.state.value = settings.state.value.copy(enabled = enabled)
    }

    fun prewarms(): List<ch.lkmc.neutrodyne.youtube.api.PrewarmReason> = transport.prewarms.toList()

    fun preparations(): Int = store.preparations

    init {
        val hints =
            object : IpFamilyHints {
                private var family: IpFamily? = null

                override fun current() = family

                override fun set(family: IpFamily?) {
                    this.family = family
                }
            }
        // One client owns admission/deadline/cursor routing for every port; lookup and search
        // share the same instance so the journey exercises the real mapper without a second queue.
        val client = YtDlpClient(transport, clock, scope)
        val context = { YtxContext("en", "US", "test-agent") }
        resolver =
            YtDlpStreamResolver(
                client,
                engine,
                health,
                ResolvedUrlCache(clock),
                YtDlpErrorMapper(clock),
                hints,
                context,
                clock,
                scope,
            )
        channelLookup = YtDlpChannelLookup(client, engine, health, context, clock)
        channelSearch = YtDlpChannelSearch(client, engine, health, context, clock)
    }

    fun failWith(code: String) {
        transport.answer = {
            throw ch.lkmc.neutrodyne.youtube.engine
                .YtxCallException(code)
        }
    }

    fun nativeCalls(): Int = transport.calls.size

    /** Wire method names, so consumers need no `:youtube:engine` compile edge. */
    fun transportCallNames(): List<String> = transport.calls.map { it.method.wireName }

    fun cancellations(): Int = transport.cancelled.size

    fun reply(block: suspend () -> String) {
        transport.answer = { block() }
    }

    /** Method-aware variant for multi-step journeys (search → lookup → resolve). */
    fun replyFor(block: suspend (method: String, json: String) -> String) {
        transport.answer = { call -> block(call.method.wireName, call.json) }
    }
}
