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
            extensions.configure<TestExtension> { configureAndroidCommon(this) }
            // Like androidTest APKs, this APK is never published. Licensee remains on
            // the shipped shells; JUnit's existing test-only EPL dependency stays out of them.
            registerDependencyPolicy()
            configureModuleGraphAssert()
        }
    }
}
