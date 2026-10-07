// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * `sync_state`: singleton (`id = 0`), inserted by `Callback.onCreate` as
 * `INSERT OR IGNORE INTO sync_state(id) VALUES (0)` (02 sync_state). The token is not here: it
 * lives in `credential` (`sync:<host>`, Android) or `DesktopSecretStore` (desktop).
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    /** Linked and first-link step done: capture on. */
    @ColumnInfo(defaultValue = "0") val enabled: Boolean = false,
    /** 1 only inside `SyncStateDao.withApplying`. */
    @ColumnInfo(defaultValue = "0") val applying: Boolean = false,
    /** Base URL of the linked server. */
    val serverUrl: String? = null,
    val accountId: String? = null,
    val deviceId: String? = null,
    /** Opaque server cursor (10). */
    val cursor: String? = null,
    /** Packed `(ms << 16) | counter` of this node. */
    @ColumnInfo(defaultValue = "0") val hlc: Long = 0,
    /** 16 lowercase hex digits, new at each link. */
    val nodeId: String? = null,
    /** Offset correction from serverTime (10). */
    @ColumnInfo(defaultValue = "0") val clockOffsetMs: Long = 0,
    /** Negotiated `Neutrodyne-Sync-Protocol`. */
    val protocol: Int? = null,
    val linkedAt: Long? = null,
    val lastSyncAt: Long? = null,
    /** `SyncErrorCode` or `SyncProblem` name only. */
    val lastError: String? = null,
)

/** Device bookkeeping only; never a wire field (02 sync_outbox). */
enum class SyncCaptureKind { LOCAL, REPLAY }

/**
 * `sync_outbox`: one row per `(coll, rid, field)` — repeated changes coalesce (02). WITHOUT ROWID:
 * every access is by the text key.
 */
@Entity(
    tableName = "sync_outbox",
    primaryKeys = ["coll", "rid", "field"],
    withoutRowId = true,
    indices = [Index("hlc", "nodeId")],
)
data class SyncOutboxEntity(
    /** podcast, group, member, episode, upnext, session, setting. */
    val coll: String,
    /** Canonical record ID text. */
    val rid: String,
    /** Wire field name, `*` (every field of the record) or `~rekey`. */
    val field: String,
    /** Packed milliseconds and counter; replay preserves both. */
    val hlc: Long,
    /** Clock's original node; local captures use `sync_state.nodeId`. */
    val nodeId: String,
    @ColumnInfo(defaultValue = "'LOCAL'") val captureKind: SyncCaptureKind = SyncCaptureKind.LOCAL,
    /** Null: read the current local value at push time; else literal JSON. */
    val value: String? = null,
)

/**
 * `sync_clock`: one row per record this device has synced; `clocks` holds field clocks, raw
 * records, setting baseline and pending effects as JSON owned by 10 (02). NEVER read in SQL.
 */
@Entity(tableName = "sync_clock", primaryKeys = ["coll", "rid"], withoutRowId = true)
data class SyncClockEntity(
    val coll: String,
    val rid: String,
    val clocks: String,
)

/**
 * `sync_parked`: received `episode`, `upnext` and `member` records that reference a podcast or
 * episode this device does not have yet (02).
 */
@Entity(
    tableName = "sync_parked",
    indices = [Index("podcastSyncId"), Index("guid"), Index("enclosureKey")],
)
data class SyncParkedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastSyncId: String,
    /** The record's episode key k; `@member:<groupUuid>` for a parked membership. */
    val identityKey: String,
    /** Match hint guid. */
    val guid: String? = null,
    /** `UrlNormalizer.forIdentity(match hint enc)`. */
    val enclosureKey: String? = null,
    /** The received record as JSON (10's RecordDto). */
    val record: String,
    val receivedAt: Long,
)

/**
 * `sync_held`: removals staged or held by 10's mass-change guard, and removals deferred while the
 * affected podcast plays (02). A handful of rows at most.
 */
@Entity(tableName = "sync_held")
data class SyncHeldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** JSON: state and the removals with their clocks (10). */
    val batch: String,
    /** JSON for the prompt: device name, podcast titles, group names. */
    val summary: String,
    val heldAt: Long,
)
