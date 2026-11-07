// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

internal data class UpdateNotice(
    val version: String,
    val releaseUrl: String,
    val jar: String,
    val image: String,
    val minJava: Int,
)

internal data class ManifestResponse(
    val status: Int,
    val location: String? = null,
    val body: ByteArray = byteArrayOf(),
)

internal enum class UpdateCheckMode { ENABLED, DISABLED }

/** Notify only. Off never constructs a client or invokes the injected transport. Failures retain the last notice. */
internal class ServerUpdateNotice(
    private val mode: UpdateCheckMode,
    private val inputs: ServerInputs,
    private val currentVersion: String = ServerVersion.current(),
    private val repository: String = ServerVersion.repository(),
    private val fetch: (URI, Long) -> ManifestResponse = ::fetchManifestResponse,
) {
    @Volatile var notice: UpdateNotice? = null
        private set
    private var nextAt: Long? = null

    fun start(scope: CoroutineScope): Job? =
        if (mode == UpdateCheckMode.DISABLED) {
            null
        } else {
            scope.launch {
                while (true) {
                    checkDue()
                    delay(60_000)
                }
            }
        }

    /** One attempt per day plus jitter, including failures. Reserve the slot before network I/O. */
    suspend fun checkDue() {
        if (mode == UpdateCheckMode.DISABLED) return
        val now = inputs.wallMs()
        synchronized(this) {
            val next = nextAt
            if (next == null) {
                nextAt = now + jitter()
                return
            }
            if (now < next) return
            nextAt = now + DAY_MS + jitter()
        }
        try {
            val bytes = withContext(Dispatchers.IO) { download() }
            val update = requireNotNull(parse(bytes, repository))
            notice = update.takeIf { newer(it.version, currentVersion) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Transport/parser messages can include signed asset queries; never log them.
        }
    }

    private fun jitter(): Long {
        val bytes = inputs.randomBytes(4)
        val value = bytes.fold(0L) { total, byte -> (total shl 8) or (byte.toLong() and 255) }
        return value % JITTER_MS
    }

    internal fun download(): ByteArray {
        check(mode == UpdateCheckMode.ENABLED)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var uri = URI("$repository/releases/latest/download/neutrodyne-update.json")
        repeat(MAX_REDIRECTS + 1) { redirects ->
            require(allowed(uri, repository))
            check(System.nanoTime() < deadline)
            val response = fetch(uri, deadline)
            check(System.nanoTime() < deadline)
            require(response.body.size <= MAX_BYTES)
            if (response.status == 200) return response.body
            require(response.status in setOf(301, 302, 303, 307, 308) && redirects < MAX_REDIRECTS)
            uri = uri.resolve(requireNotNull(response.location))
        }
        error("Redirect limit")
    }

    companion object {
        const val MAX_BYTES = 65_536
        private const val MAX_REDIRECTS = 3
        private const val JITTER_MS = 3_600_000L
        private val VERSION = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

        internal fun allowed(
            uri: URI,
            repository: String,
        ): Boolean {
            if (uri.scheme != "https" || uri.port !in listOf(-1, 443) || uri.rawUserInfo != null ||
                uri.rawFragment != null
            ) {
                return false
            }
            if (uri.host == "release-assets.githubusercontent.com") return true
            if (uri.host != "github.com" || uri.rawQuery != null) return false
            val prefix = URI(repository).rawPath + "/releases/"
            val tail = uri.rawPath.removePrefix(prefix)
            return uri.rawPath.startsWith(prefix) &&
                (
                    tail == "latest/download/neutrodyne-update.json" ||
                        Regex("download/v[0-9]+\\.[0-9]+\\.[0-9]+/neutrodyne-update\\.json").matches(tail)
                )
        }

        internal fun parse(
            bytes: ByteArray,
            repository: String,
        ): UpdateNotice? {
            require(bytes.size <= MAX_BYTES)
            val root = ProtocolCodec.parse(RequestGuards.utf8(bytes)) as? JsonObject ?: return null
            if ((root["schema"] as? JsonPrimitive)?.intOrNull != 1) return null
            val version = root.text("versionName") ?: return null
            if (!VERSION.matches(version)) return null
            val release = "$repository/releases/tag/v$version"
            if (!sameLink(root.text("releaseUrl"), release)) return null
            val server = root["server"] as? JsonObject ?: return null
            val jar = server["jar"] as? JsonObject ?: return null
            val file = "neutrodyne-server-$version.jar"
            val jarUrl = "$repository/releases/download/v$version/$file"
            if (jar.text("file") != file || !sameLink(jar.text("url"), jarUrl)) return null
            if ((jar["size"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true) return null
            if (!Regex("[a-fA-F0-9]{64}").matches(jar.text("sha256").orEmpty())) return null
            val owner = URI(repository).path.split('/')[1].lowercase()
            val image = server.text("image") ?: return null
            if (!Regex(
                    "ghcr\\.io/${Regex.escape(owner)}/neutrodyne-server@sha256:[a-f0-9]{64}",
                ).matches(image)
            ) {
                return null
            }
            val minJava = (server["minJava"] as? JsonPrimitive)?.intOrNull ?: return null
            if (minJava < 21) return null
            return UpdateNotice(version, release, jarUrl, image, minJava)
        }

        private fun sameLink(
            raw: String?,
            expected: String,
        ): Boolean =
            runCatching {
                val uri = URI(raw ?: return false)
                val target = URI(expected)
                uri.scheme == "https" && uri.host == target.host && uri.port in listOf(-1, 443) &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.split('/').map { segment ->
                        val decoded = URI("https://github.com/$segment").path.removePrefix("/")
                        require(decoded !in listOf(".", "..") && '/' !in decoded && '\\' !in decoded)
                        decoded
                    } == target.path.split('/')
            }.getOrDefault(false)

        private fun newer(
            candidate: String,
            current: String,
        ): Boolean {
            if (!VERSION.matches(current)) return false
            val a = candidate.split('.').map(String::toInt)
            val b = current.split('.').map(String::toInt)
            return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
        }
    }
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.contentOrNull

/** Bounded subscriber cancels before buffering cap+1; the future deadline also covers a stalled body. */
internal class ManifestBody : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(items: List<ByteBuffer>) {
        if (items.sumOf { it.remaining().toLong() } + bytes.size() > ServerUpdateNotice.MAX_BYTES) {
            subscription.cancel()
            result.completeExceptionally(IllegalArgumentException("Manifest too large"))
            return
        }
        items.forEach { buffer ->
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            bytes.write(chunk)
        }
        subscription.request(1)
    }

    override fun onError(error: Throwable) {
        result.completeExceptionally(IllegalStateException("Manifest unavailable"))
    }

    override fun onComplete() {
        result.complete(bytes.toByteArray())
    }
}

internal fun fetchManifestResponse(
    uri: URI,
    deadline: Long,
): ManifestResponse {
    val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
    HttpClient
        .newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(
            Duration.ofSeconds(3),
        ).followRedirects(HttpClient.Redirect.NEVER)
        .build()
        .use { client ->
            val request =
                HttpRequest
                    .newBuilder(
                        uri,
                    ).timeout(
                        Duration.ofNanos(remaining),
                    ).header("User-Agent", "Neutrodyne-Sync/${ServerVersion.current()}")
                    .GET()
                    .build()
            val future = client.sendAsync(request, HttpResponse.BodyHandler { ManifestBody() })
            try {
                val response = future.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                return ManifestResponse(
                    response.statusCode(),
                    response.headers().firstValue("Location").orElse(null),
                    response.body(),
                )
            } finally {
                future.cancel(true)
            }
        }
}
