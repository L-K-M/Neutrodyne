// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class UpdateNoticesImpl(
    private val build: BuildInfo,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val timeline: VerificationTimeline,
    @ApplicationScope private val scope: CoroutineScope,
) : UpdateNotices {
    private val mutablePending = MutableStateFlow<UpdateNotice?>(null)
    override val pending = mutablePending.asStateFlow()
    private val mutablePhase = MutableStateFlow(timeline.phase(clock.now()))
    override val verificationPhase = mutablePhase.asStateFlow()
    private val mutableError = MutableStateFlow<SettingsError?>(null)
    override val writeError = mutableError.asStateFlow()
    private val mutex = Mutex()

    init {
        scope.launch { settings.observe(Keys.CHECK_ENABLED).collect { refresh() } }
        scope.launch {
            // Reevaluate long-lived processes and wall-clock corrections without a restart.
            while (true) {
                delay(DATE_RECHECK_MS)
                refresh()
            }
        }
    }

    internal suspend fun refresh() = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        mutablePhase.value = timeline.phase(clock.now())
        if (build.debug) {
            mutablePending.value = null
            return
        }

        if (!settings.get(Keys.FIRST_RUN_CHOICE_DONE)) {
            if (settings.get(Keys.CHECK_ENABLED)) {
                mutablePending.value = UpdateNotice.FIRST_RUN_CHOICE
                return
            }
            when (val saved = settings.set(Keys.FIRST_RUN_CHOICE_DONE, true)) {
                is Outcome.Failure -> mutableError.value = saved.error
                is Outcome.Success -> mutableError.value = null
            }
        }
        mutablePending.value =
            if (build.platform == BuildInfo.Platform.ANDROID &&
                timeline.noticeDue(clock.now()) && settings.get(Keys.VERIFICATION_NOTICE_SHOWN_AT) == Keys.ABSENT
            ) {
                UpdateNotice.VERIFICATION_ENFORCEMENT
            } else {
                null
            }
    }

    override fun dismiss(notice: UpdateNotice) {
        scope.launch {
            mutex.withLock {
                if (mutablePending.value != notice) return@withLock
                val result =
                    when (notice) {
                        UpdateNotice.FIRST_RUN_CHOICE -> {
                            settings.set(Keys.FIRST_RUN_CHOICE_DONE, true)
                        }

                        UpdateNotice.VERIFICATION_ENFORCEMENT -> {
                            settings.set(Keys.VERIFICATION_NOTICE_SHOWN_AT, clock.now())
                        }
                    }
                when (result) {
                    is Outcome.Failure -> {
                        mutableError.value = result.error
                    }

                    is Outcome.Success -> {
                        mutableError.value = null
                        refreshLocked()
                    }
                }
            }
        }
    }

    private companion object {
        const val DATE_RECHECK_MS = 60_000L
    }
}
