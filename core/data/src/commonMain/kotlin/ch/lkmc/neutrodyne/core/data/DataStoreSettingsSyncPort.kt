// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.domain.LocalSyncedSetting
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
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
    private var recorderRef: suspend (SettingKey<*>, String) -> Unit = { _, _ -> }
    private val local = MutableSharedFlow<LocalSyncedSetting>(extraBufferCapacity = 16)
    override val localSyncedChanges: Flow<LocalSyncedSetting> = local

    override suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean {
        if (!key.synced) return false
        val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return false
        // A remote reset lands as JsonNull; decode() alone cannot tell it from an invalid value.
        if (element is JsonNull) {
            return suspendRunCatching { settingsStore.reset(key) }.isSuccess
        }
        val value = decode(key, element) ?: return false
        if (!AllSettingKeys.acceptsValue(key, value)) return false
        return suspendRunCatching { write(key, value) }.isSuccess
    }

    override fun registerRecorder(recorder: (suspend (key: SettingKey<*>, json: String) -> Unit)?) {
        recorderRef = recorder ?: { _, _ -> }
    }

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

    override suspend fun recordLocal(
        key: SettingKey<*>,
        value: Any?,
    ) {
        if (!key.synced) return
        val json = encode(key, value) ?: return
        recorderRef.invoke(key, json)
    }

    override fun emitLocal(
        key: SettingKey<*>,
        value: Any?,
        atMs: Long,
    ) {
        if (!key.synced) return
        val json = encode(key, value) ?: return
        local.tryEmit(LocalSyncedSetting(key, json, atMs))
    }

    override suspend fun syncedLiteralValues(): Map<SettingKey<*>, String> {
        val values = mutableMapOf<SettingKey<*>, String>()
        for (key in AllSettingKeys.list.filter { it.synced }) {
            val json = encode(key, settingsStore.get(key)) ?: continue
            values[key] = json
        }
        return values
    }

    /** Decodes [element] into [key]'s type; `null` when it is not a valid value for it. */
    private fun decode(
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

    @Suppress("UNCHECKED_CAST")
    private suspend fun write(
        key: SettingKey<*>,
        value: Any,
    ) {
        settingsStore.set(key as SettingKey<Any>, value)
    }
}
