// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `CreateDocumentContract` + `FileSaver.create` wiring (05 Destinations): the caller's MIME
 * type must reach `ACTION_CREATE_DOCUMENT` — `ActivityResultContracts.CreateDocument` bakes it
 * into the contract instead — because `text/x-opml` is what stops providers renaming the
 * export to `.xml`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateDocumentContractTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun createIntentCarriesTheRequestedMimeAndName() {
        val intent =
            CreateDocumentContract().createIntent(
                context,
                CreateDocumentRequest("neutrodyne.opml", "text/x-opml"),
            )

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/x-opml", intent.type)
        assertEquals("neutrodyne.opml", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun createIntentCarriesTheBackupMime() {
        val intent =
            CreateDocumentContract().createIntent(
                context,
                CreateDocumentRequest("backup.zip", "application/zip"),
            )

        assertEquals("application/zip", intent.type)
        assertEquals("backup.zip", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun parseResultMapsOkToTheUriAndEverythingElseToNull() {
        val contract = CreateDocumentContract()
        val uri = Uri.parse("content://provider/doc/7")

        assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
    }

    @Test
    fun saverCreateLaunchesTheRequestWithItsMimeType() =
        runBlocking {
            val createLauncher = RecordingCreateLauncher()
            val createResult = PendingResult<Uri?>()
            val actions =
                AndroidPlatformActions(
                    context,
                    PendingResult(),
                    createResult,
                    PendingResult(),
                    NullLauncher(),
                    createLauncher,
                    NullLauncher(),
                )

            var returned: String? = null
            val job = launch { returned = actions.saver.create("feeds.opml", "text/x-opml") }
            yield()
            assertEquals(CreateDocumentRequest("feeds.opml", "text/x-opml"), createLauncher.launched)

            // The provider's answer flows back through PendingResult.complete as a string URI.
            createResult.complete(Uri.parse("content://provider/doc/9"))
            job.join()
            assertEquals("content://provider/doc/9", returned)
        }
}

/** Records the request `FileSaver.create` hands the launcher. */
private class RecordingCreateLauncher : ActivityResultLauncher<CreateDocumentRequest>() {
    var launched: CreateDocumentRequest? = null

    override fun launch(
        input: CreateDocumentRequest,
        options: ActivityOptionsCompat?,
    ) {
        launched = input
    }

    override fun unregister() {}

    override val contract: ActivityResultContract<CreateDocumentRequest, Uri?> =
        CreateDocumentContract()
}

/** The launchers this test never reaches; `launch`/`unregister` are no-ops. */
private class NullLauncher<I> : ActivityResultLauncher<I>() {
    override fun launch(
        input: I,
        options: ActivityOptionsCompat?,
    ) {
    }

    override fun unregister() {}

    override val contract: ActivityResultContract<I, *>
        get() = error("unreachable")
}
