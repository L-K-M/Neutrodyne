// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import java.nio.file.Path
import java.util.UUID

/** Run with only the fat JAR and compiled test probe on the classpath, under the S16 heap flags. */
internal object PackagedAuthProbe {
    @JvmStatic fun main(args: Array<String>) =
        runBlocking {
            check(
                PasswordHasher::class.java.protectionDomain.codeSource.location ==
                    Argon2BytesGenerator::class.java.protectionDomain.codeSource.location,
            )
            val inputs = productionInputs()
            val passwords = PasswordHasher(inputs)
            coroutineScope {
                (1..6)
                    .map {
                        async {
                            val password = "packaged password $it"
                            check(passwords.verify(password, passwords.hash(password)))
                        }
                    }.awaitAll()
            }
            SqliteSyncStore(Path.of(args.single())).use { store ->
                val setup = SetupService(store, inputs, passwords, Path.of(args.single()))
                val auth = AccountAuth(store, inputs, passwords, setup)
                val owner = auth.createAccount("packaged", AccountRole.ADMIN, "packaged password")
                val grants = InviteService(store, inputs)
                val device =
                    DeviceInfoDto(UUID.randomUUID().toString(), "Packaged probe", "linux", "probe", "1111111111111111")
                val grant = grants.redeem(grants.create(owner.id), device)
                val tokens = TokenService(store, inputs)
                val principal = tokens.authenticate(grant.token, TokenKind.DEVICE)
                check(principal.accountId == owner.id)
                tokens.logout(principal)
                check(!tokens.valid(principal))
            }
            println("Packaged Argon2id, concurrent hash bound, SQLite auth and revocation passed")
        }
}
