// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.protocol.RecordState
import kotlinx.serialization.Serializable

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
    /** The last acknowledged local setting value's hash, or the applied remote value's hash. */
    val settingHash: String? = null,
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
