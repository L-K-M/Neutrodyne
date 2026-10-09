// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Evicts the shared connection pools' idle sockets (11 Wake and restart catch-up): pooled sockets
 * do not survive a system sleep, so the desktop job runner evicts them before poking the lanes.
 * `:core:network:okhttp`'s `CoreClients` implements it; nothing calls it on Android (WorkManager's
 * reschedule replaces the wake path).
 */
interface ConnectionPoolEvictor {
    fun evict()
}
