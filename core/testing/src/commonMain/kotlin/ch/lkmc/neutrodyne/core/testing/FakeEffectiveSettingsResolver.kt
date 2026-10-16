// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.model.Effective
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Fixture-controlled resolutions; selection and observable updates match the domain contract. */
class FakeEffectiveSettingsResolver : EffectiveSettingsResolver {
    val notificationValues = MutableStateFlow<Map<Long, Effective<Boolean>>>(emptyMap())
    val refreshValues = MutableStateFlow<Map<Long, Effective<Int?>>>(emptyMap())

    override suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>> =
        notificationValues.value.filterKeys { it in podcastIds }

    override suspend fun refreshIntervals(): Map<Long, Effective<Int?>> = refreshValues.value

    override fun observeNotifications(podcastIds: Collection<Long>): Flow<Map<Long, Effective<Boolean>>> {
        val ids = podcastIds.toSet()
        return notificationValues.map { values -> values.filterKeys { it in ids } }.distinctUntilChanged()
    }

    override fun observeRefreshIntervals(): Flow<Map<Long, Effective<Int?>>> = refreshValues
}
