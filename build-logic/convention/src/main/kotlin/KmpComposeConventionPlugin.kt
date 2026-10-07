// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Shared Compose Multiplatform modules: `:core:designsystem`, `:core:ui` and every feature (01 Convention plugins). */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("neutrodyne.kmp.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("org.jetbrains.compose")

            val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
            kotlin.sourceSets.getByName("commonMain").dependencies {
                implementation(libs.findBundle("cmp-core").get())
            }
            (kotlin as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                androidResources { enable = true }
            }
            if (path == ":core:designsystem") {
                kotlin.compilerOptions { optIn.add("androidx.compose.material3.ExperimentalMaterial3Api") }
            }

            val compose = extensions.getByType<ComposeExtension>()
            (compose as ExtensionAware).extensions.configure<ResourcesExtension> {
                packageOfResClass = "$neutrodyneNamespace.resources"
                publicResClass = path == ":core:ui"
                generateResClass = ResourcesExtension.ResourceClassGeneration.Always
            }

            extensions.configure<ComposeCompilerGradlePluginExtension> {
                stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
                if (providers.gradleProperty("composeReports").isPresent) {
                    reportsDestination.set(layout.buildDirectory.dir("compose"))
                    metricsDestination.set(layout.buildDirectory.dir("compose"))
                }
            }
        }
}
