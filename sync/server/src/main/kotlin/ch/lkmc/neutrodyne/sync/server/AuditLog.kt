// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

internal data class AuditEntry(
    val accountId: Long?,
    val at: Long,
    val event: String,
    val ip: String?,
)

/** Only event identifiers and resolved IP addresses belong here, never error messages or payloads. */
internal class AuditLog(
    private val store: AuthStore,
    private val inputs: ServerInputs,
) {
    suspend fun recent(actor: AccountPrincipal): List<AuditEntry> =
        store.readAuth {
            requireWebAdmin(actor, inputs.wallMs())
            recentAudit(200)
        }
}
