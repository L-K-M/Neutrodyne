// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.setting_app_default
import ch.lkmc.neutrodyne.core.ui.resources.setting_from_group
import ch.lkmc.neutrodyne.core.ui.resources.setting_from_groups
import ch.lkmc.neutrodyne.core.ui.resources.setting_from_two_groups
import ch.lkmc.neutrodyne.core.ui.resources.setting_podcast_override

/** The deciding sources supplied by the resolver, in its deterministic group order (05). */
public object AttributionText {
    public fun describe(source: SettingSource): UiText =
        when (source) {
            SettingSource.PodcastOverride -> {
                UiText.Res(Res.string.setting_podcast_override)
            }

            SettingSource.AppDefault -> {
                UiText.Res(Res.string.setting_app_default)
            }

            is SettingSource.Group -> {
                UiText.Res(Res.string.setting_from_group, listOf(source.name))
            }

            is SettingSource.Groups -> {
                when (source.groups.size) {
                    1 -> describe(source.groups.single())
                    2 -> UiText.Res(Res.string.setting_from_two_groups, source.groups.map { it.name })
                    else -> UiText.Res(Res.string.setting_from_groups, listOf(source.groups.size))
                }
            }
        }
}
