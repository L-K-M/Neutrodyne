// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

/**
 * One DataStore preference key, defined by the module that owns it (01 DataStore files and typed
 * setting keys). [file] picks the portable or device-local store; [synced] may be true only for
 * portable keys in syncable prefixes (see the rules on [AllSettingKeys]).
 *
 * Subclasses fix the value type [T] and the stored representation; reads never throw — an
 * unknown stored value falls back to [default]. Collections in key defaults are
 * `kotlinx.collections.immutable` so a key is deeply immutable (01 Model and state rules).
 */
sealed class SettingKey<T : Any>(
    /** Stored name, e.g. `appearance.dark_mode` (pattern enforced by the registry test). */
    val name: String,
    /** The key's compile-time default; the DataStore layer returns it when the key is absent. */
    val default: T,
    /** `PORTABLE` = part of backup and sync's candidate set; `DEVICE` = never leaves this device. */
    val file: SettingsFile,
    /** Whether 10's sync may carry it (`false` unless portable and in a syncable prefix). */
    val synced: Boolean = false,
) {
    class Bool(
        name: String,
        default: Boolean,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<Boolean>(name, default, file, synced)

    class Int32(
        name: String,
        default: Int,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<Int>(name, default, file, synced)

    class Int64(
        name: String,
        default: Long,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<Long>(name, default, file, synced)

    class Float32(
        name: String,
        default: Float,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<Float>(name, default, file, synced)

    class Text(
        name: String,
        default: String,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<String>(name, default, file, synced)

    class TextSet(
        name: String,
        default: ImmutableSet<String>,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<ImmutableSet<String>>(name, default, file, synced)

    /** String-keyed choice, stored as `E.name`; an unknown stored name reads as [default]. */
    class Choice<E : Enum<E>>(
        name: String,
        default: E,
        /** Immutable: the allowed values. [fromStored] falls back to [default] for unknown names. */
        val values: ImmutableList<E>,
        file: SettingsFile = SettingsFile.PORTABLE,
        synced: Boolean = false,
    ) : SettingKey<E>(name, default, file, synced) {
        init {
            require(default in values) { "Choice '$name' default must be one of its values" }
        }

        /** Maps a stored `E.name` back to its constant; unknown or absent names read [default]. */
        fun fromStored(stored: String?): E = values.firstOrNull { it.name == stored } ?: default
    }
}
