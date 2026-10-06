// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The seven purpose clients (01 Networking baseline), every one a `newBuilder()` derivative so it
 * shares the dispatcher, pool, DNS chain and interceptor set with the family. [base] adds
 * `AuthInterceptor` as a **network** interceptor — re-evaluated on every redirect hop, so
 * credentials never ride a cross-origin hop.
 */
@SingleIn(AppScope::class)
@Inject
class NetworkClients(
    private val coreClients: CoreClients,
    auth: AuthInterceptor,
) {
    /** Every purpose client except YOUTUBE and SYNC derives from this one. */
    val base: OkHttpClient =
        coreClients.core
            .newBuilder()
            .addNetworkInterceptor(auth)
            .build()

    // FEED never follows redirects itself: 03's redirect policy is explicit, and a refresh must
    // see the hop's `If-None-Match`/`If-Modified-Since` state. The `callTimeout` is also what
    // turns "crawl" into a failure (R1/N10).
    val feed: OkHttpClient =
        base
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(FEED_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    val api: OkHttpClient =
        base
            .newBuilder()
            .callTimeout(API_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    val image: OkHttpClient =
        base
            .newBuilder()
            .readTimeout(IMAGE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    // `IdentityEncodingInterceptor` (an application interceptor, so a re-derived client keeps it
    // through redirects) pins `Accept-Encoding: identity` for byte-exact bodies (05).
    val media: OkHttpClient =
        base
            .newBuilder()
            .addInterceptor(IdentityEncodingInterceptor)
            .build()

    val download: OkHttpClient =
        base
            .newBuilder()
            .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(IdentityEncodingInterceptor)
            .build()

    /** Maps [HttpClientKind] to its client; YOUTUBE and SYNC come from [CoreClients]. */
    operator fun get(kind: HttpClientKind): OkHttpClient =
        when (kind) {
            HttpClientKind.FEED -> feed
            HttpClientKind.API -> api
            HttpClientKind.IMAGE -> image
            HttpClientKind.MEDIA -> media
            HttpClientKind.DOWNLOAD -> download
            HttpClientKind.YOUTUBE -> coreClients.youtube
            HttpClientKind.SYNC -> coreClients.sync
        }

    private companion object {
        const val FEED_CALL_TIMEOUT_SECONDS = 120L
        const val API_CALL_TIMEOUT_SECONDS = 8L
        const val IMAGE_READ_TIMEOUT_SECONDS = 20L
        const val IMAGE_CALL_TIMEOUT_SECONDS = 60L
        const val DOWNLOAD_READ_TIMEOUT_SECONDS = 60L
    }
}
