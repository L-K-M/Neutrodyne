// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Per-host native builds of `:playback:native` (`buildNdmedia`, `buildFfmpeg`, `assembleFfmpegSource`,
 * `checkNativeLicences`). Empty until MD0 (01 Convention plugins, 11 Desktop playback engine).
 */
class DesktopNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("neutrodyne.desktop.library")
    }
}
