// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `CredentialDao.putByOrigin` / `referenceCount` and `PodcastDao.setCredentialId` — the
 * primitives 03's credential-commit sequences use (M1b). `putByOrigin` must keep an existing
 * origin's row ID so `podcast.credentialId` references stay valid; `credential.origin` has no
 * unique index, so the replace is a read-then-write in one transaction, never
 * delete-and-insert (02 forbids `REPLACE` on this FK parent).
 */
class CredentialDaoTest {
    @Test
    fun putByOriginInsertsANewRowAndReturnsItsId() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val id = db.credentialDao().putByOrigin(credential("https://a", "u", byteArrayOf(1)))

                val row = db.credentialDao().byOrigin("https://a").single()
                assertEquals(id, row.id)
                assertEquals("u", row.username)
                assertContentEquals(byteArrayOf(1), row.secretCipher)
            } finally {
                db.close()
            }
        }

    @Test
    fun putByOriginReplacesInPlaceKeepingTheRowIdAndReferences() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val id = db.credentialDao().putByOrigin(credential("https://a", "u1", byteArrayOf(1)))
                val podcast =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-1") { copy(credentialId = id) },
                    )

                val again = db.credentialDao().putByOrigin(credential("https://a", "u2", byteArrayOf(2)))

                // Replace, not insert: same row ID, still one row for the origin.
                assertEquals(id, again)
                val row = db.credentialDao().byOrigin("https://a").single()
                assertEquals(id, row.id)
                assertEquals("u2", row.username)
                assertContentEquals(byteArrayOf(2), row.secretCipher)

                // The referencing podcast never lost its credentialId.
                assertEquals(id, db.podcastDao().byId(podcast)?.credentialId)
                assertEquals(1, db.credentialDao().referenceCount(id))
            } finally {
                db.close()
            }
        }

    @Test
    fun referenceCountCountsReferencingPodcasts() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val id = db.credentialDao().putByOrigin(credential("https://a", "u", null))
                assertEquals(0, db.credentialDao().referenceCount(id))

                val p1 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-1"))
                val p2 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-2"))
                db.podcastDao().setCredentialId(p1, id)
                db.podcastDao().setCredentialId(p2, id)
                assertEquals(2, db.credentialDao().referenceCount(id))

                db.podcastDao().setCredentialId(p2, null)
                assertEquals(1, db.credentialDao().referenceCount(id))
            } finally {
                db.close()
            }
        }

    @Test
    fun setCredentialIdSetsAndClearsTheReference() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val id = db.credentialDao().putByOrigin(credential("https://a", "u", null))
                val podcast = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-1"))
                assertNull(db.podcastDao().byId(podcast)?.credentialId)

                db.podcastDao().setCredentialId(podcast, id)
                assertEquals(id, db.podcastDao().byId(podcast)?.credentialId)

                db.podcastDao().setCredentialId(podcast, null)
                assertNull(db.podcastDao().byId(podcast)?.credentialId)
            } finally {
                db.close()
            }
        }

    private fun credential(
        origin: String,
        username: String,
        secretCipher: ByteArray?,
        createdAt: Long = 1,
    ) = CredentialEntity(
        origin = origin,
        username = username,
        secretCipher = secretCipher,
        iv = null,
        createdAt = createdAt,
    )
}
