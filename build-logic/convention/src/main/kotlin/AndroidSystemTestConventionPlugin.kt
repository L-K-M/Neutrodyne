// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** An unpublished, self-instrumenting APK with the same toolchain and boundary gates. */
class AndroidSystemTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            pluginManager.apply("androidx.baselineprofile")
            extensions.configure<TestExtension> { configureAndroidCommon(this) }
            registerDependencyPolicy()
            configureModuleGraphAssert()
        }
    }
}
