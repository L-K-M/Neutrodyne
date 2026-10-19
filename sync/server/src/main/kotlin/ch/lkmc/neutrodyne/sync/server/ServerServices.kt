// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

/** Constructor wiring shared with W8's web handlers and W9's observability. No client/DI imports. */
internal class ServerServices(
    store: SqliteSyncStore,
    config: ServerConfig,
    val events: EventBus,
    val inputs: ServerInputs = productionInputs(),
) {
    private val passwords = PasswordHasher(inputs)
    val tokens = TokenService(store, inputs)
    val setup = SetupService(store, inputs, passwords, config.dataDir, config.quotaRecords)
    val auth = AccountAuth(store, inputs, passwords, setup, config.quotaRecords)
    val invites = InviteService(store, inputs)
    val links = LinkService(store, inputs, config.publicUrl?.resolve("/link")?.toString() ?: "/link")
    val sessions = WebSessions(tokens, config, inputs)
    val devices = DeviceService(store, inputs)
    val backups = AccountBackups(store, config, inputs)
    val accounts = AccountService(store, store, inputs, backups)
    val admin = AdminService(store, store, inputs, backups)
    val audit = AuditLog(store, inputs)
    val metrics = ServerMetrics(store, config.dataDir)
    val updates =
        ServerUpdateNotice(if (config.updateCheck) UpdateCheckMode.ENABLED else UpdateCheckMode.DISABLED, inputs)
    val backupJob = BackupJob(store, config, backups, metrics, inputs)
    val gcJob = GcJob(store, config, inputs, metrics)
    val records = StoredRecordWriter(store, inputs, config.retentionUnsubscribedDays)

    suspend fun features(): List<String> =
        listOf("sse", "link") + if (hasPassword()) listOf("password") else emptyList()

    private val authStore = store

    private suspend fun hasPassword(): Boolean =
        authStore.readAuth {
            accounts().any {
                it.passwordHash != null &&
                    it.disabledAt == null
            }
        }
}
