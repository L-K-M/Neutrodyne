// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import dev.zacsweers.metro.Inject
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
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
@Inject
class CoreClients(
    private val ua: UserAgentInterceptor,
    private val lanGuard: LocalNetworkGuard,
    private val hints: DnsFamilyHints,
    @param:DebugInterceptors private val debugInterceptors: Set<Interceptor>,
) : ConnectionPoolEvictor {
    /**
     * The desktop-test seam (03 note 2026-11-02): discovery tests serve their MockWebServer
     * under a fake public host that [baseDns] maps to a chosen loopback address, and
     * [isLocalPeer] decides which loopback sub-addresses count as "the LAN" so marked calls
     * can still reach the public stand-in (127.0.0.1) while 127.0.0.2 refuses. The clients
     * build during this delegation, so the Dns wrapper and peer guard in `coreBuilder` read
     * both lazily — assigning them here still lands before the first request.
     */
    constructor(
        ua: UserAgentInterceptor,
        lanGuard: LocalNetworkGuard,
        hints: DnsFamilyHints,
        @DebugInterceptors debugInterceptors: Set<Interceptor>,
        baseDns: Dns,
        isLocalPeer: (InetSocketAddress) -> Boolean = ::isLocalPeerSocket,
    ) : this(ua, lanGuard, hints, debugInterceptors) {
        this.baseDns = baseDns
        this.isLocalPeer = isLocalPeer
    }

    /** `Dns.SYSTEM` in production; only the test seam rewrites it, before any lookup can run. */
    private var baseDns: Dns = Dns.SYSTEM

    /** The production peer classification; the test seam narrows it to one loopback address. */
    private var isLocalPeer: (InetSocketAddress) -> Boolean = ::isLocalPeerSocket

    // Declared before the clients: property initializers run in order, and `coreBuilder` reads them.
    private val dispatcher =
        Dispatcher().apply {
            maxRequests = MAX_REQUESTS
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }
    private val pool = ConnectionPool(POOL_MAX_IDLE, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)

    private fun coreBuilder(mode: LocalNetworkGuard.Mode): OkHttpClient.Builder {
        val dns =
            lanGuard.dns(
                PreConfirmationDns(FamilyHintDns(Dns { hostname -> baseDns.lookup(hostname) }, hints)) { peer ->
                    isLocalPeer(peer)
                },
                mode,
            )
        val destinations = PreConfirmationDestinations(dns) { peer -> isLocalPeer(peer) }
        return OkHttpClient
            .Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .dns(dns)
            .addInterceptor(lanGuard.interceptor(mode))
            .addInterceptor(ua)
            .addInterceptor(PreConfirmationRequestGuard(destinations))
            // The M7 actual-peer guard (03 note 2026-11-02): a network interceptor, so it runs
            // after the connection exists — on every follow-up hop — and only for requests
            // carrying the pre-confirmation marker. On `core` so feed, image and the other
            // derivatives all get it.
            .addNetworkInterceptor(PreConfirmationPeerGuard({ peer -> isLocalPeer(peer) }, destinations))
            .apply { debugInterceptors.forEach(::addInterceptor) }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

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

    // 10's events stream (Live updates): the SSE call derives from `sync` — same SYNC-mode LAN
    // guard, DNS chain and pool — but with no read and no call timeout, so a heartbeat-driven
    // stream can stay open far past the 30 s read timeout the rest of the family obeys. The
    // client-side watchdog (2×hb + 15 s of silence) is what closes a dead stream.
    val syncStream: OkHttpClient =
        sync
            .newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
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

/** An unresolved peer can never be verified public — refuse it like a local address. */
private fun isLocalPeerSocket(peer: InetSocketAddress): Boolean =
    peer.address == null || isLocalPeerAddress(peer.address)
