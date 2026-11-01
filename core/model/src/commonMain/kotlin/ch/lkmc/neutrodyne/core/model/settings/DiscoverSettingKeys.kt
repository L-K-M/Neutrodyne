// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/** Explicit backups carry provider choices; sync never does. Secrets stay in SecretStore. */
object DiscoverSettingKeys {
    /** Empty uses PlatformInfo.regionCode, then US; explicit values are uppercase ASCII pairs. */
    val COUNTRY = SettingKey.Text("discover.country", "")
    val APPLE_ENABLED = SettingKey.Bool("discover.apple_enabled", true)
    val FYYD_ENABLED = SettingKey.Bool("discover.fyyd_enabled", true)
    val PODCASTINDEX_ENABLED = SettingKey.Bool("discover.podcastindex_enabled", true)
    val SUGGESTED_GROUPS_DISMISSED =
        SettingKey.Bool("discover.suggested_groups_dismissed", false, SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(COUNTRY, APPLE_ENABLED, FYYD_ENABLED, PODCASTINDEX_ENABLED, SUGGESTED_GROUPS_DISMISSED)

    /** Validate stored format, not Apple's changing country availability; shared by write/restore. */
    fun acceptsCountry(value: String): Boolean = value.isEmpty() || (value.length == 2 && value.all { it in 'A'..'Z' })

    internal fun acceptsValue(
        key: SettingKey<*>,
        value: Any,
    ): Boolean = key.name != COUNTRY.name || (value is String && acceptsCountry(value))
}
