// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.common.PublisherAuthPolicy
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
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
    // Lazy (01 DI rule 7): the lookup is the M1b `SecretStore`, which reaches the database —
    // resolving it at construction would pull `requireDatabase` into anything that touches the
    // client family (e.g. `imageLoaderFactory` at `Application.onCreate`). Requests only run on
    // background threads after the start-up gate, so the first `get()` cannot stall the UI.
    private val lookup: Provider<CredentialLookup>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val policy = request.header(PublisherAuthPolicy.HEADER)
        if (policy == PublisherAuthPolicy.ANONYMOUS.name ||
            request.tag(PublisherAuthPolicy::class.java) == PublisherAuthPolicy.ANONYMOUS
        ) {
            // Follow-ups retain the immutable request policy, never the credential provider.
            return chain.proceed(
                request
                    .newBuilder()
                    .removeHeader(PublisherAuthPolicy.HEADER)
                    .removeHeader(DIRECTORY_ORIGIN_HEADER)
                    .removeHeader("Authorization")
                    .tag(PublisherAuthPolicy::class.java, PublisherAuthPolicy.ANONYMOUS)
                    .build(),
            )
        }
        if (policy != null) request = request.newBuilder().removeHeader(PublisherAuthPolicy.HEADER).build()
        val directoryOrigin =
            request.header(DIRECTORY_ORIGIN_HEADER)
                ?: request.tag(DirectoryAuthOrigin::class.java)?.value
        if (directoryOrigin != null) {
            val builder =
                request
                    .newBuilder()
                    .removeHeader(DIRECTORY_ORIGIN_HEADER)
                    .tag(DirectoryAuthOrigin::class.java, DirectoryAuthOrigin(directoryOrigin))
            if ("${request.url.scheme}://${request.url.host}:${request.url.port}" != directoryOrigin) {
                builder.removeHeader("X-Auth-Key").removeHeader("X-Auth-Date").removeHeader("Authorization")
            }
            // Directory requests never borrow a publisher's stored Basic credentials.
            return chain.proceed(builder.build())
        }
        if (request.header("Authorization") != null) return chain.proceed(request)
        val value = lookup().basicAuthorization(Origin.of(request.url)) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", value).build())
    }
}

/** Internal Ktor→island marker, removed before the request reaches the wire. */
private const val DIRECTORY_ORIGIN_HEADER = "X-Neutrodyne-Directory-Origin"

private class DirectoryAuthOrigin(
    val value: String,
)
