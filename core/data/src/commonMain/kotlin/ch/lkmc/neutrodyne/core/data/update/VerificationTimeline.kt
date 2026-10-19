// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.model.update.VerificationPhase

/** PO-36 default; no exact global enforcement date has been announced. UTC date boundaries. */
internal class VerificationTimeline(
    private val noticeFrom: Long = NOTICE_FROM,
    private val globalEnforcement: Long? = GLOBAL_ENFORCEMENT,
) {
    fun noticeDue(now: Long): Boolean = now >= noticeFrom

    fun phase(now: Long): VerificationPhase =
        if (globalEnforcement != null && now >= globalEnforcement) {
            VerificationPhase.POST_ENFORCEMENT
        } else {
            VerificationPhase.PRE_ENFORCEMENT
        }

    companion object {
        const val NOTICE_FROM = 1_796_083_200_000L // 2026-12-01T00:00:00Z
        val GLOBAL_ENFORCEMENT: Long? = null
    }
}
