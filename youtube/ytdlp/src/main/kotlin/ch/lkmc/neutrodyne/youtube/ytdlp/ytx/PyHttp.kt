// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import ch.lkmc.neutrodyne.core.common.YtxScope
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.pinnedToFamily
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Credential-free HTTP, with socket ownership scoped to a single shim call. */
@SingleIn(YtxScope::class)
@Inject
class PyHttp(
    private val clients: CoreClients,
) {
    private val lock = Any()
    private val calls = mutableMapOf<Long, MutableSet<Call>>()
    private val cancelled = mutableSetOf<Long>()

    internal fun begin(id: Long) {
        synchronized(lock) {
            calls[id] = mutableSetOf()
            cancelled.remove(id)
        }
    }

    internal fun end(id: Long) {
        synchronized(lock) {
            calls.remove(id)?.forEach(Call::cancel)
            cancelled.remove(id)
        }
    }

    internal fun cancel(id: Long) =
        synchronized(lock) {
            if (id in calls) {
                cancelled += id
                calls[id]?.forEach(Call::cancel)
            }
        }

    fun execute(
        callId: Long,
        method: String,
        url: String,
        headersJson: String,
        body: ByteArray?,
        timeoutMs: Int,
        ipFamily: String?,
    ): PyHttpReply {
        val family = ipFamily?.let(IpFamily::valueOf)
        val client = if (family == null) clients.youtube else clients.youtube.pinnedToFamily(family)
        val request = Request.Builder().url(url).method(method, body?.toRequestBody())
        Json.decodeFromString<Map<String, String>>(headersJson).forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", true)) request.header(name, value)
        }
        val call = client.newCall(request.build())
        call.timeout().timeout(timeoutMs.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (callId !in calls || callId in cancelled) throw IOException("YouTube request cancelled")
            calls.getValue(callId) += call
        }
        try {
            return call.execute().use { response ->
                val source = response.body.source()
                if (source.request(YtxLimits.HTTP_BODY_BYTES.toLong() + 1)) {
                    throw IOException("YouTube response exceeds limit")
                }
                PyHttpReply(
                    response.code,
                    response.request.url.toString(),
                    Json.encodeToString(response.headers.map { listOf(it.first, it.second) }),
                    source.readByteArray(),
                )
            }
        } catch (_: IOException) {
            // Network exceptions may carry signed URLs or cookie values across Java interop.
            throw IOException("YouTube HTTP request failed")
        } finally {
            synchronized(lock) { calls[callId]?.remove(call) }
        }
    }
}

class PyHttpReply(
    @JvmField val status: Int,
    @JvmField val url: String,
    @JvmField val headersJson: String,
    @JvmField val body: ByteArray,
)
