// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTest
import com.android.build.api.dsl.ManagedDevices
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Device-side test settings shared by `com.android.*` modules (`testOptions`) and KMP library modules
 * that opt into `withDeviceTest { }` (09 Gradle Managed Devices): one GMD table, the same runner
 * arguments, the orchestrator and disabled animations.
 */
internal const val DEFAULT_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
internal const val ORCHESTRATOR = "ANDROIDX_TEST_ORCHESTRATOR"
private const val SCOPE_CI = "ci"
private const val CI_EXCLUDED_ANNOTATIONS =
    "ch.lkmc.neutrodyne.core.testing.Nightly,androidx.test.filters.FlakyTest"
private const val GROUP_CI = "ci"
private const val GROUP_NIGHTLY = "nightly"
private const val API_26 = "api26"
private const val API_36 = "api36"
private const val PIXEL_2 = "Pixel 2"
private const val PIXEL_6 = "Pixel 6"
private const val IMAGE_AOSP = "aosp"
private const val IMAGE_ATD = "aosp-atd"

private val MANAGED_DEVICES =
    listOf(
        ManagedDevice(API_26, PIXEL_2, 26, IMAGE_AOSP),
        ManagedDevice("api33", PIXEL_6, 33, IMAGE_ATD),
        ManagedDevice("api34", PIXEL_6, 34, IMAGE_ATD),
        ManagedDevice(API_36, PIXEL_6, 36, IMAGE_ATD),
    )

private data class ManagedDevice(
    val name: String,
    val hardware: String,
    val apiLevel: Int,
    val imageSource: String,
)

/**
 * The instrumentation arguments both DSL surfaces carry (09): `clearPackageData` for the
 * orchestrator, and under `neutrodyne.testScope=ci` the exclusion of `Nightly` and `FlakyTest`.
 */
internal fun Project.configureInstrumentationArgs(args: MutableMap<String, String>) {
    args["clearPackageData"] = "true"
    if (providers.gradleProperty("neutrodyne.testScope").getOrElse(SCOPE_CI) == SCOPE_CI) {
        args["notAnnotation"] = CI_EXCLUDED_ANNOTATIONS
    }
}

/** The GMD table of 09: `ci` = minSdk floor + the main device; `nightly` adds the UIDT levels. */
internal fun configureManagedDevices(md: ManagedDevices) {
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

/**
 * The same settings on a KMP module's device-test compilation (`withDeviceTest`), which carries
 * `managedDevices`, `instrumentationRunnerArguments`, `animationsDisabled` and `execution` itself
 * (09: KMP device tests run the same GMDs; the group task is `<group>GroupAndroidDeviceTest`).
 * The orchestrator APK resolves from `androidTestUtil`, which `ManagedDeviceTestTask` reads per
 * project.
 */
internal fun Project.configureKmpDeviceTest(dt: KotlinMultiplatformAndroidDeviceTest) {
    dt.instrumentationRunner = DEFAULT_RUNNER
    configureInstrumentationArgs(dt.instrumentationRunnerArguments)
    dt.animationsDisabled = true
    dt.execution = ORCHESTRATOR
    dt.managedDevices(::configureManagedDevices)
    dependencies { add("androidTestUtil", libs.lib("androidx-test-orchestrator")) }
}
