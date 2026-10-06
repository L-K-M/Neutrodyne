// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sets `Accept-Encoding: identity` so OkHttp's bridge neither adds gzip nor decompresses (01
 * Interceptors): MEDIA and DOWNLOAD need byte-exact bodies — ranges and `SimpleCache`/`SpanCache`
 * keys must match the server's bytes.
 */
object IdentityEncodingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("Accept-Encoding", "identity")
                .build(),
        )
}
