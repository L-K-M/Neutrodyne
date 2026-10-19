// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.getOrNull
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer

/** One rename commits the manifest and schedule together; no metadata sidecar can tear. */
@SingleIn(AppScope::class)
@Inject
internal class UpdateCheckStore(
    paths: StoragePaths,
    private val fileSystem: FileSystem,
    private val parser: UpdateParser,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : UpdateStore {
    private val root = paths.noBackupDir.toPath() / "updates"
    private val target = root / "last-check.json"
    private val staged = root / "last-check.json.tmp"
    private val mutex = Mutex()

    override suspend fun read(): Outcome<UpdateStoreSnapshot?, UpdateStoreError> =
        withContext(io) {
            mutex.withLock {
                try {
                    if (!fileSystem.exists(target)) return@withLock Outcome.Success(null)
                    val bytes =
                        fileSystem.source(target).use { source ->
                            val buffer = Buffer()
                            while (buffer.size <= MAX_CACHE_BYTES) {
                                if (source.read(buffer, MAX_CACHE_BYTES + 1L - buffer.size) == -1L) break
                            }
                            require(buffer.size <= MAX_CACHE_BYTES)
                            buffer.readByteArray()
                        }
                    val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
                    require(root.number("schema") == CACHE_SCHEMA)
                    val manifest =
                        root["manifest"]?.takeUnless { it == JsonNull }?.let {
                            parser.parse(it.toString().encodeToByteArray()).getOrNull()
                                ?: error("Invalid cached manifest")
                        }
                    val lastElement = root["lastCheckAtMs"]
                    val lastCheck =
                        if (lastElement == null || lastElement == JsonNull) {
                            null
                        } else {
                            root.number("lastCheckAtMs")
                        }
                    val started = root.number("firstStartedAtMs")
                    val due = root.number("nextDueAtMs")
                    val retry = root.number("retryAttempt")
                    require(retry <= MAX_RETRY_ATTEMPT)
                    require(manifest == null || lastCheck != null)
                    Outcome.Success(UpdateStoreSnapshot(manifest, lastCheck, started, due, retry.toInt()))
                } catch (_: CharacterCodingException) {
                    Outcome.Failure(UpdateStoreError.CORRUPT)
                } catch (_: IOException) {
                    Outcome.Failure(UpdateStoreError.IO)
                } catch (_: IllegalArgumentException) {
                    Outcome.Failure(UpdateStoreError.CORRUPT)
                } catch (_: IllegalStateException) {
                    Outcome.Failure(UpdateStoreError.CORRUPT)
                } catch (_: NoSuchElementException) {
                    Outcome.Failure(UpdateStoreError.CORRUPT)
                }
            }
        }

    override suspend fun write(snapshot: UpdateStoreSnapshot): Outcome<Unit, UpdateStoreError> =
        withContext(io) {
            mutex.withLock {
                val bytes =
                    buildJsonObject {
                        put("schema", CACHE_SCHEMA)
                        put("manifest", snapshot.manifest?.let(::manifestJson) ?: JsonNull)
                        put("lastCheckAtMs", snapshot.lastCheckAtMs?.let(::JsonPrimitive) ?: JsonNull)
                        put("firstStartedAtMs", snapshot.firstStartedAtMs)
                        put("nextDueAtMs", snapshot.nextDueAtMs)
                        put("retryAttempt", snapshot.retryAttempt)
                    }.toString().encodeToByteArray()
                if (bytes.size > MAX_CACHE_BYTES) return@withLock Outcome.Failure(UpdateStoreError.CORRUPT)

                try {
                    fileSystem.createDirectories(root)
                    fileSystem.sink(staged).buffer().use { sink ->
                        sink.write(bytes)
                        sink.flush()
                    }
                    fileSystem.atomicMove(staged, target)
                    Outcome.Success(Unit)
                } catch (_: IOException) {
                    Outcome.Failure(UpdateStoreError.IO)
                } finally {
                    // A failed cleanup must not turn an already committed rename into a reported failure.
                    try {
                        fileSystem.delete(staged, mustExist = false)
                    } catch (_: IOException) {
                    }
                }
            }
        }

    private companion object {
        const val CACHE_SCHEMA = 1L
        const val MAX_CACHE_BYTES = MAX_UPDATE_MANIFEST_BYTES * 2L
        const val MAX_RETRY_ATTEMPT = 6L
    }
}

/** Cache uses the wire schema too, so every loaded link goes through the same trust boundary. */
internal fun manifestJson(manifest: UpdateManifest): JsonObject =
    buildJsonObject {
        put("schema", 1)
        put("versionName", manifest.versionName)
        put("versionCode", manifest.versionCode)
        put("minSdk", manifest.minSdk)
        put("published", manifest.publishedAt)
        put("notes", manifest.notes)
        put("releaseUrl", manifest.releaseUrl)
        putJsonArray("apks") {
            manifest.apks.forEach { asset ->
                add(
                    buildJsonObject {
                        put("abi", asset.abi)
                        assetFields(asset.fileName, asset.url, asset.sizeBytes, asset.sha256)
                    },
                )
            }
        }
        putJsonArray("desktop") {
            manifest.desktop.forEach { asset ->
                add(
                    buildJsonObject {
                        put("os", asset.os.wire)
                        put("arch", asset.arch.wire)
                        put("kind", asset.kind.wire)
                        assetFields(asset.fileName, asset.url, asset.sizeBytes, asset.sha256)
                        asset.minOs?.let { put("minOs", it) }
                    },
                )
            }
        }
        manifest.server?.let { server ->
            putJsonObject("server") {
                putJsonObject("jar") {
                    assetFields(server.jarFileName, server.jarUrl, server.jarSizeBytes, server.jarSha256)
                }
                put("image", server.imageDigestRef)
                put("minJava", server.minJava)
            }
        }
    }

private fun JsonObjectBuilder.assetFields(
    file: String,
    url: String,
    size: Long,
    hash: String,
) {
    put("file", file)
    put("url", url)
    put("size", size)
    put("sha256", hash)
}
