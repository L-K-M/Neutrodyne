// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportHttpTest {
    @Test fun authenticatedDownloadIsARealZipAndRevocationClosesAccess() =
        authApplication { f ->
            val owner = f.account()
            val grant = f.grant(owner)
            val fixture = WriterFixture()
            f.services.records.push(f.principal(grant), SyncRequest(null, listOf(fixture.podcast())))
            val raw = fixture.state(episode(), "fav", JsonPrimitive(true))
            f.store.write(owner.id) {
                repeat(STREAMED_EPISODES) { index ->
                    put(StoredRecord(episode(episode = "g:http-$index"), raw, nextSequence(), null, f.now, null))
                }
            }
            val response = client.get("/api/v1/account/export") { api(grant.token) }
            response.expect(HttpStatusCode.OK)
            val names = mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(response.body<ByteArray>())).use { zip ->
                while (true) {
                    val name = (zip.nextEntry ?: break).name
                    names += name
                    if (name == "episodes.jsonl") {
                        assertEquals(STREAMED_EPISODES, zip.readBytes().count { it == '\n'.code.toByte() })
                    }
                }
            }
            assertEquals(6, names.size)
            assertTrue("manifest.json" in names)
            f.services.tokens.logout(f.principal(grant))
            client.get("/api/v1/account/export") { api(grant.token) }.expect(HttpStatusCode.Unauthorized)
        }

    @Test fun exportFailureIsReportedBeforeSuccessHeaders() =
        authApplication { f ->
            val owner = f.account()
            val grant = f.grant(owner)
            val blocked = f.config.dataDir.resolve("account-backups/${owner.id}")
            Files.createDirectories(blocked.parent)
            Files.createFile(blocked)
            client.get("/api/v1/account/export") { api(grant.token) }.expect(HttpStatusCode.InternalServerError)
        }

    private companion object {
        const val STREAMED_EPISODES = 2_000
    }
}
