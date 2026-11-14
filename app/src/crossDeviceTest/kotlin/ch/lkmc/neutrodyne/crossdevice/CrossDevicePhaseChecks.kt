// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crossdevice

import ch.lkmc.neutrodyne.core.database.SyncStateEntity

/**
 * True when the persisted `sync_state` carries link, clock, cursor, protocol, error or applying
 * residue. The default unlinked row is clean; DataStore preference flags are not part of
 * `sync_state` and are not residue.
 */
fun SyncStateEntity?.hasLinkOrSyncResidue(): Boolean =
    this != null && (
        enabled || applying || linkedAt != null || accountId != null || deviceId != null ||
            serverUrl != null || cursor != null || hlc != 0L || nodeId != null ||
            clockOffsetMs != 0L || protocol != null || lastSyncAt != null || lastError != null
    )

/**
 * The fixture's `nextRefreshAt`: an actual current-time future bound. `T_BASE + one year` is in
 * the past for any run after 2025-10-27 and would let the refresh scheduler run against the
 * fixture feed during the test. The production `RefreshPolicy.NEVER` sentinel is internal to
 * `:core:data`, so the driver uses a current-time bound instead.
 */
fun fixtureNextRefreshAt(nowMs: Long): Long = nowMs + REFRESH_FAR_FUTURE_MS

/**
 * A malformed `sync_clock` row silently removes a record from the canonical wire state the host
 * compares, so the dump must fail rather than report a partial result. The count stays in the
 * successful output for schema compatibility.
 */
fun skippedClockRowsFailure(skippedClockRows: Int): String? =
    if (skippedClockRows > 0) "malformed sync_clock rows: $skippedClockRows" else null

internal const val REFRESH_FAR_FUTURE_MS: Long = 365L * 24 * 60 * 60 * 1000
