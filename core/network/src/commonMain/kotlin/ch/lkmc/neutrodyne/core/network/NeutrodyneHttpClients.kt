// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import io.ktor.client.HttpClient

/**
 * One Ktor `HttpClient` per [HttpClientKind] (01 Networking baseline): the API the fetch and sync
 * code consumes. Each client runs Ktor's OkHttp engine with the island's matching client as
 * `preconfigured`, so timeouts, interceptors, the DNS chain and the connection pool are the
 * island's, shared with the raw-OkHttp consumers (Media3, Coil, `PyHttp`).
 */
interface NeutrodyneHttpClients {
    /** The Ktor client for [kind]; seven clients exist, one per enum value. */
    fun client(kind: HttpClientKind): HttpClient
}
