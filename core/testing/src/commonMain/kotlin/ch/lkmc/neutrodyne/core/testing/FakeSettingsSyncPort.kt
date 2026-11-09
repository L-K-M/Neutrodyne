// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.LocalSyncedSetting
import ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncedSettingSnapshot
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlin.uuid.Uuid

/** A [SettingsSyncPort] fixture: recordings in, scripted remote applies, no DataStore. */
class FakeSettingsSyncPort : SettingsSyncPort {
    private var recorderRef: (suspend (key: SettingKey<*>, json: String) -> SettingWriteOrigin)? = null
    private var committedRef: (
        suspend (key: SettingKey<*>, json: String, before: SyncedSettingSnapshot) -> SettingWriteOrigin
    )? = null
    private val admitted = mutableMapOf<String, SettingWriteOrigin>()
    private val origins = mutableMapOf<String, SettingWriteOrigin>()
    private val receiptIds = mutableMapOf<String, String>()
    private var materializedRef: (suspend (key: SettingKey<*>, json: String) -> Unit)? = null
    private val locals = MutableSharedFlow<LocalSyncedSetting>(extraBufferCapacity = 16)

    override val localSyncedChanges: Flow<LocalSyncedSetting> = locals

    val recordings = mutableListOf<Pair<SettingKey<*>, String>>()
    var remoteResult: Boolean = true
    val applied = mutableListOf<Pair<SettingKey<*>, String>>()

    /** What [currentJson] reports per key — the fixture's stand-in for DataStore contents. */
    private val storedValues = mutableMapOf<String, String>()
    val current: MutableMap<String, String> =
        object : MutableMap<String, String> by storedValues {
            override fun put(
                key: String,
                value: String,
            ): String? {
                origins.remove(key)
                receiptIds.remove(key)
                return storedValues.put(key, value)
            }

            override fun putAll(from: Map<out String, String>) {
                from.forEach { (key, value) -> put(key, value) }
            }

            override fun remove(key: String): String? {
                origins.remove(key)
                receiptIds.remove(key)
                return storedValues.remove(key)
            }

            override fun clear() {
                origins.clear()
                storedValues.clear()
            }
        }
    val materialized = mutableListOf<Pair<SettingKey<*>, String>>()

    override suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean {
        applied += key to json
        if (remoteResult) current[key.name] = if (json == "null") encode(key, key.default)!! else json
        return remoteResult
    }

    override suspend fun applyRecorded(
        key: SettingKey<*>,
        json: String,
        origin: SettingWriteOrigin,
    ): Boolean {
        if (key.name !in current) current[key.name] = encode(key, key.default)!!
        if (!applyRemote(key, json)) return false
        origins[key.name] = origin
        receiptIds[key.name] = Uuid.random().toString()
        return true
    }

    override suspend fun recordCommitted(
        key: SettingKey<*>,
        json: String,
        before: SyncedSettingSnapshot,
    ): SettingWriteOrigin = committedRef?.invoke(key, json, before) ?: SettingWriteOrigin.Uncaptured

    override fun registerCommittedRecorder(
        recorder: (suspend (key: SettingKey<*>, json: String, before: SyncedSettingSnapshot) -> SettingWriteOrigin)?,
    ) {
        committedRef = recorder
    }

    override fun registerRecorder(recorder: (suspend (key: SettingKey<*>, json: String) -> SettingWriteOrigin)?) {
        recorderRef = recorder
    }

    override fun registerMaterialized(listener: (suspend (SettingKey<*>, String) -> Unit)?) {
        materializedRef = listener
    }

    override suspend fun currentJson(key: SettingKey<*>): String? = current[key.name]

    override suspend fun currentSnapshot(key: SettingKey<*>): SyncedSettingSnapshot? =
        current[key.name]?.let { SyncedSettingSnapshot(it, origins[key.name], receiptIds[key.name]) }

    /** The repository's atomic store commit, independently of its later notification. */
    fun commitLocal(
        key: SettingKey<*>,
        value: Any?,
    ) {
        val origin = admitted.remove(key.name) ?: SettingWriteOrigin.Uncaptured
        current[key.name] = encode(key, value ?: key.default) ?: error("invalid fixture setting")
        origins[key.name] = origin
        receiptIds[key.name] = Uuid.random().toString()
    }

    override fun encode(
        key: SettingKey<*>,
        value: Any?,
    ): String? =
        when (value) {
            null -> {
                "null"
            }

            is Boolean -> {
                JsonPrimitive(value).toString()
            }

            is Int -> {
                JsonPrimitive(value).toString()
            }

            is Long -> {
                JsonPrimitive(value).toString()
            }

            is Float -> {
                JsonPrimitive(value).toString()
            }

            is Double -> {
                JsonPrimitive(value).toString()
            }

            is String -> {
                JsonPrimitive(value).toString()
            }

            is Set<*> -> {
                JsonArray(value.sortedBy { it.toString() }.map { JsonPrimitive(it.toString()) }).toString()
            }

            is Enum<*> -> {
                JsonPrimitive(value.name).toString()
            }

            else -> {
                null
            }
        }

    override fun canonicalJson(
        key: SettingKey<*>,
        json: String,
    ): String? {
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        if (parsed is JsonNull) return "null"
        // This fixture keeps scripted values, while matching the real adapter's Float32 shape.
        return if (key is SettingKey.Float32) {
            (parsed as? JsonPrimitive)?.floatOrNull?.let { encode(key, it) }
        } else {
            parsed.toString()
        }
    }

    override suspend fun recordLocal(
        key: SettingKey<*>,
        value: Any?,
    ): SettingWriteOrigin {
        if (!key.synced) return SettingWriteOrigin.Uncaptured
        // A real store always reads the default of a never-written key. Populate only the
        // admitted key, leaving unconfigured keys unavailable to focused snapshot fixtures.
        if (key.name !in current) current[key.name] = encode(key, key.default)!!
        val json = encode(key, value) ?: return SettingWriteOrigin.Uncaptured
        recordings += key to json
        val origin = recorderRef?.invoke(key, json) ?: SettingWriteOrigin.Uncaptured
        admitted[key.name] = origin
        return origin
    }

    override suspend fun emitLocal(
        key: SettingKey<*>,
        value: Any?,
        atMs: Long,
    ) {
        val json = encode(key, value) ?: return
        materialized += key to json
        materializedRef?.invoke(key, json)
        locals.tryEmit(LocalSyncedSetting(key, json, atMs))
    }
}
