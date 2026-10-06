// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [SettingsRepository] (09 `:core:testing` inventory): one value map per
 * [SettingsFile], the `file`/`synced` flags of each key honoured exactly like the real
 * implementation, a [calls] log and a [failNextSet] mutator. Never touches a disk.
 */
class FakeSettingsRepository : SettingsRepository {
    private val values = MutableStateFlow<Map<SettingsFile, Map<String, Any>>>(emptyMap())
    private val loggedCalls = mutableListOf<String>()

    /** Every call so far, e.g. `set(playback.skip_back_ms)`. */
    val calls: List<String> get() = loggedCalls.toList()

    /** Returned by the next [set] instead of writing; one-shot, cleared after use. */
    var failNextSet: SettingsError? = null

    override fun <T : Any> observe(key: SettingKey<T>): Flow<T> =
        values.map { stored -> stored.storedValue(key) }.distinctUntilChanged()

    override suspend fun <T : Any> get(key: SettingKey<T>): T {
        loggedCalls += "get(${key.name})"
        return values.value.storedValue(key)
    }

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ): Outcome<Unit, SettingsError> {
        loggedCalls += "set(${key.name})"
        if (rejectsValue(key, value)) {
            return Outcome.Failure(SettingsError.OutOfRange(key.name))
        }

        failNextSet?.let {
            failNextSet = null
            return Outcome.Failure(it)
        }

        values.update { current ->
            current + (key.file to (current[key.file].orEmpty() + (key.name to value)))
        }
        return Outcome.Success(Unit)
    }

    override suspend fun reset(key: SettingKey<*>) {
        loggedCalls += "reset(${key.name})"
        values.update { current ->
            current + (key.file to (current[key.file].orEmpty() - key.name))
        }
    }

    override fun observePortableSnapshot(): Flow<Map<String, Any>> {
        val portableKeys = AllSettingKeys.list.filter { it.file == SettingsFile.PORTABLE }
        if (portableKeys.isEmpty()) return flowOf(emptyMap())

        val entries: List<Flow<Pair<String, Any>>> =
            portableKeys.map { key ->
                observe(key).map { value -> key.name to value }
            }
        return combine(entries) { pairs -> pairs.toMap() }.distinctUntilChanged()
    }

    /** The key's value: its file's stored entry, or the key's default when never written. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> Map<SettingsFile, Map<String, Any>>.storedValue(key: SettingKey<T>): T =
        this[key.file]?.get(key.name) as? T ?: key.default

    /** A `Choice` outside its declared values is the one value a key itself forbids. */
    private fun rejectsValue(
        key: SettingKey<*>,
        value: Any,
    ): Boolean = key is SettingKey.Choice<*> && key.values.none { it == value }
}
