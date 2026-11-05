// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.impl

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3's `OkHttpDataSource` cancels a call when the loader thread is interrupted after
 * the response arrived, and never closes that response body. The debug build's StrictMode
 * VM policy treats the unclosed body as a leaked closable and kills the process. This
 * factory closes every body a cancelled call produced.
 *
 * Install [installInterceptor] on the client and pass [wrap] of that client to Media3:
 * the interceptor registers each body against the call that produced it, and the wrapper
 * closes the registered bodies on `cancel()`. Bodies closed normally unregister
 * themselves, so the registry holds only in-flight responses.
 */
internal class CancellationCleaningCallFactory
    private constructor(
        private val delegate: Call.Factory,
    ) : Call.Factory {
        override fun newCall(request: Request): Call = CleaningCall(delegate.newCall(request))

        private class CleaningCall(
            private val delegate: Call,
        ) : Call by delegate {
            override fun cancel() {
                delegate.cancel()
                ResponseBodyCancellationTracker.closeBodies(delegate)
            }
        }

        companion object {
            fun installInterceptor(): Interceptor =
                Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response
                        .newBuilder()
                        .body(ResponseBodyCancellationTracker.track(response.body, chain.call()))
                        .build()
                }

            fun wrap(delegate: Call.Factory): Call.Factory = CancellationCleaningCallFactory(delegate)
        }
    }

private object ResponseBodyCancellationTracker {
    private val bodies = ConcurrentHashMap<Call, MutableSet<ResponseBody>>()

    fun track(
        body: ResponseBody,
        call: Call,
    ): ResponseBody {
        val tracked = TrackedResponseBody(body, call)
        bodies.getOrPut(call) { ConcurrentHashMap.newKeySet() }.add(tracked)
        return tracked
    }

    fun closeBodies(call: Call) {
        bodies.remove(call)?.forEach { body -> runCatching { body.close() } }
    }

    private class TrackedResponseBody(
        private val delegate: ResponseBody,
        private val call: Call,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = delegate.source()

        override fun close() {
            bodies[call]?.remove(this)
            delegate.close()
        }
    }
}
