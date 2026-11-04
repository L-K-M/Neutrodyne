// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.fetch.FeedHttpHeaders
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.feeds.directory.DirectoryParsers
import dev.zacsweers.metro.Inject
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/** All provider bodies are bounded before JSON allocation. Errors deliberately omit raw details. */
@Inject
internal class DirectoryHttp(
    clients: NeutrodyneHttpClients,
) {
    private val client = clients.client(HttpClientKind.API)

    internal class Reply(
        val code: Int,
        val text: String,
        val dateMs: Long?,
    )

    suspend fun get(
        url: String,
        parameters: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Outcome<Reply, ProviderStatus> =
        try {
            withTimeoutOrNull(DEADLINE_MS) {
                var result: Outcome<Reply, ProviderStatus> = Outcome.Failure(ProviderStatus.Failed(null))
                client
                    .prepareGet(url) {
                        url { parameters.forEach { (key, value) -> this.parameters.append(key, value) } }
                        headers.forEach { (key, value) -> header(key, value) }
                        val origin = Url(url)
                        header(
                            "X-Neutrodyne-Directory-Origin",
                            "${origin.protocol.name}://${origin.host}:${origin.port}",
                        )
                    }.execute { response ->
                        val channel = response.bodyAsChannel()
                        try {
                            if (response.status.value !in 200..299) {
                                result =
                                    Outcome.Success(
                                        Reply(
                                            response.status.value,
                                            "",
                                            FeedHttpHeaders.serverDateMs(response.headers[HttpHeaders.Date]),
                                        ),
                                    )
                                return@execute
                            }
                            val buffer = Buffer()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val remaining = DirectoryParsers.MAX_JSON_BYTES + 1 - buffer.size.toInt()
                                val read = channel.readAvailable(chunk, 0, minOf(chunk.size, remaining))
                                if (read == -1) break
                                if (read == 0) {
                                    channel.awaitContent()
                                    continue
                                }
                                buffer.write(chunk, 0, read)
                                if (buffer.size > DirectoryParsers.MAX_JSON_BYTES) return@execute
                            }
                            val reply =
                                Reply(
                                    response.status.value,
                                    buffer.readUtf8(),
                                    FeedHttpHeaders.serverDateMs(response.headers[HttpHeaders.Date]),
                                )
                            result = Outcome.Success(reply)
                        } finally {
                            channel.cancel(null)
                        }
                    }
                result
            } ?: Outcome.Failure(ProviderStatus.Failed(NetError.Timeout))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Classifier messages may contain request URLs; retain only a stable failure category.
            Outcome.Failure(ProviderStatus.Failed(null))
        }

    companion object {
        const val DEADLINE_MS = 8_000L
    }
}
