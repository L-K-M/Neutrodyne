// SPDX-License-Identifier: Unlicense
import androidx.baselineprofile.gradle.consumer.BaselineProfileConsumerExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * The Android shell `:app` (01 Convention plugins, Build variants and ABIs): build types `release` (published) and
 * `debug` (local, `.debug`), every build type signed by the committed public keystore, per-ABI APKs, Compose.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            assertKotlinPluginVersion()

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                namespace = BASE_PACKAGE
                // Unit and instrumented tests run on debug; release-type smoke runs pass -PtestBuildType=release (09)
                testBuildType = providers.gradleProperty("testBuildType").getOrElse(DEBUG)
                defaultConfig {
                    applicationId = BASE_PACKAGE
                    targetSdk = TARGET_SDK
                    versionCode = providers.gradleProperty("neutrodyne.versionCode").get().toInt()
                    versionName = providers.gradleProperty("neutrodyne.versionName").get()
                }
                buildFeatures {
                    buildConfig = true
                    compose = true
                }
                androidResources { generateLocaleConfig = true }
                dependenciesInfo {
                    includeInApk = false
                    includeInBundle = false
                }
                splits {
                    abi {
                        isEnable = true
                        reset()
                        include(*PUBLISHED_ABIS)
                        isUniversalApk = false
                    }
                }
                signingConfigs {
                    // Committed and public on purpose (D61, 01 Signing config); the same block signs
                    // :benchmark's test APK (AndroidSystemTestConventionPlugin)
                    create(SIGNING_CONFIG) {
                        storeFile = rootProject.file(SIGNING_STORE)
                        storeType = "pkcs12"
                        storePassword = SIGNING_PASSWORD
                        keyAlias = SIGNING_ALIAS
                        keyPassword = SIGNING_PASSWORD
                        enableV1Signing = false
                        enableV2Signing = true
                        enableV3Signing = true
                    }
                }
                buildTypes {
                    getByName(RELEASE) {
                        optimization { enable = true }
                        // Reproducible builds (09 Hygiene): PNGs are committed pre-optimised; no VCS metadata in the APK
                        isCrunchPngs = false
                        vcsInfo { include = false }
                        // Keeps the androidTest APK intact when -PtestBuildType=release runs the smoke tests (09)
                        if (file(TEST_KEEP_RULES).exists()) testProguardFiles(TEST_KEEP_RULES)
                    }
                    getByName(DEBUG) {
                        applicationIdSuffix = ".debug"
                        versionNameSuffix = "-debug"
                        isPseudoLocalesEnabled = true
                    }
                    // Every build type, including those androidx.baselineprofile creates, uses the public key
                    configureEach { signingConfig = signingConfigs.getByName(SIGNING_CONFIG) }
                    // The plugin's initWith(release) runs after this container's configureEach callbacks,
                    // so the benchmarkRelease and nonMinifiedRelease optimization flags are set in the
                    // finalizeDsl callback below, once the plugin has created both build types.
                }
                packaging { jniLibs { useLegacyPackaging = false } }
            }

            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom"))
                add("implementation", bom)
                add("debugImplementation", bom)
                add("androidTestImplementation", bom)
            }

            // Consumer of the baseline and startup profiles; creates benchmarkRelease and nonMinifiedRelease (D96, S19)
            pluginManager.apply("androidx.baselineprofile")
            extensions.configure<BaselineProfileConsumerExtension> {
                saveInSrc = true
                automaticGenerationDuringBuild = false
            }

            // The plugin copies release's optimization {} block through initWith(release), so the
            // two derived build types are configured only after the plugin has created them, in a
            // finalizeDsl callback registered after the plugin's own.
            // benchmarkRelease is minified like the published build to measure what users run
            // (S19, 2026-10-06); nonMinifiedRelease stays unminified so the profile generator sees
            // the original class and method descriptors (D96, 09 Macrobenchmark and profiles).
            extensions.configure<ApplicationAndroidComponentsExtension> {
                finalizeDsl { app ->
                    app.buildTypes.matching { it.name == BENCHMARK_RELEASE }.configureEach {
                        optimization { enable = true }
                    }
                    app.buildTypes.matching { it.name == NON_MINIFIED_RELEASE }.configureEach {
                        optimization { enable = false }
                    }
                }
            }

            configureLicensee()
            registerDependencyPolicy()
            registerManifestPermissions()
            configureModuleGraphAssert()
            pluginManager.apply("neutrodyne.metro")
            pluginManager.apply("neutrodyne.android.lint")
            pluginManager.apply("neutrodyne.android.testing")
        }

    private companion object {
        const val RELEASE = "release"
        const val DEBUG = "debug"
        const val BENCHMARK_RELEASE = "benchmarkRelease"
        const val NON_MINIFIED_RELEASE = "nonMinifiedRelease"
        const val TEST_KEEP_RULES = "proguard-test.pro"
        val PUBLISHED_ABIS = arrayOf("arm64-v8a", "x86_64", "armeabi-v7a")
    }
}
