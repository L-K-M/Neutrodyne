// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import dev.zacsweers.metro.Inject
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The three clients all others derive from (01 Networking baseline): [core] everything,
 * [youtube] and [sync] their own lanes. This class holds the module's only `OkHttpClient.Builder`
 * construction — every consumer derives with `newBuilder()`, sharing the dispatcher, connection
 * pool, DNS chain (LAN guard + family hints) and interceptors with their siblings.
 *
 * [core] is `Strict` LAN-guarded; [sync] carries `Mode.SYNC` guard elements that obey
 * `LocalNetworkAccess`. No OkHttp `Cache` anywhere (05 owns the media cache).
 *
 * The class stays **unscoped**: `AppScope` and `YtxScope` each bind it through their own
 * `@Provides @SingleIn(…)` container in `NetworkIslandBindings`/`NetworkIslandYtxBindings`
 * (per-process singletons, S8 question 6) — one class cannot be `@SingleIn` two scopes.
 */
class CoreClients
    @Inject
    constructor(
        private val ua: UserAgentInterceptor,
        private val lanGuard: LocalNetworkGuard,
        private val hints: DnsFamilyHints,
        @param:DebugInterceptors private val debugInterceptors: Set<Interceptor>,
    ) : ConnectionPoolEvictor {
    /**
     * The desktop-test seam (03 note 2026-11-02): the add pipeline's LAN refusal is syntactic,
     * so discovery tests serve their MockWebServer under a fake public host that [baseDns] maps
     * to loopback. The clients build during this delegation, so the Dns wrapper in `coreBuilder`
     * reads [baseDns] per lookup — assigning it here still lands before the first request.
     */
    constructor(
        ua: UserAgentInterceptor,
        lanGuard: LocalNetworkGuard,
        hints: DnsFamilyHints,
        @DebugInterceptors debugInterceptors: Set<Interceptor>,
        baseDns: Dns,
    ) : this(ua, lanGuard, hints, debugInterceptors) {
        this.baseDns = baseDns
    }

    /** `Dns.SYSTEM` in production; only the test seam rewrites it, before any lookup can run. */
    private var baseDns: Dns = Dns.SYSTEM

    // Declared before the clients: property initializers run in order, and `coreBuilder` reads them.
    private val dispatcher =
        Dispatcher().apply {
            maxRequests = MAX_REQUESTS
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }
    private val pool = ConnectionPool(POOL_MAX_IDLE, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)

    private fun coreBuilder(mode: LocalNetworkGuard.Mode): OkHttpClient.Builder =
        OkHttpClient
            .Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .dns(lanGuard.dns(FamilyHintDns(Dns { hostname -> baseDns.lookup(hostname) }, hints), mode))
            .addInterceptor(lanGuard.interceptor(mode))
            .addInterceptor(ua)
            .apply { debugInterceptors.forEach(::addInterceptor) }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    val core: OkHttpClient = coreBuilder(LocalNetworkGuard.Mode.STRICT).build()

    // YouTube's engine has its own retry policy (04), so it skips `base`'s auth interceptor and
    // call timeout. PyHttp re-derives per family — the derived client keeps the shared pool.
    val youtube: OkHttpClient =
        core
            .newBuilder()
            .callTimeout(YOUTUBE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    // Sync keeps a strict-by-default LAN guard that `LocalNetworkAccess` unlocks, and no `base`
    // auth: `SyncClient` adds its sync token per request (10).
    val sync: OkHttpClient =
        coreBuilder(LocalNetworkGuard.Mode.SYNC)
            .callTimeout(SYNC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /** 11's wake path: pooled sockets do not survive a system sleep. */
    override fun evict() {
        pool.evictAll()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val YOUTUBE_CALL_TIMEOUT_SECONDS = 60L
        const val SYNC_CALL_TIMEOUT_SECONDS = 60L
        const val MAX_REQUESTS = 64
        const val MAX_REQUESTS_PER_HOST = 8
        const val POOL_MAX_IDLE = 10
        const val POOL_KEEP_ALIVE_MINUTES = 5L
    }
}
