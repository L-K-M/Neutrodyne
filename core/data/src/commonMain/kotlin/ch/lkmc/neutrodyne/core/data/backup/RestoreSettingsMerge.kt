// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import dev.zacsweers.metro.Inject
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 05's "Settings are applied through `SettingsRepository` …" restore step (M3 B12b): the
 * archive's `settings.json` values land on the [AllSettingKeys] PORTABLE whitelist only.
 * Unknown names, DEVICE-scoped keys, a declared `type` that does not match the key's
 * `SettingKey` subclass, wrong-shaped JSON and values the key rejects are skipped and
 * counted — a hostile or newer-format archive can never mint a setting. A real store write
 * failure ([SettingsError.WriteFailed]) is the restore's explicit [BackupError.Storage]
 * failure; the durable request survives for a re-run.
 *
 * Linked restores never reach here: `RestoreRunner` rejects them before any write (the
 * linked skip rule for `sync.server_url`/`sync.username` is MS2's to apply).
 */
@Inject
internal class RestoreSettingsMerge(
    private val settings: SettingsRepository,
) {
    private val keysByName = AllSettingKeys.list.associateBy { it.name }

    /**
     * Applies every applicable [staged] value; null [staged] (an archive without
     * `settings.json`) applies nothing. The result counts applied and skipped entries.
     */
    suspend fun apply(staged: SettingsV1?): Outcome<SettingsMergeCounts, BackupError> {
        staged ?: return Outcome.Success(SettingsMergeCounts(applied = 0, skipped = 0))
        var applied = 0
        var skipped = 0
        for (entry in staged.values) {
            val key = keysByName[entry.key]
            // Whitelist first: a crafted name that lands on a DEVICE key is a skip, never a write.
            if (key == null || key.file != SettingsFile.PORTABLE) {
                skipped++
                continue
            }
            when (val outcome = applyOne(key, entry)) {
                Applied.YES -> applied++
                Applied.SKIPPED -> skipped++
                is Applied.Failed -> return Outcome.Failure(outcome.error)
            }
        }
        return Outcome.Success(SettingsMergeCounts(applied, skipped))
    }

    private suspend fun applyOne(
        key: SettingKey<*>,
        entry: SettingValueV1,
    ): Applied =
        when {
            key is SettingKey.Bool && entry.type == TYPE_BOOL -> {
                entry.value?.booleanOrNull?.let { setOrSkip(key, it) } ?: Applied.SKIPPED
            }

            key is SettingKey.Int32 && entry.type == TYPE_INT32 -> {
                entry.value?.intOrNull?.let { setOrSkip(key, it) } ?: Applied.SKIPPED
            }

            key is SettingKey.Int64 && entry.type == TYPE_INT64 -> {
                entry.value?.longOrNull?.let { setOrSkip(key, it) } ?: Applied.SKIPPED
            }

            key is SettingKey.Float32 && entry.type == TYPE_FLOAT32 -> {
                entry.value
                    ?.doubleOrNull
                    ?.toFloat()
                    ?.let { setOrSkip(key, it) } ?: Applied.SKIPPED
            }

            key is SettingKey.Text && entry.type == TYPE_TEXT -> {
                entry.value
                    ?.takeIf { it.isString }
                    ?.content
                    ?.let { setOrSkip(key, it) }
                    ?: Applied.SKIPPED
            }

            key is SettingKey.TextSet && entry.type == TYPE_TEXT_SET -> {
                entry.values?.toPersistentSet()?.let { setOrSkip(key, it) } ?: Applied.SKIPPED
            }

            key is SettingKey.Choice<*> && entry.type == TYPE_CHOICE -> {
                entry.value
                    ?.takeIf { it.isString }
                    ?.content
                    ?.let { name -> key.values.firstOrNull { it.name == name } }
                    ?.let { setChoice(key, it) } ?: Applied.SKIPPED
            }

            else -> {
                Applied.SKIPPED
            }
        }

    /** A `Choice`'s enum value reaches `set` through an unchecked cast past the star projection. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun setChoice(
        key: SettingKey.Choice<*>,
        value: Enum<*>,
    ): Applied = setOrSkip(key as SettingKey<Enum<*>>, value)

    /**
     * One `SettingsRepository.set`: a validator rejection ([SettingsError.OutOfRange],
     * including a `Choice` outside its values) is a skipped entry, not a failed restore;
     * only [SettingsError.WriteFailed] fails it.
     */
    private suspend fun <T : Any> setOrSkip(
        key: SettingKey<T>,
        value: T,
    ): Applied =
        when (val outcome = settings.set(key, value)) {
            is Outcome.Success -> {
                Applied.YES
            }

            is Outcome.Failure -> {
                when (outcome.error) {
                    is SettingsError.OutOfRange -> Applied.SKIPPED
                    SettingsError.WriteFailed -> Applied.Failed(BackupError.Storage)
                }
            }
        }

    private sealed interface Applied {
        data object YES : Applied

        data object SKIPPED : Applied

        data class Failed(
            val error: BackupError,
        ) : Applied
    }

    private companion object {
        // `SettingValueV1.type` is the SettingKey subclass name — the same literals
        // `BackupWriter` emits (the wire contract survives minified builds).
        const val TYPE_BOOL = "Bool"
        const val TYPE_INT32 = "Int32"
        const val TYPE_INT64 = "Int64"
        const val TYPE_FLOAT32 = "Float32"
        const val TYPE_TEXT = "Text"
        const val TYPE_TEXT_SET = "TextSet"
        const val TYPE_CHOICE = "Choice"
    }
}

/** How many staged settings landed and how many the whitelist/type/validator rules skipped. */
internal class SettingsMergeCounts(
    val applied: Int,
    val skipped: Int,
)
