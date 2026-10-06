// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

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

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        openFile.complete(it)
    }
    val createLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) {
            createFile.complete(it)
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notification.complete(it)
        }

    return remember(context, openLauncher, createLauncher, notificationLauncher) {
        AndroidPlatformActions(context, openFile, createFile, notification, openLauncher, createLauncher, notificationLauncher)
    }
}

private class AndroidPlatformActions(
    private val context: Context,
    private val openFile: PendingResult<Uri?>,
    private val createFile: PendingResult<Uri?>,
    private val notification: PendingResult<Boolean>,
    private val openLauncher: ActivityResultLauncher<Array<String>>,
    private val createLauncher: ActivityResultLauncher<String>,
    private val notificationLauncher: ActivityResultLauncher<String>,
) : PlatformActions {
    override val urls: ExternalUrlOpener = ExternalUrlOpener { url ->
        val intent = if (url.startsWith("mailto:")) {
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

    override val share: ShareSheet = object : ShareSheet {
        override fun shareText(text: String, subject: String?) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, subject)
            context.startActivity(Intent.createChooser(send, null))
        }

        override fun shareFile(uri: String, mimeType: String) {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, Uri.parse(uri))
            context.startActivity(Intent.createChooser(send, null))
        }
    }

    override val files: FilePicker = object : FilePicker {
        override suspend fun pickFile(mimeTypes: List<String>, extensions: List<String>): String? =
            suspendCancellableCoroutine { cont ->
                // An empty list means "any type"; OpenDocument still needs at least one MIME type.
                val types = mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
                openFile.start(cont.contramap { it?.toString() }) { openLauncher.launch(types) }
            }

        // SAF folder picking is v1.x (08 Platform actions); M0a picks files only.
        override suspend fun pickFolder(title: String): String? = null
    }

    override val saver: FileSaver = FileSaver { suggestedName, _ ->
        // CreateDocument's MIME type is fixed at construction; "*/*" is used and the document's
        // real type is set by the writer (05's export writes zip/opml content regardless).
        suspendCancellableCoroutine { cont ->
            createFile.start(cont.contramap { it?.toString() }) { createLauncher.launch(suggestedName) }
        }
    }

    // "Show in folder" is a desktop action (08 Modules).
    override val reveal: RevealInFolder? = null

    override val notifications: NotificationPermissionRequester? =
        if (Build.VERSION.SDK_INT < NOTIFICATION_PERMISSION_SDK) {
            null
        } else {
            object : NotificationPermissionRequester {
                override val granted: Boolean
                    get() = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

                override fun request(onResult: (Boolean) -> Unit) {
                    notification.start { granted -> onResult(granted) }
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
}

/** POST_NOTIFICATIONS exists from API 33 (Tiramisu). */
private const val NOTIFICATION_PERMISSION_SDK = 33

/**
 * One outstanding activity-result call. [start] suspends the caller until [complete] delivers the
 * launcher result; a second [start] while one is pending finishes the first with `null`.
 */
private class PendingResult<T> {
    private var continuation: Continuation<T>? = null

    fun start(continuation: Continuation<T>, launch: () -> Unit) {
        this.continuation?.resumeWithException(kotlinx.coroutines.CancellationException("superseded"))
        this.continuation = continuation
        launch()
    }

    fun start(onResult: (T) -> Unit) {
        continuation?.resumeWithException(kotlinx.coroutines.CancellationException("superseded"))
        continuation = Continuation(kotlin.coroutines.EmptyCoroutineContext) { result ->
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
