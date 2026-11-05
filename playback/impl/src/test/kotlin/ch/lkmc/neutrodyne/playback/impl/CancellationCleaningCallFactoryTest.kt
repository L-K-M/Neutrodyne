// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.impl

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test

class CancellationCleaningCallFactoryTest {
    private val server = MockWebServer()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `cancelling a delivered call closes its response body`() {
        server.enqueue(MockResponse.Builder().body("hello").build())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(CancellationCleaningCallFactory.installInterceptor())
                .build()
        val call =
            CancellationCleaningCallFactory
                .wrap(client)
                .newCall(Request.Builder().url(server.url("/")).build())
        val body = AtomicReference<ResponseBody>()
        val bodyClosed = AtomicBoolean(false)
        val delivered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val finished = CountDownLatch(1)
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    error: IOException,
                ) {
                    finished.countDown()
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    body.set(response.body)
                    delivered.countDown()
                    // OkHttp hands the delegate call to the callback, so cancel through the
                    // wrapper reference, exactly as Media3's OkHttpDataSource does.
                    releaseCallback.await(5, TimeUnit.SECONDS)
                    try {
                        body.get().source().request(1)
                    } catch (closed: IllegalStateException) {
                        bodyClosed.set(true)
                    } finally {
                        finished.countDown()
                    }
                }
            },
        )

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue()
        call.cancel()
        releaseCallback.countDown()
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(bodyClosed.get()).isTrue()
    }
}
