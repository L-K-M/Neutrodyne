// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json

/**
 * The Ktor side of the client family (01 Networking baseline): one `HttpClient` per kind, each
 * running Ktor's OkHttp engine with `NetworkClients[kind]` as `preconfigured` — the engine
 * derives `newBuilder()` from it, so the island's pool, dispatcher, DNS and interceptors apply.
 * S12 validated this arrangement; `expectSuccess` stays off so status codes reach the caller,
 * and redirects stay in the caller's hands for FEED.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class OkHttpNeutrodyneHttpClients
    @Inject
    constructor(
        networkClients: NetworkClients,
        userAgent: UserAgentProvider,
    ) : NeutrodyneHttpClients {
        private val clients =
            HttpClientKind.entries.associateWith { kind ->
                HttpClient(OkHttp) {
                    engine { preconfigured = networkClients[kind] }
                    // Status codes are data for 03's fetch pipeline, not exceptions.
                    expectSuccess = false
                    // The engine level keeps the island's per-kind redirect policy; Ktor's plugin must
                    // not add a second opinion for FEED.
                    followRedirects = kind != HttpClientKind.FEED
                    // 05's downloads may follow an https → http hop (publishers' CDN hand-offs).
                    if (kind == HttpClientKind.DOWNLOAD) {
                        install(HttpRedirect) { allowHttpsDowngrade = true }
                    }
                    // 10's long-lived sync stream.
                    if (kind == HttpClientKind.SYNC) install(SSE)
                    // Not for FEED: ContentNegotiation appends application/json to Accept, but 03's
                    // Request rules pin the feed Accept header verbatim (and feeds are never JSON).
                    if (kind != HttpClientKind.FEED) {
                        install(ContentNegotiation) { json(NeutrodyneJson) }
                    }
                    install(UserAgent) { agent = userAgent.value }
                }
            }

        override fun client(kind: HttpClientKind): HttpClient = clients.getValue(kind)
    }
