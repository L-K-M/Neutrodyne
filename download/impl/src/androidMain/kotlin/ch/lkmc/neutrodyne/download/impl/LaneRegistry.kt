// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

/** Scheduler holds its mutex across queue reads/enqueues. Framework callbacks only touch this
 * monitor: synchronous UIDT startup must never wait for a database read on the main thread. */
internal class LaneRegistry {
    enum class Phase { IDLE, PENDING, ACTIVE, CLOSING }

    private data class Runner(
        var closing: Boolean = false,
        var reopen: Boolean = false,
    )

    private val runners = mutableMapOf<RunnerToken, Runner>()
    private var pending: java.util.UUID? = null
    private var expedited = false
    private var workerStarted = false
    private var uidt: RunnerToken? = null

    @Synchronized fun phase(): Phase =
        when {
            runners.values.any { !it.closing } -> Phase.ACTIVE
            runners.isNotEmpty() -> Phase.CLOSING
            pending != null -> Phase.PENDING
            else -> Phase.IDLE
        }

    @Synchronized fun isUidt(token: RunnerToken): Boolean = uidt == token

    @Synchronized fun uidtRunning(): Boolean = uidt != null

    @Synchronized fun uidtStarted(token: RunnerToken) {
        uidt = token
        register(token)
    }

    @Synchronized fun pendingExpedited(): Boolean = expedited

    @Synchronized fun hasStartedWorker(): Boolean = workerStarted

    @Synchronized fun pending(
        id: java.util.UUID,
        expedited: Boolean,
    ) {
        pending = id
        this.expedited = expedited
    }

    @Synchronized fun enqueueFailed(id: java.util.UUID) {
        if (pending == id) pending = null
    }

    @Synchronized fun workerStarted() {
        pending = null
        expedited = false
        workerStarted = true
    }

    @Synchronized fun register(token: RunnerToken) {
        runners.putIfAbsent(token, Runner())
    }

    @Synchronized fun closing(token: RunnerToken) {
        runners[token]?.closing = true
    }

    @Synchronized fun reopen() {
        runners.values.filter { it.closing }.forEach { it.reopen = true }
    }

    @Synchronized fun consumeReopen(token: RunnerToken): Boolean {
        val runner = runners[token] ?: return false
        if (!runner.reopen) return false
        runner.closing = false
        runner.reopen = false
        return true
    }

    @Synchronized fun unregister(token: RunnerToken) {
        runners.remove(token)
        if (uidt == token) uidt = null
    }

    @Synchronized fun liveTokens(): Set<String> = runners.keys.map { it.value }.toSet()
}
