// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkServiceTest {
    @Test fun `concurrent failed approvals cannot bypass the account budget`() =
        authServices { f ->
            val approver = f.principal(f.grant(f.account()))
            val results =
                coroutineScope {
                    (1..20)
                        .map {
                            async {
                                runCatching { f.services.links.preview(approver, "BAD-CODE", "ip") }
                            }
                        }.awaitAll()
                }
            assertEquals(5, results.count { it.exceptionOrNull() is ProtocolException })
            assertEquals(15, results.count { it.exceptionOrNull() is AuthThrottled })
        }

    @Test fun `pending slow down approve confirm token and replay`() =
        authServices { f ->
            val owner = f.account()
            val approver = f.principal(f.grant(owner))
            val link = f.services.links.start(deviceInfo("New laptop"), "192.0.2.1")
            assertEquals(LinkPoll.Error("authorization_pending"), f.services.links.pollDevice(link.deviceCode))
            assertEquals(LinkPoll.Error("slow_down"), f.services.links.pollDevice(link.deviceCode))
            f.now += 5000
            assertEquals(LinkPoll.Error("slow_down"), f.services.links.pollDevice(link.deviceCode))
            val preview = f.services.links.preview(approver, link.userCode.lowercase(), "192.0.2.2")
            assertEquals("New laptop", preview.deviceName)
            assertEquals("device", preview.purpose)
            assertFalse(preview.sameNetwork)
            f.services.links.confirm(approver, link.userCode, true)
            f.now += 15_000
            val token = f.services.links.pollDevice(link.deviceCode) as LinkPoll.Device
            assertEquals(owner.id, f.principal(token.response).accountId)
            assertEquals(LinkPoll.Error("expired_token"), f.services.links.pollDevice(link.deviceCode))
        }

    @Test fun `confirm needs preview and preview binds both account and credential`() =
        authServices { f ->
            val alice = f.account()
            val a = f.principal(f.grant(alice))
            val second = f.principal(f.grant(alice))
            val bob = f.principal(f.grant(f.account("bob")))
            val link = f.services.links.start(deviceInfo(), "ip")
            assertFailsWith<ProtocolException> { f.services.links.confirm(a, link.userCode, true) }
            assertTrue(
                f.services.links
                    .preview(a, link.userCode, "ip")
                    .sameNetwork,
            )
            assertFailsWith<ProtocolException> { f.services.links.confirm(bob, link.userCode, true) }
            assertFailsWith<ProtocolException> { f.services.links.preview(bob, link.userCode, "ip") }
            assertFailsWith<ProtocolException> { f.services.links.confirm(second, link.userCode, true) }
            f.services.links.confirm(a, link.userCode, true)
            assertFailsWith<ProtocolException> { f.services.links.confirm(a, link.userCode, true) }
            assertEquals(
                alice.id.toString(),
                (f.services.links.pollDevice(link.deviceCode) as LinkPoll.Device).response.accountId,
            )
        }

    @Test fun `denial expiry and approved credential revocation return OAuth errors`() =
        authServices { f ->
            val approver = f.principal(f.grant(f.account()))
            val denied = f.services.links.start(deviceInfo(), "ip")
            f.services.links.preview(approver, denied.userCode, "ip")
            f.services.links.confirm(approver, denied.userCode, false)
            assertEquals(LinkPoll.Error("access_denied"), f.services.links.pollDevice(denied.deviceCode))
            val expired = f.services.links.start(deviceInfo(), "ip")
            f.now += 600_000
            assertEquals(LinkPoll.Error("expired_token"), f.services.links.pollDevice(expired.deviceCode))
            val revoked = f.services.links.start(deviceInfo(), "ip")
            f.services.links.preview(approver, revoked.userCode, "ip")
            f.services.links.confirm(approver, revoked.userCode, true)
            f.services.tokens.logout(approver)
            assertEquals(LinkPoll.Error("access_denied"), f.services.links.pollDevice(revoked.deviceCode))
        }

    @Test fun `lookup budget and failed account entries are bounded independently`() =
        authServices { f ->
            val a = f.principal(f.grant(f.account()))
            val b = f.principal(f.grant(f.account("bob")))
            val link = f.services.links.start(deviceInfo(), "ip")
            repeat(4) { f.services.links.preview(a, link.userCode, "ip") }
            assertFailsWith<ProtocolException> { f.services.links.preview(a, link.userCode, "ip") }
            assertEquals(LinkPoll.Error("expired_token"), f.services.links.pollDevice(link.deviceCode))
            repeat(4) { assertFailsWith<ProtocolException> { f.services.links.preview(a, "BAD-CODE", "ip") } }
            assertFailsWith<AuthThrottled> { f.services.links.preview(a, "BAD-CODE", "ip") }
            val other = f.services.links.start(deviceInfo(), "ip")
            f.services.links.preview(b, other.userCode, "ip")
            f.now += 900_000
            assertFailsWith<ProtocolException> { f.services.links.preview(a, "BAD-CODE", "ip") }
        }

    @Test fun `successful links do not consume failed approval budget`() =
        authServices { f ->
            val a = f.principal(f.grant(f.account()))
            repeat(6) {
                val link = f.services.links.start(deviceInfo(), "ip")
                f.services.links.preview(a, link.userCode, "ip")
                f.services.links.confirm(a, link.userCode, true)
                assertTrue(f.services.links.pollDevice(link.deviceCode) is LinkPoll.Device)
            }
        }

    @Test fun `concurrent token redemption creates exactly one device`() =
        authServices { f ->
            val owner = f.account()
            val a = f.principal(f.grant(owner))
            val link = f.services.links.start(deviceInfo(), "ip")
            f.services.links.preview(a, link.userCode, "ip")
            f.services.links.confirm(a, link.userCode, true)
            val results =
                coroutineScope { (1..8).map { async { f.services.links.pollDevice(link.deviceCode) } }.awaitAll() }
            assertEquals(1, results.count { it is LinkPoll.Device })
            assertEquals(
                2,
                f.services.devices
                    .list(a)
                    .size,
            )
        }

    @Test fun `web purpose cannot be redeemed by device endpoint or used as API token`() =
        authServices { f ->
            val a = f.principal(f.grant(f.account()))
            val web = f.services.links.startWeb("ip")
            assertEquals(
                "web",
                f.services.links
                    .preview(a, web.userCode, "ip")
                    .purpose,
            )
            f.services.links.confirm(a, web.userCode, true)
            assertEquals(LinkPoll.Error("expired_token"), f.services.links.pollDevice(web.deviceCode))
            val raw = (f.services.links.pollWeb(web.deviceCode) as LinkPoll.Web).cookie
            assertEquals(
                a.accountId,
                f.services.tokens
                    .authenticate(raw, TokenKind.WEB_SESSION)
                    .accountId,
            )
            assertFailsWith<ProtocolException> { f.services.tokens.authenticate(raw, TokenKind.DEVICE) }
            assertEquals(LinkPoll.Error("expired_token"), f.services.links.pollWeb(web.deviceCode))
        }

    @Test fun `global pending request cap survives service recreation`() =
        authServices { f ->
            val device = deviceInfo()
            f.store.writeAuth {
                repeat(1000) {
                    save(AuthLink("device$it", "user$it", device, LinkPurpose.DEVICE, "ip", f.now, f.now + 600_000))
                }
            }
            assertFailsWith<AuthThrottled> { f.services.links.start(deviceInfo(), "ip") }
            f.now += 600_000
            assertEquals(
                600,
                f.services.links
                    .start(deviceInfo(), "ip")
                    .expiresIn,
            )
        }
}
