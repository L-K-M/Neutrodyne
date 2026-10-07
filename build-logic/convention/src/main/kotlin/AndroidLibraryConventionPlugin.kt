// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android-only libraries `:playback:impl` and `:youtube:ytdlp` (01 Convention plugins). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            assertKotlinPluginVersion()
            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                namespace = neutrodyneNamespace
                if (file("consumer-rules.pro").exists()) defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
            pluginManager.apply("neutrodyne.metro")
            pluginManager.apply("neutrodyne.android.lint")
            pluginManager.apply("neutrodyne.android.testing")
            registerDependencyPolicy()
            if (path == ":youtube:ytdlp") registerPythonPolicy(desktopLock = false)
        }
}
