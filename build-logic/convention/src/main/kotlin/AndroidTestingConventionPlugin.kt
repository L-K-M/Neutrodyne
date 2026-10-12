// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Android test settings for `:app` and the Android-only libraries (09 Gradle test configuration, Gradle Managed
 * Devices): the test runner, orchestrator with `clearPackageData`, Robolectric, and the managed devices.
 */
class AndroidTestingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> { configureAndroidTesting(this) }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> { configureAndroidTesting(this) }
            }

            val robolectric = libs.version("robolectric")
            configurations.configureEach {
                // media3-test-utils pulls an older Robolectric; one version everywhere (09)
                resolutionStrategy.eachDependency {
                    val separatelyVersioned = SEPARATELY_VERSIONED.any { requested.name.startsWith(it) }
                    if (requested.group == ROBOLECTRIC_GROUP && !separatelyVersioned) useVersion(robolectric)
                }
            }
            dependencies {
                add("testImplementation", project(":core:testing"))
                add("testImplementation", libs.findBundle("jvm-test").get())
                add("testImplementation", libs.lib("robolectric"))
                add("androidTestImplementation", libs.lib("androidx-test-runner"))
                add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
                add("androidTestImplementation", libs.lib("truth"))
                add("androidTestImplementation", project(":core:testing"))
                add("androidTestUtil", libs.lib("androidx-test-orchestrator"))
            }
            configureNeutrodyneTestTasks()
        }

    private fun Project.configureAndroidTesting(ext: CommonExtension) {
        ext.defaultConfig.testInstrumentationRunner = if (path == ":app") APP_RUNNER else DEFAULT_RUNNER
        configureInstrumentationArgs(ext.defaultConfig.testInstrumentationRunnerArguments)
        ext.testOptions.unitTests.isIncludeAndroidResources = true
        ext.testOptions.unitTests.isReturnDefaultValues = false
        ext.testOptions.animationsDisabled = true
        ext.testOptions.execution = ORCHESTRATOR
        configureManagedDevices(ext.testOptions.managedDevices)
    }

    private companion object {
        const val APP_RUNNER = "ch.lkmc.neutrodyne.NeutrodyneTestRunner"
        const val ROBOLECTRIC_GROUP = "org.robolectric"

        /** Robolectric artifacts with their own version line (the SDK jars and the native-runtime bundle). */
        val SEPARATELY_VERSIONED = listOf("android-all", "nativeruntime-dist-compat")
    }
}
