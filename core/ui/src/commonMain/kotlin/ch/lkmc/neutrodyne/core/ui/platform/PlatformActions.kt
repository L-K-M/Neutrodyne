// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform-only UI actions (08 Modules, D83). Features never call `Intent`s, AWT or D-Bus; they
 * read [LocalPlatformActions], which the shell provides from `:core:ui`'s platform source set
 * (`MainActivity` calls `rememberAndroidPlatformActions()`, `NeutrodyneWindow` calls
 * `rememberDesktopPlatformActions(portal)`). A member is `null` where the platform has no such
 * action; screens hide the corresponding control then.
 */
public interface PlatformActions {
    /** Every link, "Watch on YouTube", update and help links. */
    public val urls: ExternalUrlOpener

    /** Android share sheet; `null` on the desktop ("Copy link" instead). */
    public val share: ShareSheet?

    /** OPML, backup and import files; the desktop also picks folders. */
    public val files: FilePicker

    /** Export, backup and diagnostics export. */
    public val saver: FileSaver

    /** Desktop only: "Show in Explorer / Finder / Files". */
    public val reveal: RevealInFolder?

    /** Android 13+ only; `null` on the desktop and on API < 33. */
    public val notifications: NotificationPermissionRequester?
}

/** How an external-URL open ended. */
public enum class OpenResult {
    OPENED,
    NO_HANDLER,
}

/** Opens [url] in the platform handler (browser, YouTube app, `mailto:`). */
public fun interface ExternalUrlOpener {
    public fun open(url: String): OpenResult
}

/** Android `ACTION_SEND` sheet. Not available on the desktop. */
public interface ShareSheet {
    public fun shareText(text: String, subject: String?)

    public fun shareFile(uri: String, mimeType: String)
}

/**
 * Document/folder picking. [pickFile] returns a `content:` or `file:` URI, or `null` when
 * cancelled. [pickFolder] is a desktop action; Android returns `null` at M0a (SAF folders are v1.x).
 */
public interface FilePicker {
    public suspend fun pickFile(mimeTypes: List<String>, extensions: List<String>): String?

    public suspend fun pickFolder(title: String): String?
}

/** Creates a document the caller then writes to; `null` = the user cancelled. */
public fun interface FileSaver {
    public suspend fun create(suggestedName: String, mimeType: String): String?
}

/** Reveals [path] in the platform file manager; desktop only. */
public fun interface RevealInFolder {
    public fun reveal(path: String): Boolean
}

/** Wraps the Android 13+ `POST_NOTIFICATIONS` request (08 Permission prompts). */
public interface NotificationPermissionRequester {
    public val granted: Boolean

    public fun request(onResult: (Boolean) -> Unit)
}

/** Provided by `NeutrodyneRoot` from the shell's platform implementation. */
public val LocalPlatformActions: androidx.compose.runtime.ProvidableCompositionLocal<PlatformActions> =
    staticCompositionLocalOf { error("LocalPlatformActions not provided by the shell") }
