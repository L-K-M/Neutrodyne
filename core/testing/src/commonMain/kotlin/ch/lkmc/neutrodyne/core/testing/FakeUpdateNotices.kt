// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import kotlinx.coroutines.flow.MutableStateFlow

/** Ordered notice fixture; dismissal of a stale notice cannot consume the next one. */
class FakeUpdateNotices(
    notices: List<UpdateNotice> = emptyList(),
) : UpdateNotices {
    private val remaining = notices.distinct().toMutableList()
    override val pending = MutableStateFlow(remaining.firstOrNull())
    override val verificationPhase = MutableStateFlow(VerificationPhase.PRE_ENFORCEMENT)
    override val writeError = MutableStateFlow<SettingsError?>(null)
    var failNextWrite: SettingsError? = null
    private val dismissed = mutableListOf<UpdateNotice>()
    val dismissals: List<UpdateNotice> get() = dismissed.toList()

    override fun dismiss(notice: UpdateNotice) {
        if (pending.value != notice) return

        val failure = failNextWrite
        failNextWrite = null
        writeError.value = failure
        if (failure != null) return

        dismissed += notice
        remaining.removeAt(0)
        pending.value = remaining.firstOrNull()
    }
}
