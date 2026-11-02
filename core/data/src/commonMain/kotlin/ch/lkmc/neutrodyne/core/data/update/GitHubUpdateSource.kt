// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import dev.zacsweers.metro.Inject
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import okio.Buffer

@Inject
internal class GitHubUpdateSource(
    clients: NeutrodyneHttpClients,
    private val build: BuildInfo,
    private val parser: UpdateParser,
) : UpdateSource {
    private val client = clients.client(HttpClientKind.API)

    override suspend fun fetch(): Outcome<UpdateManifest, UpdateCheckError> {
        val expected = "${build.repoUrl.removeSuffix("/")}/releases/latest/download/neutrodyne-update.json"
        if (build.updateManifestUrl != expected || !expected.startsWith("https://github.com/")) {
            return Outcome.Failure(UpdateCheckError.MANIFEST_INVALID)
        }

        return try {
            client.prepareGet(build.updateManifestUrl).execute { response ->
                when (response.status.value) {
                    403, 429 -> return@execute Outcome.Failure(UpdateCheckError.RATE_LIMITED)
                    200 -> Unit
                    else -> return@execute Outcome.Failure(UpdateCheckError.MANIFEST_INVALID)
                }
                if ((response.contentLength() ?: 0) > MAX_UPDATE_MANIFEST_BYTES) {
                    return@execute Outcome.Failure(UpdateCheckError.MANIFEST_INVALID)
                }

                val channel = response.bodyAsChannel()
                val buffer = Buffer()
                val chunk = ByteArray(READ_CHUNK_BYTES)
                while (true) {
                    val count =
                        channel.readAvailable(
                            chunk,
                            0,
                            minOf(chunk.size.toLong(), MAX_UPDATE_MANIFEST_BYTES + 1L - buffer.size).toInt(),
                        )
                    if (count == -1) break
                    if (count == 0) continue
                    buffer.write(chunk, 0, count)
                    if (buffer.size > MAX_UPDATE_MANIFEST_BYTES) {
                        channel.cancel(null)
                        return@execute Outcome.Failure(UpdateCheckError.MANIFEST_INVALID)
                    }
                }
                parser.parse(buffer.readByteArray())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the exception: it can contain a signed release CDN URL.
            Outcome.Failure(UpdateCheckError.NETWORK)
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 8192
    }
}
