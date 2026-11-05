// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.testing.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions

/** Compiled only into each feature's test source set, never a runtime dependency. */
@Composable
internal fun ScreenSnapshot(
    dark: Boolean,
    platform: PlatformKind,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val navigator = remember { RecordingAppNavigator() }
    val owner = remember { SnapshotLifecycle() }
    DisposableEffect(owner) { onDispose { owner.lifecycle.currentState = Lifecycle.State.DESTROYED } }
    NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState(dark, 0.0, true)) {
        CompositionLocalProvider(
            LocalAppNavigator provides navigator,
            LocalPlatformActions provides SnapshotActions,
            LocalUiClock provides TestClock(),
            LocalPlatformKind provides platform,
            LocalLifecycleOwner provides owner,
        ) {
            Surface(modifier.fillMaxSize()) { content() }
        }
    }
}

private class SnapshotLifecycle : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}

private object SnapshotActions : PlatformActions {
    override val urls = ExternalUrlOpener { OpenResult.NO_HANDLER }
    override val share = null
    override val reveal = null
    override val notifications = null
    override val saver = FileSaver { _, _ -> null }
    override val files =
        object : FilePicker {
            override suspend fun pickFile(
                mimeTypes: List<String>,
                extensions: List<String>,
            ): String? = null

            override suspend fun pickFolder(title: String): String? = null
        }
}
