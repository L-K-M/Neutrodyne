// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApiRoutesTest {
    @Test fun `HTTP sync mixed merge pages limits and acknowledgements use applied request cursor`() =
        authApplication { f ->
            val grant = f.grant(f.account())
            val w = WriterFixture().apply { now = f.now }
            val pushed =
                client.postApi(
                    "/api/v1/sync",
                    SyncRequest(changes = listOf(w.podcast(), w.group(), w.group().copy(coll = "unknown"))),
                    grant.token,
                )
            pushed.expect(HttpStatusCode.OK)
            val result = pushed.decoded<SyncResponse>()
            assertEquals(
                listOf("applied", "applied", "rejected"),
                result.results.map { it.status },
            )
            assertEquals(2, result.records.size)
            val first =
                SyncJson.decodeFromString<ChangesPage>(
                    client
                        .get("/api/v1/changes?limit=1") {
                            api(grant.token)
                        }.bodyAsText(),
                )
            assertEquals(1, first.records.size)
            assertTrue(first.hasMore)
            assertTrue(result.cursor != first.cursor)
            val next =
                SyncJson.decodeFromString<ChangesPage>(
                    client
                        .get(
                            "/api/v1/changes?since=${first.cursor}&limit=1",
                        ) {
                            api(grant.token)
                        }.bodyAsText(),
                )
            assertFalse(next.hasMore)
            assertEquals(result.cursor, next.cursor)
            DriverManager
                .getConnection(
                    "jdbc:sqlite:${f.config.dataDir.resolve(SqliteSyncStore.DATABASE_FILE)}",
                ).use { jdbc ->
                    assertEquals(
                        first.records.single().seq,
                        jdbc
                            .query("SELECT last_cursor FROM device WHERE id=?", grant.deviceId) { it.getLong(1) }
                            .single(),
                    )
                }
            val empty =
                SyncJson.decodeFromString<SyncResponse>(
                    client
                        .postApi("/api/v1/sync", SyncRequest(result.cursor), grant.token)
                        .bodyAsText(),
                )
            assertTrue(empty.records.isEmpty())
            assertEquals(result.cursor, empty.cursor)
            val staleChange = w.write(groupG, "name", JsonPrimitive("Old"), w.clock(-1000))
            val stale =
                client
                    .postApi("/api/v1/sync", SyncRequest(result.cursor, listOf(staleChange)), grant.token)
                    .decoded<SyncResponse>()
            assertEquals("stale", stale.results.single().status)
            assertEquals(result.cursor, stale.cursor)
            assertEquals(groupG.id.toWire(), stale.records.single().id)
            assertEquals(
                "\"News\"",
                stale.records
                    .single()
                    .fields
                    .getValue("name")
                    .v
                    .toString(),
            )
            client.get("/api/v1/changes?limit=bad") { api(grant.token) }.expect(HttpStatusCode.BadRequest)
            client
                .postApi(
                    "/api/v1/sync",
                    SyncRequest("c:ffffffff:0", listOf(w.group(H, "Other"))),
                    grant.token,
                ).expect(HttpStatusCode.Gone)
        }

    @Test fun `accounts with identical record ids have isolated state and summary`() =
        authApplication { f ->
            val a = f.grant(f.account())
            val b = f.grant(f.account("bob"))
            val w = WriterFixture().apply { now = f.now }
            client.postApi("/api/v1/sync", SyncRequest(changes = listOf(w.group(name = "Alice"))), a.token)
            client.postApi("/api/v1/sync", SyncRequest(changes = listOf(w.group(name = "Bob"))), b.token)
            val alice = client.get("/api/v1/changes") { api(a.token) }.bodyAsText()
            val bob = client.get("/api/v1/changes") { api(b.token) }.bodyAsText()
            assertTrue(alice.contains("Alice"))
            assertFalse(alice.contains("Bob"))
            assertTrue(bob.contains("Bob"))
            assertFalse(bob.contains("Alice"))
            val summary =
                SyncJson.decodeFromString<AccountSummary>(
                    client.get("/api/v1/account") { api(a.token) }.bodyAsText(),
                )
            assertEquals("alice", summary.username)
            assertEquals(1, summary.counts.groups)
            assertEquals(1L, summary.records)
            assertEquals(1, summary.devices)
            client
                .get(
                    "/api/v1/changes?since=${SyncJson.decodeFromString<ChangesPage>(alice).cursor}",
                ) { api(b.token) }
                .expect(HttpStatusCode.Gone)
        }

    @Test fun `library reset preserves later uploads and purge revokes only its account`() =
        authApplication { f ->
            val a = f.grant(f.account())
            val b = f.grant(f.account("bob"))
            val w = WriterFixture().apply { now = f.now }
            val staged =
                SyncJson.decodeFromString<SyncResponse>(
                    client
                        .postApi(
                            "/api/v1/sync",
                            SyncRequest(changes = listOf(w.podcast(), w.group())),
                            a.token,
                        ).bodyAsText(),
                )
            client.postApi("/api/v1/sync", SyncRequest(staged.cursor, listOf(w.group(H, "Later"))), a.token)
            client
                .postApi(
                    "/api/v1/account/reset",
                    AccountResetRequest("DELETE", "library"),
                    a.token,
                ).expect(HttpStatusCode.BadRequest)
            val reset =
                client.postApi(
                    "/api/v1/account/reset",
                    AccountResetRequest("DELETE", "library", staged.cursor),
                    a.token,
                )
            reset.expect(HttpStatusCode.OK)
            val summary = reset.decoded<AccountSummary>()
            assertEquals(0, summary.counts.podcasts)
            assertEquals(1, summary.counts.groups)
            val epoch = f.store.read(a.accountId.toLong()) { account.cursor.epoch }
            client
                .postApi(
                    "/api/v1/account/reset",
                    AccountResetRequest("bad", "purge"),
                    a.token,
                ).expect(HttpStatusCode.BadRequest)
            val purged =
                client.postApi("/api/v1/account/reset", AccountResetRequest("DELETE", "purge"), a.token)
            purged.expect(HttpStatusCode.OK)
            assertEquals(0L, purged.decoded<AccountSummary>().records)
            client.get("/api/v1/account") { api(a.token) }.expect(HttpStatusCode.Unauthorized)
            client.get("/api/v1/account") { api(b.token) }.expect(HttpStatusCode.OK)
            assertNotEquals(epoch, f.store.read(a.accountId.toLong()) { account.cursor.epoch })
            f.store.read(a.accountId.toLong()) {
                assertTrue(records().none())
                assertTrue(redirects().isEmpty())
            }
        }

    @Test fun `discovery capabilities follow functional services and password state`() =
        authApplication { f ->

            suspend fun features() =
                SyncJson
                    .decodeFromString<DiscoveryDocument>(
                        client.get(WellKnownRoutes.WELL_KNOWN_PATH).bodyAsText(),
                    ).features
            assertEquals(listOf("sse", "link"), features())
            f.account(password = "correct password")
            assertEquals(listOf("sse", "link", "password"), features())
        }

    @Test fun `authenticated account bucket is independent between accounts`() =
        authApplication { f ->
            val a = f.grant(f.account())
            val b = f.grant(f.account("bob"))
            repeat(30) {
                client.get("/api/v1/account") { api(a.token) }.expect(HttpStatusCode.OK)
            }
            client.get("/api/v1/account") { api(a.token) }.expect(HttpStatusCode.TooManyRequests)
            client.get("/api/v1/account") { api(b.token) }.expect(HttpStatusCode.OK)
        }
}
