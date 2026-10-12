// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddModelsTest {
    /** Feed passwords are secrets (N3): a data-class toString must never print one. */
    @Test
    fun basicCredentialsToStringRedactsThePassword() {
        val text = BasicCredentials("alice", "s3cr3t-pass").toString()
        assertFalse(text.contains("s3cr3t-pass"), text)
        assertTrue(text.contains("alice"), text)
    }
}
