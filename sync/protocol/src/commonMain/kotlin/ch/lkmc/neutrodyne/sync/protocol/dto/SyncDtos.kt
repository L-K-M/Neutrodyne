// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DiscoveryDocument(
    val name: String,
    val serverVersion: String,
    val protocol: ProtocolRange,
    val features: List<String>,
    val maxBatch: Int,
    val maxSkewMs: Long,
    val serverTime: String,
)

@Serializable
data class ProtocolRange(
    val min: Int,
    val max: Int,
)

@Serializable
data class DeviceInfoDto(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val nodeId: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val device: DeviceInfoDto,
)

@Serializable
data class InviteRedeemRequest(
    val inviteCode: String,
    val device: DeviceInfoDto,
)

@Serializable
data class LinkStartRequest(
    val device: DeviceInfoDto,
)

@Serializable
data class LinkStartResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
)

@Serializable
data class LinkTokenRequest(
    val deviceCode: String,
)

@Serializable
data class LinkApproveRequest(
    val userCode: String,
)

@Serializable
data class LinkApproveResponse(
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val requestedAt: String,
    val purpose: String,
    val sameNetwork: Boolean,
)

@Serializable
data class LinkConfirmRequest(
    val userCode: String,
    val approve: Boolean,
)

@Serializable
data class TokenResponse(
    val token: String,
    val accountId: String,
    val deviceId: String,
    val username: String,
)

@Serializable
data class OAuthErrorDto(
    val error: String,
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String?,
    val lastSeenAt: String?,
    val current: Boolean,
    val stale: Boolean,
)

@Serializable
data class DeviceRenameRequest(
    val name: String,
)

@Serializable
data class AccountSummary(
    val username: String,
    val counts: LibraryCountsDto,
    val devices: Int,
    val quotaRecords: Long,
    val records: Long,
    val passwordSetAt: String? = null,
)

@Serializable
data class LibraryCountsDto(
    val podcasts: Int,
    val groups: Int,
    val episodes: Int,
    val upnext: Int,
    val settings: Int,
)

@Serializable
data class AccountResetRequest(
    val confirm: String,
    val mode: String,
    val beforeCursor: String? = null,
)

@Serializable
data class SyncRequest(
    val since: String? = null,
    val changes: List<ChangeDto> = emptyList(),
)

@Serializable
data class SyncResponse(
    val results: List<ChangeResultDto>,
    val records: List<RecordDto>,
    val cursor: String,
    val hasMore: Boolean,
    val serverTime: String,
)

@Serializable
data class ChangesPage(
    val records: List<RecordDto>,
    val cursor: String,
    val hasMore: Boolean,
    val serverTime: String,
)

@Serializable
data class ChangeDto(
    val coll: String,
    val id: JsonElement,
    val op: String? = null,
    val to: JsonElement? = null,
    val match: MatchHints? = null,
    val fields: Map<String, FieldValue> = emptyMap(),
    val matchClocks: Map<String, String> = emptyMap(),
)

/** Kotlin null means omitted; JsonNull is an explicit value (for example an inherited override). */
@Serializable(with = FieldValueSerializer::class)
data class FieldValue(
    val v: JsonElement? = null,
    val c: String? = null,
    val reset: Boolean? = null,
    val add: List<String>? = null,
)

@Serializable
data class MatchHints(
    val guid: String? = null,
    val enc: String? = null,
    val title: String? = null,
    val date: String? = null,
    val link: String? = null,
    val ytId: String? = null,
    val kv: Int? = null,
)

/** Optional provenance preserves each hint's age when a full record relays a partial hint update. */
@Serializable
data class RecordDto(
    val coll: String,
    val id: JsonElement,
    val seq: Long,
    val by: String?,
    val fields: Map<String, FieldValue>,
    val match: MatchHints? = null,
    val mergedInto: JsonElement? = null,
    val matchClocks: Map<String, String> = emptyMap(),
)

@Serializable
data class ChangeResultDto(
    val i: Int,
    val status: String,
    val mergedInto: JsonElement? = null,
    val code: String? = null,
)

@Serializable
data class ChangedEvent(
    val cursor: String,
    val by: String?,
)

@Serializable
data class ProblemDto(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String? = null,
    val instance: String? = null,
)
