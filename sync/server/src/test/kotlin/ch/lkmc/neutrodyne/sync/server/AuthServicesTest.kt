// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServicesTest {
    @Test fun `administrator creation through account service removes setup file`() =
        authServices { f ->
            val path = f.services.setup.initialize()!!
            f.account("admin", role = AccountRole.ADMIN)
            assertFalse(Files.exists(path))
            f.store.readAuth { assertTrue(tokens(null, TokenKind.SETUP).none { it.active(f.now) }) }
        }

    @Test fun `setup is owner-only single use and restart replaces old code`() =
        authServices { f ->
            val path = assertNotNull(f.services.setup.initialize())
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
            val old = Files.readString(path).trim()
            f.services.setup.initialize()
            val code = Files.readString(path).trim()
            assertNotEquals(old, code)
            assertFailsWith<ProtocolException> { f.services.setup.createAdmin(old, "admin") }
            val admin = f.services.setup.createAdmin(code, "ADMIN")
            assertEquals(AccountRole.ADMIN, admin.role)
            assertEquals("admin", admin.username)
            assertFalse(Files.exists(path))
            assertFailsWith<ProtocolException> { f.services.setup.createAdmin(code, "other") }
            assertNull(f.services.setup.initialize())
            f.store.readAuth { assertTrue(tokens(null, TokenKind.SETUP).none { it.active(f.now) }) }
        }

    @Test fun `only one setup contender creates an administrator`() =
        authServices { f ->
            val code = Files.readString(f.services.setup.initialize()!!).trim()
            val results =
                coroutineScope {
                    (1..2)
                        .map { index ->
                            async {
                                runCatching {
                                    f.services.setup.createAdmin(code, "admin$index")
                                }
                            }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it.isSuccess })
            f.store.readAuth { assertEquals(1, accounts().size) }
        }

    @Test fun `setup expires at 24 hours`() =
        authServices { f ->
            val code = Files.readString(f.services.setup.initialize()!!).trim()
            f.now += DAY_MS
            assertFailsWith<ProtocolException> { f.services.setup.createAdmin(code, "admin") }
        }

    @Test fun `expired invites and device collisions cannot consume a new invite`() =
        authServices { f ->
            val owner = f.account()
            val expired = f.services.invites.create(owner.id)
            f.now += DAY_MS
            assertFailsWith<ProtocolException> { f.services.invites.redeem(expired, deviceInfo()) }
            val device = deviceInfo()
            f.grant(owner, device)
            val other = f.account("bob")
            val code = f.services.invites.create(other.id)
            assertFailsWith<ProtocolException> { f.services.invites.redeem(code, device) }
            assertEquals(
                other.id.toString(),
                f.services.invites
                    .redeem(code, deviceInfo())
                    .accountId,
            )
        }

    @Test fun `secrets are hashed and token kinds cannot cross scopes`() =
        authServices { f ->
            val owner = f.account()
            val invite = f.services.invites.create(owner.id)
            assertFailsWith<ProtocolException> {
                f.services.tokens.authenticate(invite, TokenKind.DEVICE)
            }
            val grant = f.services.invites.redeem(invite, deviceInfo())
            assertTrue(grant.token.startsWith("nds_"))
            val principal = f.principal(grant)
            assertEquals(AuthSecrets.hash(grant.token), principal.tokenHash)
            f.store.readAuth {
                assertNull(token(grant.token))
                assertNull(token(invite))
                assertEquals(TokenKind.DEVICE, token(principal.tokenHash)!!.kind)
            }
            assertFailsWith<ProtocolException> {
                f.services.tokens.authenticate(grant.token, TokenKind.WEB_SESSION)
            }
            f.services.tokens.logout(principal)
            assertEquals(
                SyncErrorCode.UNAUTHORIZED,
                assertFailsWith<ProtocolException> { f.principal(grant) }.code,
            )
        }

    @Test fun `web sessions slide only hourly and expire at their boundary`() =
        authServices { f ->
            f.account(password = "correct password")
            val raw = f.services.auth.loginWeb("alice", "correct password", "Firefox")
            val first = f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            assertNull(first.deviceId)
            assertFailsWith<ProtocolException> {
                f.services.tokens.authenticate(raw, TokenKind.DEVICE)
            }
            val expiry = f.store.readAuth { token(first.tokenHash)!!.expiresAt }
            f.now += HOUR_MS - 1
            f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            assertEquals(expiry, f.store.readAuth { token(first.tokenHash)!!.expiresAt })
            f.now++
            f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            assertEquals(
                f.now + TokenService.SESSION_MS,
                f.store.readAuth { token(first.tokenHash)!!.expiresAt },
            )
            f.now += TokenService.SESSION_MS
            assertFailsWith<ProtocolException> {
                f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            }
        }

    @Test fun `Argon2id PHC parameters length and generic failure`() =
        runBlocking<Unit> {
            val passwords = PasswordHasher(productionInputs())
            val hash = passwords.hash("1234567890")
            assertTrue(hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
            assertTrue(passwords.verify("1234567890", hash))
            assertFalse(passwords.verify("123456789x", hash))
            assertFalse(passwords.verify("1234567890", null))
            assertFalse(passwords.verify("1234567890", hash.replace("m=19456", "m=999999999")))
            assertFailsWith<ProtocolException> { passwords.hash("123456789") }
            assertFailsWith<ProtocolException> { passwords.hash("a".repeat(257)) }
            assertTrue(passwords.verify("a".repeat(256), passwords.hash("a".repeat(256))))
        }

    @Test fun `first password persists notice and revokes other web sessions`() =
        authServices { f ->
            val owner = f.account()
            val device = f.grant(owner)
            val raw = productionInputs().let { AuthSecrets(it).secret("ndw_") }
            f.store.writeAuth { issueWeb(owner, raw, f.now, "Browser") }
            val web = f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            f.services.auth.changePassword(web, null, "first password")
            assertTrue(f.services.tokens.valid(web))
            val second = f.services.auth.loginWeb("alice", "first password", null)
            val other = f.services.tokens.authenticate(second, TokenKind.WEB_SESSION)
            assertFailsWith<ProtocolException> {
                f.services.auth.changePassword(other, null, "new password")
            }
            assertFailsWith<ProtocolException> {
                f.services.auth.changePassword(other, "bad password", "new password")
            }
            val firstSet =
                f.services.accounts
                    .summary(f.principal(device))
                    .passwordSetAt
            assertNotNull(firstSet)
            f.now += HOUR_MS
            f.services.auth.changePassword(web, "first password", "new password")
            assertFalse(f.services.tokens.valid(other))
            assertEquals(
                firstSet,
                f.services.accounts
                    .summary(f.principal(device))
                    .passwordSetAt,
            )
            f.now += 30 * DAY_MS
            assertNull(
                f.services.accounts
                    .summary(f.principal(device))
                    .passwordSetAt,
            )
        }

    @Test fun `password login backoff is account-wide and tokens remain usable`() =
        authServices { f ->
            f.account(password = "correct password")
            repeat(10) {
                assertFailsWith<ProtocolException> {
                    f.services.auth.login("alice", "wrong password", deviceInfo())
                }
            }
            assertEquals(
                60L,
                assertFailsWith<AuthThrottled> {
                    f.services.auth.login("alice", "correct password", deviceInfo())
                }.retrySeconds,
            )
            f.now += 60_000
            assertFailsWith<ProtocolException> {
                f.services.auth.login("alice", "wrong password", deviceInfo())
            }
            assertEquals(
                120L,
                assertFailsWith<AuthThrottled> {
                    f.services.auth.login("alice", "correct password", deviceInfo())
                }.retrySeconds,
            )
            f.now += 120_000
            assertEquals(
                "alice",
                f.services.auth
                    .login("ALICE", "correct password", deviceInfo())
                    .username,
            )
            assertFailsWith<ProtocolException> {
                f.services.auth.login("alice", "wrong password", deviceInfo())
            }
        }

    @Test fun `constant time helper uses JDK equality for equal mismatched and different lengths`() {
        assertTrue(AuthSecrets.same("abc", "abc"))
        assertFalse(AuthSecrets.same("abc", "abd"))
        assertFalse(AuthSecrets.same("abc", "abcd"))
        assertFalse(AuthSecrets.same("", "a"))
        // No timing assertion: scheduler noise cannot prove constant time. The implementation
        // delegates to MessageDigest.isEqual for token hashes, passwords and synchronizers.
    }
}
