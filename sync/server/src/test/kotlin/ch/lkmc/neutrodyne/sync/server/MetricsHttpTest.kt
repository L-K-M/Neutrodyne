// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertTrue

class MetricsHttpTest {
    @Test fun `authenticated sync and pull feed request and result counters`() =
        authApplication { f ->
            val grant = f.grant(f.account())
            val writer = WriterFixture().apply { now = f.now }
            client.postApi(
                "/api/v1/sync",
                SyncRequest(changes = listOf(writer.podcast(), writer.group().copy(coll = "unknown"))),
                grant.token,
            )
            client.get("/api/v1/changes") { api(grant.token) }
            val metrics = f.services.metrics.prometheus()
            assertTrue(metrics.contains("neutrodyne_changes_total{status=\"applied\"} 1"))
            assertTrue(metrics.contains("neutrodyne_changes_total{status=\"rejected\"} 1"))
            assertTrue(metrics.contains("neutrodyne_records_returned_total 2"))
            assertTrue(metrics.contains("neutrodyne_requests_total{route=\"/api/v1/sync\",status=\"200\"} 1"))
            assertTrue(metrics.contains("neutrodyne_requests_total{route=\"/api/v1/changes\",status=\"200\"} 1"))
            assertTrue(metrics.contains("neutrodyne_records 1"))
        }
}
