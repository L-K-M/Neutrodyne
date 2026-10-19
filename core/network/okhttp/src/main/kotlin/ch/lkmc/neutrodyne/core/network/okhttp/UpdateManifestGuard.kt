// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * API-client policy for the notify-only manifest request. Check Location before OkHttp follows
 * it (and before a foreign DNS lookup); the tag survives its engine-level redirect requests.
 * No marker header or signed URL is sent to a logger by this policy.
 */
internal object UpdateManifestGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.endsWith("/neutrodyne-update.json")) return chain.proceed(request)
        if (!allowed(request.url) || request.url.host != GITHUB_HOST) return rejected(request)

        return chain.proceed(request.newBuilder().tag(UpdateManifestCall::class.java, UpdateManifestCall()).build())
    }

    internal fun allowed(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && url.host in ALLOWED_HOSTS &&
            (url.host != GITHUB_HOST || url.query == null)

    internal fun rejected(request: Request): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Update transport rejected")
            .body("".toResponseBody())
            .build()

    private const val GITHUB_HOST = "github.com"
    private val ALLOWED_HOSTS =
        setOf(GITHUB_HOST, "release-assets.githubusercontent.com")
}

internal class UpdateManifestCall(
    var hops: Int = 0,
)

/** AuthInterceptor also sees the tag, so credential lookup is bypassed on every hop. */
internal object UpdateManifestHopGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = request.tag(UpdateManifestCall::class.java) ?: return chain.proceed(request)
        if (!UpdateManifestGuard.allowed(request.url) || call.hops++ > MAX_REDIRECTS) {
            return UpdateManifestGuard.rejected(request)
        }

        val clean =
            request
                .newBuilder()
                .removeHeader(
                    "Authorization",
                ).removeHeader("Cookie")
                .removeHeader("Proxy-Authorization")
                .build()
        val response = chain.proceed(clean)
        if (response.code !in REDIRECT_STATUSES) return response

        val target = response.header("Location")?.let { request.url.resolve(it) }
        if (target != null && UpdateManifestGuard.allowed(target) && call.hops <= MAX_REDIRECTS) return response

        response.close()
        return UpdateManifestGuard.rejected(clean)
    }

    private const val MAX_REDIRECTS = 5
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
}
