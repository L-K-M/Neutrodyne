// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The test APK uses the shared SDK and declared/resolved dependency boundary checks. */
class AndroidSystemTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            pluginManager.apply("androidx.baselineprofile")
            extensions.configure<TestExtension> {
                configureAndroidCommon(this)
                signingConfigs {
                    // The target app signs every build type with the committed public key
                    // (AndroidApplicationConventionPlugin); this APK must carry the same certificate:
                    // self-instrumenting a non-debuggable target requires it, and AGP leaves the custom
                    // benchmarkRelease type unsigned without an explicit config (an unsigned APK fails
                    // to install with INSTALL_PARSE_FAILED_NO_CERTIFICATES)
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
                buildTypes { configureEach { signingConfig = signingConfigs.getByName(SIGNING_CONFIG) } }
            }
            // Like androidTest APKs, this APK is never published. Licensee remains on
            // the shipped shells; JUnit's existing test-only EPL dependency stays out of them.
            registerDependencyPolicy()
            configureModuleGraphAssert()
        }
    }
}
