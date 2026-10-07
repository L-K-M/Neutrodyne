// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.persistentListOf

/**
 * 08 "Keys owned here": the `appearance.*` keys M0a writes. Both live in the portable `settings`
 * file but none syncs (PO-37: appearance is device-local). `ThemeMode` and the later keys
 * (`artwork_tint`, `pure_black`, the M1+/M2+ rows) are added by their milestones.
 */
public object AppearanceSettingKeys {
    /** Appearance › Theme. */
    public val THEME: SettingKey.Choice<ThemeMode> =
        SettingKey.Choice(
            name = "appearance.theme",
            default = ThemeMode.SYSTEM,
            values = persistentListOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
        )

    /** Appearance › Use wallpaper colours; only Android API 31+ shows the row. */
    public val DYNAMIC_COLOR: SettingKey.Bool =
        SettingKey.Bool(name = "appearance.dynamic_color", default = true)

    public val ALL: List<SettingKey<*>> = listOf(THEME, DYNAMIC_COLOR)
}
