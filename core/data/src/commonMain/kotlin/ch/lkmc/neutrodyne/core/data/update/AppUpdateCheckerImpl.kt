// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.getOrNull
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.domain.update.UpdateRuntimeInputs
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys

/** One app-scoped owner. Network I/O runs outside the mutation lock; publication checks its epoch. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<AppUpdateChecker>())
@ContributesBinding(AppScope::class, binding<UpdateCheckExecutor>())
@Inject
internal class AppUpdateCheckerImpl(
    private val build: BuildInfo,
    private val runtime: UpdateRuntimeInputs,
    private val settings: SettingsRepository,
    private val source: UpdateSource,
    private val store: UpdateStore,
    private val selector: UpdateAssetSelector,
    private val notifications: UpdateNotificationAdapter,
    private val runner: Provider<UpdateCheckRunner>,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val jitter: UpdateJitter,
    @ApplicationScope private val scope: CoroutineScope,
) : AppUpdateChecker,
    UpdateCheckExecutor {
    private val dev = build.debug || build.desktop?.installKind == InstallKind.DEV
    private val mutableState =
        MutableStateFlow<UpdateCheckState>(
            if (dev) UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD) else UpdateCheckState.Idle(null),
        )
    override val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<SettingsError?>(null)
    override val writeError = mutableError.asStateFlow()
    private val mutex = Mutex()
    private var initialized = false
    private var enabled = false
    private var epoch = 0L
    private var snapshot: UpdateStoreSnapshot? = null
    private var lastRequestElapsed: Long? = null
    private var suppressedVersion: Long? = null
    private val flight = MutableStateFlow<Flight?>(null)
    private var manualDispatch: Deferred<UpdateCheckState>? = null
    private var manualPrevious: UpdateCheckState? = null

    override suspend fun initialize() {
        mutex.withLock {
            if (initialized) return
            enabled = settings.get(Keys.CHECK_ENABLED)
            val read = store.read()
            if (read is Outcome.Failure && read.error == UpdateStoreError.IO) {
                mutableError.value = SettingsError.WriteFailed
            }
            val now = clock.now()
            snapshot = read.getOrNull() ?: UpdateStoreSnapshot(
                null,
                Keys.nullableValue(settings.get(Keys.LAST_CHECK_AT)),
                now,
                dueAfter(now, DAY_MS),
                0,
            ).also { persist(it) }
            val cached = snapshot!!.manifest
            if (cached != null && cached.versionCode <= build.versionCode.toLong()) {
                val cleared = snapshot!!.copy(manifest = null)
                if (persist(cleared)) snapshot = cleared
                notifications.cancel()
            }
            // The file is authoritative if the process died between its rename and preferences.
            snapshot!!.lastCheckAtMs?.let { save(Keys.LAST_CHECK_AT, it) }
            recompute()
            initialized = true
            // Cancelling this worker's periodic request can cancel initialize's caller. Install
            // the application-scope observer first so a later switch-on still schedules work.
            scope.launch {
                settings.observe(Keys.CHECK_ENABLED).collect { choice ->
                    // Cancel before taking the mutation lock: posting or a cache write may be
                    // suspended inside it. A manual run begun while off keeps that choice.
                    val active = flight.value
                    val mode = if (choice && !dev) UpdateScheduling.ENABLED else UpdateScheduling.DISABLED
                    if (active != null && active.choiceAtStart != mode) active.job.cancel()
                    mutex.withLock {
                        if (choice == enabled) return@withLock
                        enabled = choice
                        epoch++
                        flight.value?.job?.cancel()
                        manualDispatch?.cancel()
                        notifications.cancel()
                        recompute()
                        runner().setAutomaticChecks(scheduling())
                    }
                }
            }
            runner().setAutomaticChecks(scheduling())
        }
    }

    override suspend fun checkNow(): UpdateCheckState {
        initialize()
        val task =
            mutex.withLock {
                refreshChoice()
                if (dev) return mutableState.value
                manualDispatch?.let { return@withLock it }
                flight.value?.let {
                    it.origin = UpdateInvocation.MANUAL
                    return@withLock it.job
                }
                if (throttled()) return mutableState.value
                if (!network.status.value.isConnected) return failure(UpdateCheckError.NETWORK)

                val previous = mutableState.value
                val admissionEpoch = epoch
                scope
                    .async(start = CoroutineStart.LAZY) {
                        try {
                            withTimeout(MANUAL_TIMEOUT_MS) { runner().runNow() }
                        } catch (_: TimeoutCancellationException) {
                            abortManual()
                            mutex.withLock {
                                refreshChoice()
                                if (epoch ==
                                    admissionEpoch
                                ) {
                                    failure(UpdateCheckError.NETWORK, previous)
                                } else {
                                    mutableState.value
                                }
                            }
                        } finally {
                            withContext(NonCancellable) {
                                mutex.withLock {
                                    if (mutableState.value == UpdateCheckState.Checking && epoch == admissionEpoch) {
                                        failure(UpdateCheckError.NETWORK, previous)
                                    }
                                    manualDispatch = null
                                    manualPrevious = null
                                }
                            }
                        }
                    }.also {
                        manualPrevious = previous
                        mutableState.value = UpdateCheckState.Checking
                        manualDispatch = it
                        it.start()
                    }
            }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                task.cancel()
                abortManual()
            }
            throw cancelled
        }
    }

    override suspend fun execute(origin: UpdateInvocation): UpdateCheckState {
        initialize()
        val task =
            mutex.withLock {
                refreshChoice()
                if (dev || (origin == UpdateInvocation.SCHEDULED && !checksAllowed())) return mutableState.value
                flight.value?.let {
                    if (origin == UpdateInvocation.MANUAL) it.origin = UpdateInvocation.MANUAL
                    return@withLock it.job
                }
                if (origin == UpdateInvocation.SCHEDULED && build.platform == BuildInfo.Platform.DESKTOP &&
                    clock.now() < snapshot!!.nextDueAtMs
                ) {
                    return mutableState.value
                }
                if (origin == UpdateInvocation.MANUAL && throttled()) return mutableState.value
                val previous = manualPrevious ?: mutableState.value
                if (!network.status.value.isConnected) {
                    if (origin == UpdateInvocation.SCHEDULED) recordFailureSchedule()
                    return failure(UpdateCheckError.NETWORK, previous)
                }

                // A queued manual request also participates if the scheduled worker gets here first.
                val effectiveOrigin = if (manualDispatch != null) UpdateInvocation.MANUAL else origin
                val current = Flight(epoch, effectiveOrigin, scheduling(), previous)
                current.job = scope.async(start = CoroutineStart.LAZY) { perform(current) }
                flight.value = current
                lastRequestElapsed = clock.elapsedRealtime()
                mutableState.value = UpdateCheckState.Checking
                current.job.also { it.start() }
            }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            // The executor's GET is an application-scope child. A stopped worker/lane must
            // explicitly cancel it too, or quit/WorkManager stops leave network work alive.
            withContext(NonCancellable) { task.cancelAndJoin() }
            throw cancelled
        }
    }

    private suspend fun perform(current: Flight): UpdateCheckState {
        try {
            val result =
                withTimeoutOrNull(MANUAL_TIMEOUT_MS) { source.fetch() }
                    ?: Outcome.Failure(UpdateCheckError.NETWORK)
            return mutex.withLock {
                refreshChoice()
                if (current.epoch != epoch || (current.origin != UpdateInvocation.MANUAL && !checksAllowed())) {
                    return@withLock mutableState.value
                }
                when (result) {
                    is Outcome.Failure -> {
                        recordFailureSchedule()
                        failure(result.error, current.previous)
                    }

                    is Outcome.Success -> {
                        accept(result.value, current)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (flight.value === current) {
                        flight.value = null
                        if (mutableState.value == UpdateCheckState.Checking) {
                            failure(UpdateCheckError.NETWORK, current.previous)
                        }
                    }
                }
            }
        }
    }

    private suspend fun accept(
        manifest: UpdateManifest,
        current: Flight,
    ): UpdateCheckState {
        val now = clock.now()
        val keep = manifest.takeIf { it.versionCode > build.versionCode.toLong() }
        val delay = if (build.platform == BuildInfo.Platform.DESKTOP) jitter.nextDelayMs() else 0L
        require(delay in 0..HOUR_MS)
        val next =
            snapshot!!.copy(
                manifest = keep,
                lastCheckAtMs = now,
                nextDueAtMs =
                    dueAfter(now, DAY_MS + delay),
                retryAttempt = 0,
            )
        val cached = persist(next)
        snapshot = next
        val preferenceSaved = save(Keys.LAST_CHECK_AT, now)
        if (cached && preferenceSaved) mutableError.value = null
        refreshChoice()
        if (current.epoch != epoch) return mutableState.value
        val info = keep?.takeUnless { it.versionCode == settings.get(Keys.SKIPPED_VERSION_CODE) }?.let(::info)
        mutableState.value =
            if (info != null) {
                UpdateCheckState.Available(info, now)
            } else {
                UpdateCheckState.Idle(now)
            }
        if (keep == null) notifications.cancel()
        if (info == null || info.versionCode == settings.get(Keys.NOTIFIED_VERSION_CODE)) return mutableState.value

        if (current.origin == UpdateInvocation.MANUAL || suppressedVersion == info.versionCode) {
            // Retry bookkeeping after a failed write without dispatching twice in this process.
            suppressedVersion = info.versionCode
            save(Keys.NOTIFIED_VERSION_CODE, info.versionCode)
            return mutableState.value
        }
        val androidTarget = runtime.target as? UpdateRuntimeTarget.Android
        if (androidTarget != null && info.minSdk > androidTarget.sdkInt) return mutableState.value
        if (notifications.post(info) == UpdatePostResult.ACCEPTED) {
            suppressedVersion = info.versionCode
            save(Keys.NOTIFIED_VERSION_CODE, info.versionCode)
        }
        return mutableState.value
    }

    override fun skip(versionCode: Long) {
        scope.launch {
            initialize()
            mutex.withLock {
                val available = mutableState.value as? UpdateCheckState.Available ?: return@withLock
                if (available.info.versionCode != versionCode) return@withLock
                if (!save(Keys.SKIPPED_VERSION_CODE, versionCode)) return@withLock

                mutableError.value = null
                notifications.cancel()
                mutableState.value = UpdateCheckState.Idle(available.lastCheckAtMs)
            }
        }
    }

    /** Recheck storage at execution/publication too: its observer may still be waiting for the lock. */
    private suspend fun refreshChoice() {
        val choice = settings.get(Keys.CHECK_ENABLED)
        if (choice == enabled) return
        enabled = choice
        epoch++
        notifications.cancel()
        recompute()
        runner().setAutomaticChecks(scheduling())
    }

    private suspend fun recompute() {
        val cached = snapshot?.manifest
        mutableState.value =
            when {
                dev -> {
                    UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD)
                }

                !enabled -> {
                    UpdateCheckState.Disabled(UpdateDisabledReason.CHECKS_OFF)
                }

                cached != null && cached.versionCode > build.versionCode.toLong() &&
                    cached.versionCode != settings.get(Keys.SKIPPED_VERSION_CODE) -> {
                    UpdateCheckState.Available(info(cached), snapshot!!.lastCheckAtMs!!)
                }

                else -> {
                    UpdateCheckState.Idle(snapshot?.lastCheckAtMs)
                }
            }
    }

    private fun info(manifest: UpdateManifest): UpdateInfo {
        val target = runtime.target
        val apk =
            (target as? UpdateRuntimeTarget.Android)?.let { androidTarget ->
                manifest.apks.find { it.abi == androidTarget.preferredAbi }
            }
        val desktop =
            build.desktop?.let { identity ->
                (target as? UpdateRuntimeTarget.Desktop)?.let { selector.select(manifest, identity, it) }
            }
        return UpdateInfo(
            manifest.versionName,
            manifest.versionCode,
            manifest.minSdk,
            manifest.publishedAt,
            manifest.notes,
            manifest.releaseUrl,
            apk,
            desktop,
            manifest.server,
        )
    }

    private fun failure(
        error: UpdateCheckError,
        previous: UpdateCheckState = mutableState.value,
    ): UpdateCheckState {
        mutableState.value =
            when (previous) {
                is UpdateCheckState.Available -> previous.copy(lastError = error)
                else -> UpdateCheckState.Failed(error, snapshot?.lastCheckAtMs)
            }
        return mutableState.value
    }

    private suspend fun recordFailureSchedule() {
        val old = snapshot!!
        val attempt = (old.retryAttempt + 1).coerceAtMost(MAX_RETRY_ATTEMPT)
        val delay = minOf(DAY_MS, HOUR_MS * (1L shl (attempt - 1)))
        val next = old.copy(nextDueAtMs = dueAfter(clock.now(), delay), retryAttempt = attempt)
        persist(next)
        snapshot = next
    }

    private suspend fun persist(value: UpdateStoreSnapshot): Boolean =
        when (store.write(value)) {
            is Outcome.Success -> {
                true
            }

            is Outcome.Failure -> {
                mutableError.value = SettingsError.WriteFailed
                false
            }
        }

    private suspend fun <T : Any> save(
        key: SettingKey<T>,
        value: T,
    ): Boolean =
        when (val result = settings.set(key, value)) {
            is Outcome.Success -> {
                true
            }

            is Outcome.Failure -> {
                mutableError.value = result.error
                false
            }
        }

    private suspend fun abortManual() {
        runner().cancelManual()
        mutex.withLock {
            val active = flight.value
            if (active?.origin == UpdateInvocation.MANUAL) {
                active.job.cancel()
                if (mutableState.value == UpdateCheckState.Checking) {
                    failure(UpdateCheckError.NETWORK, active.previous)
                }
            }
        }
    }

    private fun throttled(): Boolean {
        val last = lastRequestElapsed ?: return false
        return clock.elapsedRealtime() - last < MANUAL_THROTTLE_MS
    }

    private fun checksAllowed(): Boolean = enabled && !dev

    private fun scheduling() = if (checksAllowed()) UpdateScheduling.ENABLED else UpdateScheduling.DISABLED

    private class Flight(
        val epoch: Long,
        var origin: UpdateInvocation,
        val choiceAtStart: UpdateScheduling,
        val previous: UpdateCheckState,
    ) {
        lateinit var job: Deferred<UpdateCheckState>
    }

    private companion object {
        const val MANUAL_TIMEOUT_MS = 30_000L
        const val MANUAL_THROTTLE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val MAX_RETRY_ATTEMPT = 6

        fun dueAfter(
            now: Long,
            delay: Long,
        ): Long = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}
