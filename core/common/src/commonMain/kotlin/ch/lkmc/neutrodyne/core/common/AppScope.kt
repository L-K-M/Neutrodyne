// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.common

/**
 * The one application scope of both app shells (`AndroidAppGraph`, `DesktopAppGraph`) and the aggregation key of
 * every module's Metro contributions (01 Dependency injection, D82). It is Metro's own `AppScope` class: Metro warns
 * that a `@Scope` annotation used as an aggregation key is "probably not what you meant" (CI, 2026-10-06). The alias
 * keeps one import for every module.
 */
typealias AppScope = dev.zacsweers.metro.AppScope

/**
 * The Android `:ytx` process's aggregation scope (`YtxGraph`, 01 Dependency injection, D82): it aggregates only
 * `YtxScope` contributions — the yt-dlp engine side plus the network island's `CoreClients` and its inputs — so no
 * database, DataStore or credential binding exists in `:ytx` at all. Like [AppScope] it lives here because the
 * island has to see it and cannot depend on `:app` (S8). A plain abstract class, as Metro expects for aggregation
 * scopes; `@SingleIn(YtxScope::class)` scopes bindings to that graph.
 */
abstract class YtxScope private constructor()
