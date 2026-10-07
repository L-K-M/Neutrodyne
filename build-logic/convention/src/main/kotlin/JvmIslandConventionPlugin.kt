// SPDX-License-Identifier: Unlicense
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * JVM islands (`:feeds:jvm`, `:core:network:okhttp`, `:youtube:engine`): plain Kotlin/JVM at bytecode 17 with
 * `-Xjdk-release=17`, because Android consumes the JAR (01 Source sets and JVM islands).
 */
class JvmIslandConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            installPluginGuards()
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("neutrodyne.metro")
            pluginManager.apply("neutrodyne.android.lint")
            assertKotlinPluginVersion()
            forbidDynamicVersions()

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            tasks.withType<JavaCompile>().configureEach { options.release.set(ANDROID_JVM_TARGET) }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    freeCompilerArgs.add("-Xjdk-release=$ANDROID_JVM_TARGET")
                }
            }
            dependencies {
                add("testImplementation", libs.findBundle("jvm-test").get())
                add("testImplementation", libs.findBundle("common-test").get())
                add("testImplementation", project(":core:testing"))
            }
            registerDependencyPolicy()
            configureNeutrodyneTestTasks()
        }
}
