// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Every shared module: targets `android` (Android-KMP library plugin) and `jvm("desktop")`, bytecode 17
 * (Android consumes it), common test bundle plus `:core:testing` (01 Convention plugins).
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            installPluginGuards()
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            pluginManager.apply("neutrodyne.android.lint")
            assertKotlinPluginVersion()
            forbidDynamicVersions()

            val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
            (kotlin as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = neutrodyneNamespace
                compileSdk = COMPILE_SDK
                minSdk = MIN_SDK
                compilerOptions { jvmTarget.set(JvmTarget.fromTarget(ANDROID_JVM_TARGET.toString())) }
            }
            kotlin.jvm("desktop") {
                compilerOptions { jvmTarget.set(JvmTarget.fromTarget(ANDROID_JVM_TARGET.toString())) }
            }
            kotlin.applyDefaultHierarchyTemplate()

            kotlin.sourceSets.getByName("commonTest").dependencies {
                implementation(libs.findBundle("common-test").get())
                if (path !in TESTING_SELF_AND_DEPENDENCIES) implementation(project(":core:testing"))
            }
            kotlin.sourceSets.getByName("desktopTest").dependencies {
                implementation(libs.findBundle("jvm-test").get())
            }
            registerDependencyPolicy()
            if (path == ":core:testing") configureModuleGraphAssert()
            configureNeutrodyneTestTasks()
        }

    private companion object {
        /** `:core:testing` and the main modules it depends on cannot use it in their own tests (cycle). */
        val TESTING_SELF_AND_DEPENDENCIES =
            setOf(
                ":core:testing",
                ":core:model",
                ":core:common",
                ":core:domain",
                ":core:database",
                ":core:navigation",
                ":playback:api",
                ":download:api",
                ":youtube:api",
                ":sync:api",
                ":sync:protocol",
            )
    }
}
