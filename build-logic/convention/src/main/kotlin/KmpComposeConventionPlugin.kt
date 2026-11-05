// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
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
                if (providers.gradleProperty("neutrodyne.pseudoLocales").orNull == "true" &&
                    layout.projectDirectory
                        .dir("src/commonMain/composeResources")
                        .asFile.isDirectory
                ) {
                    val pseudo =
                        tasks.register<GeneratePseudoLocales>("generatePseudoLocales") {
                            sourceDirectory.set(layout.projectDirectory.dir("src/commonMain/composeResources"))
                            resourceDirectory.set(layout.buildDirectory.dir("generated/pseudoComposeResources"))
                        }
                    customDirectory("commonMain", pseudo.flatMap { it.resourceDirectory })
                }
            }

            pluginManager.withPlugin("io.github.takahirom.roborazzi") {
                if (path.startsWith(":feature:")) {
                    kotlin.sourceSets
                        .getByName("commonTest")
                        .kotlin
                        .srcDir(rootProject.file("tools/ui-test-fixtures"))
                    kotlin.sourceSets.configureEach {
                        if (name ==
                            "desktopTest"
                        ) {
                            dependencies { implementation(libs.lib("roborazzi-compose-desktop")) }
                        }
                        if (name == "androidHostTest") {
                            dependencies {
                                implementation(libs.lib("robolectric"))
                                implementation(libs.lib("junit4"))
                                implementation(project.dependencies.platform(libs.lib("androidx-compose-bom")))
                                implementation(libs.lib("androidx-compose-ui-test-junit4"))
                                implementation(libs.lib("androidx-test-ext-junit"))
                                implementation(libs.lib("androidx-test-espresso-core"))
                                implementation(libs.lib("roborazzi"))
                                implementation(libs.lib("roborazzi-compose"))
                                implementation(libs.lib("roborazzi-junit-rule"))
                            }
                        }
                    }
                }
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
