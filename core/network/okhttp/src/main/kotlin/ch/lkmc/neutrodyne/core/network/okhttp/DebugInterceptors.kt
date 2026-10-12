// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.common.YtxScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import okhttp3.Interceptor

/**
 * Qualifier for the debug-only `Set<Interceptor>` on `CoreClients` (01 Interceptors): empty except
 * in Android debug builds, where `app/src/debug/` contributes `DebugHttpLogInterceptor`.
 */
@Qualifier
annotation class DebugInterceptors

/**
 * The island's `AppScope` declarations (01 Components and scopes): the debug-interceptor
 * multibinding, and the two scoped providers this module's types need — `CoreClients` stays
 * unscoped so both scopes can bind it once, and `UserAgentProvider` is built here from the shells'
 * `PlatformInfo`/`BuildInfo` so Ktor's `UserAgent` plugin and `UserAgentInterceptor` emit the same
 * agent string.
 */
@ContributesTo(AppScope::class)
@BindingContainer
interface NetworkIslandBindings {
    /** Empty set so every graph compiles before `app/src/debug/` contributes (01 DI rule 5). */
    @Multibinds(allowEmpty = true)
    @DebugInterceptors
    fun debugInterceptors(): Set<Interceptor>

    companion object {
        /**
         * The AppScope instance — one client family per process. Constructed here rather than
         * re-injected: an explicit `@Provides` for `CoreClients` shadows its `@Inject`
         * constructor, so a `CoreClients` parameter would resolve to this method itself (cycle).
         */
        @Provides
        @SingleIn(AppScope::class)
        fun coreClients(
            ua: UserAgentInterceptor,
            lanGuard: LocalNetworkGuard,
            hints: DnsFamilyHints,
            @DebugInterceptors debugInterceptors: Set<Interceptor>,
        ): CoreClients = CoreClients(ua, lanGuard, hints, debugInterceptors)

        @Provides
        fun authInterceptor(lookup: CredentialLookup): AuthInterceptor = AuthInterceptor(lookup)

        /** The per-kind client family every purpose client derives from (01 Interceptors). */
        @Provides
        @SingleIn(AppScope::class)
        fun networkClients(
            core: CoreClients,
            auth: AuthInterceptor,
        ): NetworkClients = NetworkClients(core, auth)

        /**
         * M1a: no `SecretStore` yet — the lookup never answers, so the auth interceptor and
         * `FeedFetcher`'s 401 path are inert until M1b's store replaces this binding.
         */
        @Provides
        fun credentialLookup(): CredentialLookup = CredentialLookup.None

        /** 11's wake path — the shared pool bound under its eviction port. */
        @Provides
        fun connectionPoolEvictor(core: CoreClients): ConnectionPoolEvictor = core

        /**
         * The shared User-Agent string, built once from the shells' `PlatformInfo` and `BuildInfo`
         * so Ktor's `UserAgent` plugin and `UserAgentInterceptor` emit the same value (01).
         */
        @Provides
        @SingleIn(AppScope::class)
        fun userAgentProvider(
            info: PlatformInfo,
            build: BuildInfo,
        ): UserAgentProvider = UserAgentProvider(info, build.versionName, build.repoUrl)
    }
}

/**
 * The island's `YtxScope` declarations (01 Components and scopes): `YtxGraph` gets `CoreClients`
 * and its inputs only — the credential-free `core`/`youtube` clients (`sync` exists on the same
 * object but nothing in `:ytx` reaches it). `LocalNetworkAccess`, `LocalNetworkGuard` and
 * `DnsFamilyHints` are `@SingleIn(AppScope)` classes a `YtxScope` graph cannot satisfy, so the
 * providers below construct fresh process-local instances; the ytx LAN guard then stays strict
 * forever — correct, since YouTube traffic never targets the LAN.
 */
@ContributesTo(YtxScope::class)
@BindingContainer
interface NetworkIslandYtxBindings {
    companion object {
        /** The `:ytx` process's own `CoreClients` — a singleton per process, not shared. */
        @Provides
        @SingleIn(YtxScope::class)
        fun coreClients(
            ua: UserAgentInterceptor,
            lanGuard: LocalNetworkGuard,
            hints: DnsFamilyHints,
            @DebugInterceptors debugInterceptors: Set<Interceptor>,
        ): CoreClients = CoreClients(ua, lanGuard, hints, debugInterceptors)

        /** Same agent string as the main process — `PlatformInfo`/`BuildInfo` come from `YtxGraph`. */
        @Provides
        @SingleIn(YtxScope::class)
        fun userAgentProvider(
            info: PlatformInfo,
            build: BuildInfo,
        ): UserAgentProvider = UserAgentProvider(info, build.versionName, build.repoUrl)

        @Provides
        fun localNetworkAccess(): LocalNetworkAccess = LocalNetworkAccess()

        @Provides
        fun localNetworkGuard(
            access: LocalNetworkAccess,
            info: PlatformInfo,
        ): LocalNetworkGuard = LocalNetworkGuard(access, info)

        @Provides
        fun userAgentInterceptor(provider: UserAgentProvider): UserAgentInterceptor = UserAgentInterceptor(provider)

        @Provides
        fun dnsFamilyHints(): DnsFamilyHints = DnsFamilyHints()

        /** `:ytx` never has debug interceptors — `app/src/debug/` only contributes to `AppScope`. */
        @Provides
        @DebugInterceptors
        fun debugInterceptors(): Set<Interceptor> = emptySet()
    }
}
