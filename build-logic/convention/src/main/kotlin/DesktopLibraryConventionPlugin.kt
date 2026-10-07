// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Desktop-only JVM modules on the JDK 25 toolchain (FFM is final since JDK 22) (01 Convention plugins). */
class DesktopLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            installPluginGuards()
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("neutrodyne.metro")
            assertKotlinPluginVersion()
            forbidDynamicVersions()
            configureDesktopJvm()
            dependencies {
                add("testImplementation", libs.findBundle("jvm-test").get())
                add("testImplementation", libs.findBundle("common-test").get())
                add("testImplementation", project(":core:testing"))
            }
            tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
            registerDependencyPolicy()
            if (path == ":youtube:ytdlp-desktop") registerPythonPolicy(desktopLock = true)
            configureNeutrodyneTestTasks()
        }
}

/** JDK 25 toolchain with `jvmTarget` 25 (fallback 21 per D4, recorded by S13). */
internal fun Project.configureDesktopJvm() {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(DESKTOP_JDK)
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(DESKTOP_JDK.toString())) }
    }
}
