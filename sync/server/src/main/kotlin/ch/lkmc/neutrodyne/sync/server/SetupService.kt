// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Only the path may be printed. File publication follows durable invalidation of older codes. */
internal class SetupService(
    private val store: AuthStore,
    private val inputs: ServerInputs,
    private val passwords: PasswordHasher,
    private val dataDir: Path,
    private val quota: Long = ServerDefaults.QUOTA_RECORDS,
) {
    private val mutex = Mutex()
    private val secrets = AuthSecrets(inputs)
    private val tokenFile = dataDir.resolve("setup-token")

    suspend fun initialize(): Path? =
        mutex.withLock {
            val code = secrets.code(16)
            val now = inputs.wallMs()
            val needed =
                store.writeAuth {
                    tokens(null, TokenKind.SETUP).filter { it.active(now) }.forEach { save(it.copy(revokedAt = now)) }
                    if (accounts().any { it.role == AccountRole.ADMIN }) return@writeAuth false
                    save(AuthToken(AuthSecrets.hash(code), null, null, TokenKind.SETUP, now, expiresAt = now + DAY_MS))
                    true
                }
            withContext(Dispatchers.IO) {
                if (!needed) {
                    Files.deleteIfExists(tokenFile)
                    return@withContext null
                }
                val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                val temporary = Files.createTempFile(dataDir, ".setup-", ".tmp", permissions)
                try {
                    Files.writeString(temporary, AuthSecrets.display(code) + "\n")
                    Files.move(
                        temporary,
                        tokenFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    tokenFile
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }

    suspend fun createAdmin(
        code: String,
        username: String,
        password: String? = null,
    ): AuthAccount =
        mutex.withLock {
            val name = AuthSecrets.username(username)
            val hash = password?.let { passwords.hash(it) }
            val epoch = secrets.epoch()
            val now = inputs.wallMs()
            val codeHash = AuthSecrets.hash(AuthSecrets.normalizeCode(code))
            val result =
                store.writeAuth {
                    if (accounts().any { it.role == AccountRole.ADMIN }) forbidden()
                    val token = token(codeHash) ?: unauthorized()
                    val matches = AuthSecrets.same(codeHash, token.hash)
                    if (!matches || token.kind != TokenKind.SETUP || !token.active(now)) unauthorized()
                    if (account(name) != null) invalidAuth()
                    val account = createAccount(name, AccountRole.ADMIN, hash, epoch, now, quota)
                    tokens(null, TokenKind.SETUP).forEach { save(it.copy(consumedAt = now)) }
                    audit(account.id, "setup_completed", now)
                    account
                }
            withContext(Dispatchers.IO) { Files.deleteIfExists(tokenFile) }
            result
        }
}
