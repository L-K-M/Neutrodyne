// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sets the app User-Agent on OkHttp-only callers (Media3, Coil, `HttpByteSource`, `PyHttp`)
 * **only when the request has none** (01 Interceptors), so a client-specific User-Agent that
 * yt-dlp sets per request survives unchanged. Ktor requests already carry the agent through
 * Ktor's `UserAgent` plugin, which the same [UserAgentProvider] feeds.
 */
@SingleIn(AppScope::class)
@Inject
class UserAgentInterceptor(
    private val userAgent: UserAgentProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent.value).build())
    }
}
