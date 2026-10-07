// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Origin
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Maps an OkHttp URL to the credential origin (lowercase host, `url.port` defaults 80/443). */
fun Origin.Companion.of(url: HttpUrl): Origin = Origin(url.scheme, url.host.lowercase(), url.port)

/**
 * Basic auth for private feeds and their same-origin enclosures (01 Interceptors). A **network**
 * interceptor, so it is re-evaluated on every redirect hop: credentials attach only when the hop's
 * `Origin.of(url)` equals the stored credential's origin — an `https → http` or cross-host hop
 * therefore never carries them. An `Authorization` header set by the caller (03's not-yet-stored
 * credentials) is left alone.
 *
 * Public, not `internal`: `NetworkClients` is injected by graphs outside this module, and Kotlin
 * forbids an `internal` type in a `public` constructor signature.
 */
@SingleIn(AppScope::class)
@Inject
class AuthInterceptor(
    private val lookup: CredentialLookup,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val value = lookup.basicAuthorization(Origin.of(request.url)) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", value).build())
    }
}
