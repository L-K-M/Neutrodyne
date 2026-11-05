// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The protected representation of a feed password on the receive side (10 Durable apply effects,
 * Feed passwords). A received `auth` value never enters Room metadata or the link staging file as
 * plaintext: before the page transaction each validated candidate is staged in the platform
 * `SecretStore` under a `sync:pending:` origin, and every raw state, outbox literal and staged
 * file carries only the marker — an object with empty `u`/`p` (so it still passes the protocol's
 * AUTH validation and merges canonically) plus the `ref` store key. Hydration (push-time replay)
 * reads the marker's key from the store; installation drains the staged entry into the feed
 * origin through `CredentialCommitCoordinator`.
 */
internal const val PENDING_ORIGIN_PREFIX = "sync:pending:"

/** `sync:pending:<account>:<rid>:<clock>` — rid and the HLC wire form contain no separator chars. */
internal fun pendingAuthOrigin(
    account: String,
    rid: String,
    clock: String,
): String = "$PENDING_ORIGIN_PREFIX$account:$rid:$clock"

/** The marker value stored in raw state for an `auth` field; [authMarkerKey] reads the key back. */
internal fun authMarker(storeKey: String): JsonObject =
    JsonObject(
        buildMap {
            put("u", JsonPrimitive(""))
            put("p", JsonPrimitive(""))
            put("ref", JsonPrimitive(storeKey))
        },
    )

/** The protected store key a marker carries, or `null` for a plaintext credential value. */
internal fun authMarkerKey(value: JsonElement): String? {
    val obj = value as? JsonObject ?: return null
    val ref = (obj["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if ((obj["u"] as? JsonPrimitive)?.stringOrNull() != "") return null
    if ((obj["p"] as? JsonPrimitive)?.stringOrNull() != "") return null
    return ref
}

/** A plaintext `{"u": …, "p": …}` pair, or `null` when malformed (validation will reject it). */
internal data class AuthCandidate(
    val username: String,
    val password: String,
)

internal fun authCandidate(value: JsonElement): AuthCandidate? {
    val obj = value as? JsonObject ?: return null
    val u = (obj["u"] as? JsonPrimitive)?.stringOrNull() ?: return null
    val p = (obj["p"] as? JsonPrimitive)?.stringOrNull() ?: return null
    return AuthCandidate(u, p)
}

private fun JsonPrimitive.stringOrNull(): String? = if (isString) content else null

/**
 * One page's staging pass: puts every plaintext `auth` candidate of the podcast records into the
 * protected store and returns the records with the marker in place of the secret. The puts need
 * no `CredentialCommitCoordinator`: `sync:` origins are outside every credential sweep, and only
 * the rules of 10 Durable apply effects remove them again.
 */
internal class AuthStage(
    private val secrets: SecretStore,
    private val account: String,
) {
    private val staged = LinkedHashMap<String, String>()

    suspend fun admit(records: List<RecordDto>): List<RecordDto> =
        records.map { dto ->
            if (dto.coll != "podcast") return@map dto
            val field = dto.fields["auth"] ?: return@map dto
            val value = field.v ?: return@map dto
            val clock = field.c ?: return@map dto
            val candidate = authCandidate(value)
            if (candidate == null || authMarkerKey(value) != null) return@map dto
            val rid = dto.id.jsonPrimitive.content
            val key = pendingAuthOrigin(account, rid, clock)
            staged["$rid|$clock"] = key
            secrets.put(key, candidate.username, candidate.password.toCharArray())
            dto.copy(fields = dto.fields + ("auth" to FieldValue(authMarker(key), field.c, field.reset)))
        }

    /** The staged key of one record's winning field, if this page staged it. */
    fun stagedKey(
        rid: String,
        clock: String?,
    ): String? = clock?.let { staged["$rid|$it"] }

    /**
     * Drops the page's losing candidates — every staged key no raw state kept referencing. The
     * winner's snapshot stays: raw state references it for later hydration.
     */
    suspend fun removeUnreferenced(referenced: Set<String>) {
        for (key in staged.values) {
            if (key !in referenced) secrets.remove(key)
        }
    }
}
