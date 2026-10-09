// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Entities with `ByteArray` columns must compare by content: the generated data-class equality
 * compares arrays by reference, which would make `Flow.distinctUntilChanged`, list diffing and
 * `assertEquals` see every reloaded row as different from an identical twin.
 */
class EntityEqualityTest {
    @Test
    fun credentialRowsWithIdenticalBytesAreEqual() {
        val a =
            CredentialEntity(
                id = 1,
                origin = "example.com",
                username = "u",
                secretCipher = byteArrayOf(1, 2, 3),
                iv = byteArrayOf(4, 5),
                createdAt = 9L,
            )
        val b = a.copy(secretCipher = byteArrayOf(1, 2, 3), iv = byteArrayOf(4, 5))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(secretCipher = byteArrayOf(9, 9, 9)))
        assertNotEquals(a, a.copy(secretCipher = null))
    }

    @Test
    fun descriptionRowsWithIdenticalBytesAreEqual() {
        val a = EpisodeDescriptionEntity(episodeId = 7, html = byteArrayOf(0, 1, 2))
        val b = a.copy(html = byteArrayOf(0, 1, 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(html = byteArrayOf(0, 1, 3)))
    }
}
