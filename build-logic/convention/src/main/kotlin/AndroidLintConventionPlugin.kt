// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Lint gates of 09 Static analysis. `:app` lints every module with Android code through `checkDependencies`;
 * plain JVM islands join through `com.android.lint`.
 */
class AndroidLintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                pluginManager.apply("com.android.lint")
                extensions.configure<Lint> { configureNeutrodyneLint(this@with) }
            }
            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> { lint { configureNeutrodyneLint(this@with) } }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> { lint { configureNeutrodyneLint(this@with) } }
            }
            pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
                val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
                (kotlin as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                    lint { configureNeutrodyneLint(this@with) }
                }
            }
        }
}

private fun Lint.configureNeutrodyneLint(project: Project) {
    warningsAsErrors = true
    abortOnError = true
    sarifReport = true
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "MissingTranslation")
    fatal +=
        setOf("StringFormatInvalid", "StringFormatMatches", "MissingQuantity", "UnusedResources", "ExtraTranslation")
    enable += setOf("StopShip")
    if (project.path == ":app") {
        checkDependencies = true
        baseline = project.file("lint-baseline.xml")
    }
    // Media3's @UnstableApi is a Lint-enforced opt-in; only :playback:impl opts in module-wide (01 Convention plugins)
    if (project.path == ":playback:impl") lintConfig = project.file("lint.xml")
}
