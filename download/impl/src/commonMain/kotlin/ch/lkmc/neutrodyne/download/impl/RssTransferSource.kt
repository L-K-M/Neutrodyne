// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceQueries
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import io.ktor.client.plugins.SendCountExceedException
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException

/** One HTTP exchange (including cached-URL fallback); the engine bounds retries and restarts. */
internal class RssTransferSource(
    clients: NeutrodyneHttpClients,
    private val credentials: CredentialLookup,
    private val sources: DownloadSourceQueries,
    private val roots: StorageRoots,
    private val files: DownloadFileSystem,
    private val verification: DownloadFiles,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val platform: PlatformKind,
) : TransferSource {
    override val kind = SourceKind.RSS_ENCLOSURE
    private val client = clients.client(HttpClientKind.DOWNLOAD)

    override suspend fun prepare(
        row: DownloadEntity,
        part: PartFile,
        attempt: TransferAttempt,
    ): Prepared {
        credentials.awaitLoaded()
        val source =
            sources.downloadSources(listOf(row.episodeId)).singleOrNull()
                ?: return Prepared.Fail(DownloadError.HTTP_NOT_FOUND, null)
        if (source.sourceType != SourceType.RSS) return Prepared.Fail(DownloadError.UNSUPPORTED_STREAM, null)
        val media = select(source) ?: return Prepared.Fail(DownloadError.UNSUPPORTED_STREAM, null)
        var failureRootId = part.rootId
        try {
            verification.adopt(row, source, media.integrity)?.let { return it }
            val rootId = roots.current()
            failureRootId = rootId
            if (roots.resolve(rootId) == null) return storagePrepared(StorageFailure.UNAVAILABLE)
            var offset = if (roots.resolve(part.rootId) == null) 0 else part.length()
            if (row.lastStopReason == DownloadStopReasons.PROCESS_DEATH && offset > 0) {
                offset -= minOf(TORN_TAIL_BYTES, offset)
                part.truncate(offset)
            }
            val validator = reliableValidator(row.etag, row.lastModified)
            if (offset > 0 && validator == null) {
                part.truncate(0)
                offset = 0
            }
            val knownSize = row.totalBytes ?: media.length
            val limit = roots.maximumFileBytes(rootId)
            if (knownSize != null && limit != null &&
                knownSize > limit
            ) {
                return storagePrepared(StorageFailure.TOO_LARGE)
            }
            val size = (knownSize ?: row.estimatedBytes ?: UNKNOWN_SIZE_BYTES).coerceAtLeast(0)
            val required =
                (
                    size.coerceAtMost(
                        Long.MAX_VALUE - HEADROOM_BYTES,
                    ) + HEADROOM_BYTES - offset
                ).coerceAtLeast(0)
            val moving = rootId != part.rootId && offset > 0
            roots.ensureSpace(rootId, if (moving) required + offset else required)?.let { return storagePrepared(it) }
            val temp = DownloadPaths.part(row.episodeId)
            if (moving) {
                copyPart(part, rootId, temp, offset)
            } else if (rootId != part.rootId) {
                val target = files.openPart(rootId, temp)
                try {
                    target.truncate(0)
                } finally {
                    target.close()
                }
            }
            return Prepared.Go(
                TransferPlan(
                    row.episodeId,
                    media.url,
                    offset,
                    if (offset > 0) row.totalBytes else null,
                    if (offset > 0) validator else null,
                    mimeType = media.mime,
                    integrity = media.integrity,
                ),
                rootId,
                temp,
                media.url,
            )
        } catch (error: IOException) {
            return storagePrepared(roots.classify(error, failureRootId))
        }
    }

    override suspend fun transfer(
        plan: TransferPlan,
        part: PartFile,
        sink: ProgressSink,
        attempt: TransferAttempt,
    ): TransferResult {
        if (!network.status.value.isConnected) return networkWait()
        require(
            plan.offset >= 0 &&
                (
                    plan.offset == 0L || reliableValidator(plan.ifRange, null) != null ||
                        httpDateMillis(plan.ifRange.orEmpty()) != null
                ),
        )
        credentials.awaitLoaded()
        var retryUrl: String? = null
        try {
            suspend fun request(url: String): TransferResult =
                client
                    .prepareGet(url) {
                        if (plan.offset > 0) {
                            header("Range", "bytes=${plan.offset}-")
                            header("If-Range", plan.ifRange)
                        }
                    }.execute { response ->
                        // Only a successful media response pins a rendition for the next in-runner retry.
                        if (response.status.value in setOf(200, 206)) {
                            retryUrl =
                                response.call.request.url
                                    .toString()
                        }
                        response(plan, part, sink, response)
                    }
            var result = request(plan.url)
            if (plan.url != plan.originalUrl && result is TransferResult.Permanent &&
                result.httpStatus in setOf(401, 403, 404, 410)
            ) {
                retryUrl = plan.originalUrl
                result = request(plan.originalUrl)
            }
            return if (result is TransferResult.Transient) result.copy(retryUrl = retryUrl) else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SendCountExceedException) {
            return TransferResult.Permanent(DownloadError.HTTP_CLIENT, null)
        } catch (failure: StorageIo) {
            return storageResult(roots.classify(checkNotNull(failure.cause), part.rootId))
        } catch (_: IOException) {
            return if (!network.status.value.isConnected) {
                networkWait()
            } else {
                TransferResult.Transient(DownloadError.NETWORK_IO, null, null, retryUrl)
            }
        }
    }

    private suspend fun response(
        plan: TransferPlan,
        part: PartFile,
        sink: ProgressSink,
        response: HttpResponse,
    ): TransferResult {
        val status = response.status.value
        val headers = response.headers
        val retry = retryAfter(headers["Retry-After"])
        if (status == 429 || status == 408 || status in 500..599) {
            val error = if (status == 429) DownloadError.HTTP_RATE_LIMITED else DownloadError.HTTP_SERVER
            return if (retry != null &&
                retry > IN_RUNNER_RETRY_MS
            ) {
                TransferResult.Wait(
                    WaitReason.BACKOFF,
                    error,
                    clock.now() + retry,
                    countsAsAttempt = true,
                    httpStatus = status,
                )
            } else {
                TransferResult.Transient(error, status, retry)
            }
        }
        if (status == 416) {
            val reportedTotal = headers["Content-Range"]?.removePrefix("bytes */")?.toLongOrNull()
            if (plan.expectedTotal == null || plan.offset != plan.expectedTotal ||
                (reportedTotal != null && reportedTotal != plan.expectedTotal)
            ) {
                return TransferResult.Restart("range_unsatisfied")
            }
            val verified = disk { verification.verify(part, plan.expectedTotal, plan.mimeType, plan.integrity) }
            if (verified is TransferResult.Complete) {
                sink.started(
                    TransferMetadata(plan.offset, plan.expectedTotal, plan.mimeType, null, null),
                )
            }
            return verified
        }
        if (status !in setOf(200, 206)) {
            return TransferResult.Permanent(
                when (status) {
                    401, 403 -> DownloadError.HTTP_AUTH
                    404 -> DownloadError.HTTP_NOT_FOUND
                    410 -> DownloadError.HTTP_GONE
                    else -> DownloadError.HTTP_CLIENT
                },
                status,
            )
        }

        val contentLength = headers["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0 }
        var offset = plan.offset
        val total: Long?
        var responseBytes: Long? = contentLength
        if (status == 206) {
            val match =
                RANGE.matchEntire(headers["Content-Range"].orEmpty())
                    ?: return TransferResult.Restart("invalid_content_range")
            val start = match.groupValues[1].toLongOrNull() ?: return TransferResult.Restart("invalid_content_range")
            val end = match.groupValues[2].toLongOrNull() ?: return TransferResult.Restart("invalid_content_range")
            total = match.groupValues[3].toLongOrNull()
            if (plan.ifRange == null || offset == 0L || start != offset || end < start || end == Long.MAX_VALUE ||
                (total != null && end >= total) || (plan.expectedTotal != null && total != plan.expectedTotal) ||
                (contentLength != null && contentLength != end - start + 1)
            ) {
                return TransferResult.Restart("range_mismatch")
            }
            val returnedValidator = if (plan.ifRange.startsWith('"')) headers["ETag"] else headers["Last-Modified"]
            if (returnedValidator != null &&
                returnedValidator != plan.ifRange
            ) {
                return TransferResult.Restart("validator_changed")
            }
            responseBytes = end - start + 1
        } else {
            if (offset > 0) disk { part.truncate(0) }
            offset = 0
            total = contentLength
        }
        if (disk { part.length() } != offset) return TransferResult.Restart("part_length_changed")
        val mime = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() ?: plan.mimeType
        val storedValidator = plan.ifRange.takeIf { status == 206 && headers["ETag"] == null }
        val etag =
            headers["ETag"]?.takeIf { reliableValidator(it, null) != null }
                ?: storedValidator?.takeIf { reliableValidator(it, null) != null }
        val lastModified =
            if (headers["ETag"] == null) {
                headers["Last-Modified"]?.takeIf {
                    val modified = httpDateMillis(it)
                    val date = headers["Date"]?.let(::httpDateMillis)
                    modified != null && date != null && date >= modified && date - modified >= DATE_VALIDATOR_AGE_MS
                }
            } else {
                null
            } ?: storedValidator?.takeIf { httpDateMillis(it) != null }
        val channel = response.bodyAsChannel()
        val head = ByteArray(if (offset == 0L) MediaSniffer.HEAD_BYTES else 0)
        var headSize = 0
        while (headSize < head.size) {
            val count = channel.readAvailable(head, headSize, head.size - headSize)
            if (count == -1) break
            headSize += count
        }
        if (contentLength != null && headSize < minOf(head.size.toLong(), contentLength)) {
            return TransferResult.Transient(DownloadError.NETWORK_IO, null, null)
        }
        if (offset == 0L) {
            when (MediaSniffer.classify(head.copyOf(headSize), mime)) {
                MediaKind.HLS -> return TransferResult.Permanent(DownloadError.UNSUPPORTED_STREAM, null)

                MediaKind.NOT_MEDIA -> return if (platform == PlatformKind.ANDROID &&
                    !network.status.value.isValidated
                ) {
                    networkWait()
                } else {
                    TransferResult.Permanent(DownloadError.NOT_MEDIA, null)
                }

                else -> Unit
            }
        }
        sink.started(TransferMetadata(offset, total, mime, etag, lastModified))
        disk { part.append(head, headSize) }
        var received = headSize.toLong()
        sink.bytes(offset + received, total)
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            disk { part.append(buffer, count) }
            received += count
            sink.bytes(offset + received, total)
        }
        disk { part.sync() }
        if (responseBytes != null &&
            received != responseBytes
        ) {
            return TransferResult.Transient(DownloadError.NETWORK_IO, null, null)
        }
        val verified = disk { verification.verify(part, total, mime, plan.integrity) }
        return if (verified is TransferResult.Complete) {
            verified.copy(
                etag = etag,
                lastModified = lastModified,
            )
        } else {
            verified
        }
    }

    private suspend fun copyPart(
        part: PartFile,
        rootId: String,
        temp: String,
        length: Long,
    ) {
        val target = files.openPart(rootId, temp)
        try {
            target.truncate(0)
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var offset = 0L
            while (offset < length) {
                val count = part.read(offset, buffer, minOf(buffer.size.toLong(), length - offset).toInt())
                if (count <= 0) throw IOException("Partial copy ended early")
                target.append(buffer, count)
                offset += count
            }
            target.sync()
            if (target.length() != length) throw IOException("Partial copy length mismatch")
        } finally {
            target.close()
        }
        part.delete()
    }

    private fun select(source: DownloadSourceRow): SelectedMedia? {
        val alternates =
            source.audioAlternates.sortedWith(
                compareByDescending<EpisodeAltEnclosureEntity> {
                    it.isDefault
                }.thenBy { it.ordinal },
            )
        if (source.isVideo) {
            for (alternate in alternates.filter { it.type.startsWith("audio/", true) }) {
                val json =
                    try {
                        Json.parseToJsonElement(alternate.sourcesJson) as? JsonArray
                    } catch (
                        _: SerializationException,
                    ) {
                        null
                    }
                val url =
                    json?.firstNotNullOfOrNull { element ->
                        val uri = (element as? JsonObject)?.get("uri")?.let { (it as? JsonPrimitive)?.content }
                        uri?.takeIf(::httpUrl)
                    } ?: continue
                return SelectedMedia(
                    url,
                    alternate.type,
                    alternate.length,
                    alternate.integrityValue.takeIf {
                        alternate.integrityType ==
                            "sri"
                    },
                )
            }
        }
        val url = source.enclosureUrl?.takeIf(::httpUrl) ?: return null
        return SelectedMedia(url, source.enclosureType, source.enclosureLength, null)
    }

    private fun httpUrl(value: String): Boolean =
        try {
            val url = Url(value)
            url.protocol.name in setOf("http", "https") && url.host.isNotEmpty() && url.user == null &&
                url.password == null
        } catch (_: URLParserException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun reliableValidator(
        etag: String?,
        lastModified: String?,
    ): String? =
        etag?.takeIf {
            it.length >= 2 && it.startsWith('"') && it.endsWith('"') &&
                it.substring(1, it.lastIndex).all { char ->
                    char == '!' || char.code in 0x23..0x7e || char.code in 0x80..0xff
                }
        }
            ?: lastModified?.takeIf { etag == null && httpDateMillis(it) != null }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            val seconds = value.toLongOrNull() ?: return MAX_BACKOFF_MS
            return seconds.coerceAtMost(MAX_BACKOFF_MS / 1000) * 1000
        }
        return httpDateMillis(value)?.let { (it - clock.now()).coerceIn(0, MAX_BACKOFF_MS) }
    }

    private fun networkWait() = TransferResult.Wait(WaitReason.NETWORK, null, clock.now() + NETWORK_WAIT_MS)

    private fun storageResult(failure: StorageFailure): TransferResult =
        when (failure) {
            StorageFailure.TOO_LARGE -> {
                TransferResult.Permanent(DownloadError.STORAGE_UNAVAILABLE, null)
            }

            StorageFailure.IO -> {
                TransferResult.Transient(DownloadError.NETWORK_IO, null, null)
            }

            else -> {
                TransferResult.Wait(
                    WaitReason.STORAGE,
                    if (failure ==
                        StorageFailure.FULL
                    ) {
                        DownloadError.STORAGE_FULL
                    } else {
                        DownloadError.STORAGE_UNAVAILABLE
                    },
                    clock.now() + STORAGE_WAIT_MS,
                )
            }
        }

    private fun storagePrepared(failure: StorageFailure): Prepared =
        when (val result = storageResult(failure)) {
            is TransferResult.Permanent -> Prepared.Fail(result.error, null)
            is TransferResult.Wait -> Prepared.Wait(result.reason, result.error, result.untilMs, false)
            else -> Prepared.Wait(WaitReason.BACKOFF, DownloadError.NETWORK_IO, clock.now() + IN_RUNNER_RETRY_MS, true)
        }

    private suspend fun <T> disk(block: suspend () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw StorageIo(error)
        }

    private class StorageIo(
        cause: IOException,
    ) : Exception(cause)

    private data class SelectedMedia(
        val url: String,
        val mime: String?,
        val length: Long?,
        val integrity: String?,
    )

    private companion object {
        const val TORN_TAIL_BYTES = 64L * 1024
        const val UNKNOWN_SIZE_BYTES = 150L * 1024 * 1024
        const val HEADROOM_BYTES = 200L * 1024 * 1024
        const val DATE_VALIDATOR_AGE_MS = 60_000L
        const val IN_RUNNER_RETRY_MS = 30_000L
        const val NETWORK_WAIT_MS = 60_000L
        const val STORAGE_WAIT_MS = 30L * 60 * 1000
        const val MAX_BACKOFF_MS = 6L * 60 * 60 * 1000
        val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
    }
}
