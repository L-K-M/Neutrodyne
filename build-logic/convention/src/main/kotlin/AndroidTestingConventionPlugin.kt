// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.ManagedDevices
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
        ext.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"
        if (providers.gradleProperty("neutrodyne.testScope").getOrElse(SCOPE_CI) == SCOPE_CI) {
            ext.defaultConfig.testInstrumentationRunnerArguments["notAnnotation"] = CI_EXCLUDED_ANNOTATIONS
        }
        ext.testOptions.unitTests.isIncludeAndroidResources = true
        ext.testOptions.unitTests.isReturnDefaultValues = false
        ext.testOptions.animationsDisabled = true
        ext.testOptions.execution = ORCHESTRATOR
        configureManagedDevices(ext.testOptions.managedDevices)
    }

    /** The GMD table of 09: `ci` = minSdk floor + the main device; `nightly` adds the UIDT levels. */
    private fun configureManagedDevices(md: ManagedDevices) {
        val devices = md.localDevices
        for (device in MANAGED_DEVICES) {
            devices.maybeCreate(device.name).apply {
                this.device = device.hardware
                apiLevel = device.apiLevel
                systemImageSource = device.imageSource
                require64Bit = true
            }
        }
        md.groups
            .maybeCreate(GROUP_CI)
            .targetDevices
            .addAll(listOf(API_26, API_36).map { devices.getByName(it) })
        md.groups
            .maybeCreate(GROUP_NIGHTLY)
            .targetDevices
            .addAll(MANAGED_DEVICES.map { devices.getByName(it.name) })
    }

    private data class ManagedDevice(
        val name: String,
        val hardware: String,
        val apiLevel: Int,
        val imageSource: String,
    )

    private companion object {
        const val APP_RUNNER = "ch.lkmc.neutrodyne.NeutrodyneTestRunner"
        const val DEFAULT_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        const val ORCHESTRATOR = "ANDROIDX_TEST_ORCHESTRATOR"
        const val ROBOLECTRIC_GROUP = "org.robolectric"

        /** Robolectric artifacts with their own version line (the SDK jars and the native-runtime bundle). */
        val SEPARATELY_VERSIONED = listOf("android-all", "nativeruntime-dist-compat")
        const val SCOPE_CI = "ci"
        const val CI_EXCLUDED_ANNOTATIONS = "ch.lkmc.neutrodyne.core.testing.Nightly,androidx.test.filters.FlakyTest"
        const val GROUP_CI = "ci"
        const val GROUP_NIGHTLY = "nightly"
        const val API_26 = "api26"
        const val API_36 = "api36"
        const val PIXEL_2 = "Pixel 2"
        const val PIXEL_6 = "Pixel 6"
        const val IMAGE_AOSP = "aosp"
        const val IMAGE_ATD = "aosp-atd"

        val MANAGED_DEVICES =
            listOf(
                ManagedDevice(API_26, PIXEL_2, 26, IMAGE_AOSP),
                ManagedDevice("api33", PIXEL_6, 33, IMAGE_ATD),
                ManagedDevice("api34", PIXEL_6, 34, IMAGE_ATD),
                ManagedDevice(API_36, PIXEL_6, 36, IMAGE_ATD),
            )
    }
}
