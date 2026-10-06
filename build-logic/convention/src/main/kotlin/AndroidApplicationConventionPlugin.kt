// SPDX-License-Identifier: Unlicense
import androidx.baselineprofile.gradle.consumer.BaselineProfileConsumerExtension
import com.android.build.api.dsl.ApplicationExtension
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
                signingConfigs {
                    // Committed and public on purpose (D61, 01 Signing config)
                    create(SIGNING_CONFIG) {
                        storeFile = rootProject.file("signing/neutrodyne-public.keystore")
                        storeType = "pkcs12"
                        storePassword = PUBLIC_PASSWORD
                        keyAlias = PUBLIC_ALIAS
                        keyPassword = PUBLIC_PASSWORD
                        enableV1Signing = false
                        enableV2Signing = true
                        enableV3Signing = true
                    }
                }
                splits {
                    abi {
                        isEnable = true
                        reset()
                        include(*PUBLISHED_ABIS)
                        isUniversalApk = false
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
                    // The plugin copies release's legacy minify flag, not AGP 9's optimization {} block, so
                    // benchmarkRelease is minified explicitly to measure what users run (S19, 2026-10-06)
                    matching { it.name == BENCHMARK_RELEASE }.configureEach { optimization { enable = true } }
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
        const val TEST_KEEP_RULES = "proguard-test.pro"
        const val SIGNING_CONFIG = "neutrodynePublic"
        const val PUBLIC_PASSWORD = "neutrodyne"
        const val PUBLIC_ALIAS = "neutrodyne"
        val PUBLISHED_ABIS = arrayOf("arm64-v8a", "x86_64", "armeabi-v7a")
    }
}
