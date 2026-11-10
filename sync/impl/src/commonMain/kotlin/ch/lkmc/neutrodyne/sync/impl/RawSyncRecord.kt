// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okio.ByteString.Companion.encodeUtf8

/**
 * The `sync_clock.clocks` envelope (10 Client sync engine, Raw state): the merged raw record plus
 * the session writer, the protected credential reference, redirects, aliases, the setting baseline
 * and the pending durable effects. `:sync:impl` owns this JSON; SQL never reads it.
 */
@Serializable
data class RawSyncRecord(
    val state: RecordState,
    /** The device that wrote the session (`by`), retained through acknowledgements and redirects. */
    val by: String? = null,
    /** A protected credential snapshot referenced by raw state, never a plaintext secret. */
    val authReference: AuthReference? = null,
    /** `"→": "<rid>"` — this record was merged into another local record. */
    val redirect: String? = null,
    /** A local alias: an incoming `rid` that matched this local record's key. */
    val alias: String? = null,
    /** The materialization baseline: the last setting value DataStore is known to hold. */
    val settingHash: SettingBaseline? = null,
    /** The predecessor snapshot of a local intent, committed atomically with its outbox row. */
    val settingIntent: SettingIntent? = null,
    /** A rejected local setting receipt, kept separately from accepted server fields. */
    val rejectedSetting: SettingBaseline? = null,
    /** A winning remote setting awaiting its DataStore write. */
    val pendingSetting: PendingSetting? = null,
    /** A winning remote credential awaiting its `SecretStore.put` and `credentialId` write. */
    val pendingAuth: PendingAuth? = null,
)

/** A protected `SecretStore` staging entry (`sync:pending:<account>:<rid>:<clock>`). */
@Serializable
data class AuthReference(
    val storeKey: String,
    val credentialOrigin: String,
    val clock: String,
)

@Serializable
data class PendingSetting(
    val value: String,
    val clock: String,
)

/**
 * 10 Settings capture, materialization baseline: the canonical hash of the last value DataStore
 * is known to hold, at the complete clock that wrote it — a captured local intent's clock, an
 * acknowledged sent clock, or an applied remote clock. [clock] is `null` for a local write that
 * was never captured (the switch off or unlinked): the value is real but carries no wire clock,
 * so recovery treats a stored intent that would overwrite it as superseded rather than replaying
 * an older committed edit over the newer local one.
 */
@Serializable
data class SettingBaseline(
    val hash: String,
    val clock: String? = null,
)

/**
 * The deterministic baseline hash of a setting's wire JSON (10 Settings capture): a literal
 * number, quoted text/choice, `"null"` reset or a text-set array — array entries sort so set
 * order cannot change the digest. Unparseable input hashes as-is; the port's encoders only
 * produce valid JSON.
 */
internal fun settingBaselineHash(json: String): String {
    val canonical =
        runCatching { SyncJson.parseToJsonElement(json) }
            .getOrNull()
            ?.let(::canonicalSettingElement)
            ?.toString() ?: json
    return canonical.encodeUtf8().sha256().hex()
}

private fun canonicalSettingElement(element: JsonElement): JsonElement =
    when (element) {
        is JsonArray -> {
            JsonArray(
                element
                    .map(::canonicalSettingElement)
                    .sortedBy(JsonElement::toString),
            )
        }

        else -> {
            element
        }
    }

/**
 * The next baseline after a materialized value at [clock], but only when that clock is newer
 * than the stored one — an ack or drain must never roll the baseline back to an older write.
 * A clockless stored baseline (an uncaptured write) yields to any clocked one.
 */
internal fun SettingBaseline?.advancedTo(
    json: String,
    clock: String,
    canonicalJson: String = json,
): SettingBaseline {
    val storedClock = this?.clock?.let { runCatching { Hlc.parse(it) }.getOrNull() }
    val next = runCatching { Hlc.parse(clock) }.getOrNull()
    return when {
        this == null -> {
            SettingBaseline(settingBaselineHash(canonicalJson), clock)
        }

        // An older writer may have hashed the accepted wire spelling before Float32 decode.
        // Normalize only the same clock and the same literal, never another write's baseline.
        next != null && storedClock == next && hash == settingBaselineHash(json) &&
            hash != settingBaselineHash(canonicalJson) -> {
            copy(hash = settingBaselineHash(canonicalJson))
        }

        next == null || (storedClock != null && storedClock >= next) -> {
            this
        }

        else -> {
            SettingBaseline(settingBaselineHash(canonicalJson), clock)
        }
    }
}

@Serializable
data class PendingAuth(
    val reference: String,
    val credentialOrigin: String,
    val clock: String,
)

/** The clock of a raw field, parsed from the envelope's wire strings. */
fun RawSyncRecord.pendingSettingClock(): String? = pendingSetting?.clock

/** The newest complete field clock in the record, hints included (the receive bound). */
fun RawSyncRecord.maxClock(): String? = state.maxClock?.toString()

/** Distinguishes an older uncaptured value from an OFF write that superseded this intent. */
@Serializable
data class SettingIntent(
    val clock: String,
    val priorReceipt: String? = null,
    val priorHash: String,
    val kind: SettingIntentKind = SettingIntentKind.LOCAL,
    /** The immutable DataStore receipt may predate repair of this unaccepted wire clock. */
    val receiptClock: String? = null,
)

/** A historical restore may precede an already accepted clock while being the next physical write. */
@Serializable
enum class SettingIntentKind { LOCAL, RESTORE }
