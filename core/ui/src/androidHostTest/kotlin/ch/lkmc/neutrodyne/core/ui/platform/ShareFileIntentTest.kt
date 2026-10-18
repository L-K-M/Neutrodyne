// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ShareSheet.shareFile` intent (05 Destinations): the file's display name must reach the
 * receiving app through `EXTRA_TITLE` and the ClipData label, and the read grant rides the
 * ClipData — the extras alone do not grant the stream URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareFileIntentTest {
    @Test
    fun shareIntentCarriesNameMimeStreamLabelAndGrant() {
        val uri = "content://ch.lkmc.neutrodyne.debug.fileprovider/export/neutrodyne-tech.opml"
        val intent = shareFileIntent(uri, "text/x-opml", "neutrodyne-tech.opml")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/x-opml", intent.type)
        assertEquals(Uri.parse(uri), intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals("neutrodyne-tech.opml", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val clip = intent.clipData
        assertEquals("neutrodyne-tech.opml", clip?.description?.label)
        assertEquals(Uri.parse(uri), clip?.getItemAt(0)?.uri)
    }
}
