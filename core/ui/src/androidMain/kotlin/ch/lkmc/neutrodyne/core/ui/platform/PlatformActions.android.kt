// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Android [PlatformActions] (08 Modules). `MainActivity` calls this in composition so the
 * activity-result launchers register with the activity's registry, then passes the result to
 * `NeutrodyneRoot` as `platformActions`.
 */
@Composable
public fun rememberAndroidPlatformActions(): PlatformActions {
    val context = LocalContext.current
    val openFile = remember { PendingResult<Uri?>() }
    val createFile = remember { PendingResult<Uri?>() }
    val notification = remember { PendingResult<Boolean>() }

    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            openFile.complete(it)
        }
    val createLauncher =
        rememberLauncherForActivityResult(CreateDocumentContract()) {
            createFile.complete(it)
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notification.complete(it)
        }

    val actions =
        remember(context, openLauncher, createLauncher, notificationLauncher) {
            AndroidPlatformActions(
                context,
                openFile,
                createFile,
                notification,
                openLauncher,
                createLauncher,
                notificationLauncher,
            )
        }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        (actions.notifications as? AndroidNotificationPermissionRequester)?.refresh()
    }
    return actions
}

internal class AndroidPlatformActions(
    private val context: Context,
    private val openFile: PendingResult<Uri?>,
    private val createFile: PendingResult<Uri?>,
    private val notification: PendingResult<Boolean>,
    private val openLauncher: ActivityResultLauncher<Array<String>>,
    private val createLauncher: ActivityResultLauncher<CreateDocumentRequest>,
    private val notificationLauncher: ActivityResultLauncher<String>,
) : PlatformActions {
    override val urls: ExternalUrlOpener =
        ExternalUrlOpener { url ->
            val intent =
                if (url.startsWith("mailto:")) {
                    Intent(Intent.ACTION_SENDTO, Uri.parse(url))
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                }
            try {
                context.startActivity(intent)
                OpenResult.OPENED
            } catch (e: ActivityNotFoundException) {
                OpenResult.NO_HANDLER
            }
        }

    override val share: ShareSheet =
        object : ShareSheet {
            override fun shareText(
                text: String,
                subject: String?,
            ) {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text)
                        .putExtra(Intent.EXTRA_SUBJECT, subject)
                context.startActivity(Intent.createChooser(send, null))
            }

            override fun shareFile(
                uri: String,
                mimeType: String,
            ) {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType(mimeType)
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(null, Uri.parse(uri))
                context.startActivity(Intent.createChooser(send, null))
            }
        }

    override val files: FilePicker =
        object : FilePicker {
            override suspend fun pickFile(
                mimeTypes: List<String>,
                extensions: List<String>,
            ): String? =
                suspendCancellableCoroutine { cont ->
                    // An empty list means "any type"; OpenDocument still needs at least one MIME type.
                    val types = mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
                    openFile.start(cont.contramap { it?.toString() }) { openLauncher.launch(types) }
                }

            // SAF folder picking is v1.x (08 Platform actions); M0a picks files only.
            override suspend fun pickFolder(title: String): String? = null
        }

    override val saver: FileSaver =
        FileSaver { suggestedName, mimeType ->
            // 05 Destinations: the MIME type rides the request — CreateDocument's own contract
            // fixes the type at construction, so the launcher uses the per-request contract.
            suspendCancellableCoroutine { cont ->
                createFile.start(cont.contramap { it?.toString() }) {
                    createLauncher.launch(CreateDocumentRequest(suggestedName, mimeType))
                }
            }
        }

    // "Show in folder" is a desktop action (08 Modules).
    override val reveal: RevealInFolder? = null

    override val notifications: NotificationPermissionRequester? =
        if (Build.VERSION.SDK_INT < NOTIFICATION_PERMISSION_SDK) {
            null
        } else {
            AndroidNotificationPermissionRequester(context) { onResult ->
                notification.start(onResult)
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
}

/** POST_NOTIFICATIONS exists from API 33 (Tiramisu). */
private const val NOTIFICATION_PERMISSION_SDK = 33

/** One `ACTION_CREATE_DOCUMENT` launch's input — name plus the MIME type [FileSaver.create] was given. */
internal data class CreateDocumentRequest(
    val suggestedName: String,
    val mimeType: String,
)

/**
 * `ActivityResultContracts.CreateDocument` bakes the MIME type into its constructor, so it cannot
 * answer [FileSaver.create]'s per-call type (05 Destinations: `text/x-opml`, `application/zip`).
 * This contract is the same `ACTION_CREATE_DOCUMENT` intent with the type carried per launch.
 */
internal class CreateDocumentContract : ActivityResultContract<CreateDocumentRequest, Uri?>() {
    override fun createIntent(
        context: Context,
        input: CreateDocumentRequest,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.suggestedName)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * One outstanding activity-result call. [start] suspends the caller until [complete] delivers the
 * launcher result; a second [start] while one is pending finishes the first with `null`.
 */
internal class PendingResult<T> {
    private var continuation: Continuation<T>? = null

    fun start(
        continuation: Continuation<T>,
        launch: () -> Unit,
    ) {
        this.continuation?.resumeWithException(kotlinx.coroutines.CancellationException("superseded"))
        this.continuation = continuation
        launch()
    }

    fun start(onResult: (T) -> Unit) {
        continuation?.resumeWithException(kotlinx.coroutines.CancellationException("superseded"))
        continuation =
            Continuation(kotlin.coroutines.EmptyCoroutineContext) { result ->
                result.onSuccess(onResult)
            }
    }

    fun complete(value: T) {
        continuation?.resume(value)
        continuation = null
    }
}

/** Maps the launcher's `Uri` result onto the caller's `String` continuation. */
private fun <T, R> Continuation<R>.contramap(map: (T) -> R): Continuation<T> =
    Continuation(context) { result -> resumeWith(result.map(map)) }
