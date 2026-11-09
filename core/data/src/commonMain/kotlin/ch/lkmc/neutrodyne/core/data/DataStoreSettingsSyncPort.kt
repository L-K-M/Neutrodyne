// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.domain.LocalSyncedSetting
import ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncedSettingSnapshot
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * [SettingsSyncPort] over the portable [SettingStore] (10 Settings capture). The recorder is
 * installed by `:sync:impl`'s `SettingsCapture` while linked; `DataStoreSettingsRepository`
 * calls [recordLocal] before a write and [emitLocal] after it, so a crash between the two leaves
 * a durable Room intent (01 Sync write ordering).
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
internal class DataStoreSettingsSyncPort(
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) : SettingsSyncPort {
    @Volatile
    private var recorderRef: suspend (SettingKey<*>, String) -> SettingWriteOrigin =
        { _, _ -> SettingWriteOrigin.Uncaptured }

    @Volatile
    private var materializedRef: suspend (SettingKey<*>, String) -> Unit = { _, _ -> }

    @Volatile
    private var committedRef: (suspend (SettingKey<*>, String, SyncedSettingSnapshot) -> SettingWriteOrigin)? = null
    private val local = MutableSharedFlow<LocalSyncedSetting>(extraBufferCapacity = 16)
    override val localSyncedChanges: Flow<LocalSyncedSetting> = local

    override suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean {
        if (!key.synced) return false
        return when (val decoded = decodeSyncedValue(key, json)) {
            null -> false
            SyncedSettingValue.Reset -> suspendRunCatching { settingsStore.reset(key) }.isSuccess
            is SyncedSettingValue.Set -> suspendRunCatching { write(key, decoded.value) }.isSuccess
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun applyRecorded(
        key: SettingKey<*>,
        json: String,
        origin: SettingWriteOrigin,
    ): Boolean {
        if (!key.synced) return false
        val decoded = decodeSyncedValue(key, json) ?: return false
        return suspendRunCatching {
            val receipt = origin.storedReceipt()
            when (decoded) {
                SyncedSettingValue.Reset -> {
                    settingsStore.resetWithReceipt(key, receipt)
                }

                is SyncedSettingValue.Set -> {
                    settingsStore.setWithReceipt(
                        key as SettingKey<Any>,
                        decoded.value,
                        receipt,
                    )
                }
            }
        }.isSuccess
    }

    override suspend fun recordCommitted(
        key: SettingKey<*>,
        json: String,
        before: SyncedSettingSnapshot,
    ): SettingWriteOrigin =
        (committedRef ?: throw okio.IOException("committed setting recorder unavailable"))(key, json, before)

    override fun registerCommittedRecorder(
        recorder: (suspend (key: SettingKey<*>, json: String, before: SyncedSettingSnapshot) -> SettingWriteOrigin)?,
    ) {
        committedRef = recorder
    }

    override fun registerRecorder(recorder: (suspend (key: SettingKey<*>, json: String) -> SettingWriteOrigin)?) {
        recorderRef = recorder ?: { _, _ -> SettingWriteOrigin.Uncaptured }
    }

    override fun registerMaterialized(listener: (suspend (SettingKey<*>, String) -> Unit)?) {
        materializedRef = listener ?: { _, _ -> }
    }

    override suspend fun currentJson(key: SettingKey<*>): String? = currentSnapshot(key)?.json

    @Suppress("UNCHECKED_CAST")
    override suspend fun currentSnapshot(key: SettingKey<*>): SyncedSettingSnapshot? =
        suspendRunCatching {
            val snapshot = settingsStore.readWithReceipt(key as SettingKey<Any>)
            val json = encode(key, snapshot.value) ?: return@suspendRunCatching null
            SyncedSettingSnapshot(json, decodeWriteOrigin(snapshot.receipt), snapshot.receipt)
        }.getOrNull()

    override fun encode(
        key: SettingKey<*>,
        value: Any?,
    ): String? {
        if (value == null) return JsonNull.toString()
        if (!AllSettingKeys.acceptsValue(key, value)) return null
        val element =
            when (key) {
                is SettingKey.Bool -> JsonPrimitive(value as Boolean)
                is SettingKey.Int32 -> JsonPrimitive(value as Int)
                is SettingKey.Int64 -> JsonPrimitive(value as Long)
                is SettingKey.Float32 -> JsonPrimitive(value as Float)
                is SettingKey.Text -> JsonPrimitive(value as String)
                is SettingKey.TextSet -> JsonArray((value as Set<*>).map { JsonPrimitive(it as String) })
                is SettingKey.Choice<*> -> JsonPrimitive((value as Enum<*>).name)
            }
        return element.toString()
    }

    override fun canonicalJson(
        key: SettingKey<*>,
        json: String,
    ): String? =
        when (val decoded = decodeSyncedValue(key, json)) {
            null -> null
            SyncedSettingValue.Reset -> "null"
            is SyncedSettingValue.Set -> encode(key, decoded.value)
        }

    override suspend fun recordLocal(
        key: SettingKey<*>,
        value: Any?,
    ): SettingWriteOrigin {
        if (!key.synced) return SettingWriteOrigin.Uncaptured
        val json = encode(key, value) ?: return SettingWriteOrigin.Uncaptured
        return recorderRef.invoke(key, json)
    }

    override suspend fun emitLocal(
        key: SettingKey<*>,
        value: Any?,
        atMs: Long,
    ) {
        if (!key.synced) return
        val json = encode(key, value) ?: return
        // The value has already committed. Room bookkeeping can retry from its durable intent;
        // it must not turn a successful settings write into an uncaught UI failure.
        suspendRunCatching { materializedRef(key, json) }.onFailure { failure ->
            Log.w("Settings", failure) { "materialization bookkeeping failed for '${key.name}'" }
        }
        local.tryEmit(LocalSyncedSetting(key, json, atMs))
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun write(
        key: SettingKey<*>,
        value: Any,
    ) {
        settingsStore.set(key as SettingKey<Any>, value)
    }
}

/**
 * A decoded synced setting value (05 Rules 4): a reset (`"null"`) or a validated value.
 * [decodeSyncedValue] returns null for malformed JSON, a wrong shape or a value the key
 * rejects.
 */
internal sealed interface SyncedSettingValue {
    data object Reset : SyncedSettingValue

    data class Set(
        val value: Any,
    ) : SyncedSettingValue
}

/**
 * Decodes [json] into [key]'s type and validates it (05 Rules 4): the stored type must match
 * the key's subclass and [AllSettingKeys.acceptsValue] must accept it. Returns null when the
 * value is invalid. This is the shared precondition of [DataStoreSettingsSyncPort.applyRemote]
 * and a linked Merge restore's settings step, so both skip exactly the same values instead of
 * a restore treating the port's rejection as a storage failure.
 */
internal fun decodeSyncedValue(
    key: SettingKey<*>,
    json: String,
): SyncedSettingValue? {
    val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
    // A remote reset lands as JsonNull; the value decode alone cannot tell it from an invalid value.
    if (element is JsonNull) return SyncedSettingValue.Reset
    val value = decodeSettingValue(key, element) ?: return null
    return if (AllSettingKeys.acceptsValue(key, value)) SyncedSettingValue.Set(value) else null
}

/** Decodes [element] into [key]'s type; null when it is not a valid value for it. */
private fun decodeSettingValue(
    key: SettingKey<*>,
    element: kotlinx.serialization.json.JsonElement,
): Any? =
    when (key) {
        is SettingKey.Bool -> {
            (element as? JsonPrimitive)?.booleanOrNull
        }

        is SettingKey.Int32 -> {
            (element as? JsonPrimitive)?.intOrNull
        }

        is SettingKey.Int64 -> {
            (element as? JsonPrimitive)?.longOrNull
        }

        is SettingKey.Float32 -> {
            (element as? JsonPrimitive)?.floatOrNull
        }

        is SettingKey.Text -> {
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        is SettingKey.TextSet -> {
            (element as? JsonArray)
                ?.takeIf { entries -> entries.all { it is JsonPrimitive && it.isString } }
                ?.map { (it as JsonPrimitive).content }
                ?.toSet()
        }

        is SettingKey.Choice<*> -> {
            val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            name?.let(key::fromStored)
        }
    }

// The low-level store treats these as opaque receipts; sync types stay in this adapter.
internal fun SettingWriteOrigin.storedReceipt(): String =
    when (this) {
        SettingWriteOrigin.Uncaptured -> "$UNCAPTURED_RECEIPT:${Uuid.random()}"
        is SettingWriteOrigin.Captured -> "$CAPTURED_RECEIPT_PREFIX$token"
    }

private fun decodeWriteOrigin(receipt: String?): SettingWriteOrigin? =
    when {
        receipt == null -> {
            null
        }

        receipt.startsWith(CAPTURED_RECEIPT_PREFIX) && receipt.length > CAPTURED_RECEIPT_PREFIX.length -> {
            SettingWriteOrigin.Captured(receipt.removePrefix(CAPTURED_RECEIPT_PREFIX))
        }

        else -> {
            SettingWriteOrigin.Uncaptured
        }
    }

private const val UNCAPTURED_RECEIPT = "uncaptured"
private const val CAPTURED_RECEIPT_PREFIX = "captured:"
