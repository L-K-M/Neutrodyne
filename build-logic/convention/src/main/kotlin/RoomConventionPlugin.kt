// SPDX-License-Identifier: Unlicense
import androidx.room3.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Room 3 KMP for `:core:database`: KSP per target, schemas under `core/database/schemas/` (01, 02 Conventions). */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("androidx.room3")
            pluginManager.apply("com.google.devtools.ksp")

            extensions.configure<RoomExtension> { schemaDirectory("$projectDir/schemas") }

            val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
            kotlin.sourceSets.getByName("commonMain").dependencies {
                api(libs.lib("androidx-room3-runtime"))
                api(libs.lib("androidx-room3-paging"))
                api(libs.lib("androidx-paging-common"))
                implementation(libs.lib("androidx-sqlite-bundled"))
            }
            kotlin.sourceSets.getByName("desktopTest").dependencies {
                implementation(libs.lib("androidx-room3-testing"))
            }
            dependencies {
                add("kspAndroid", libs.lib("androidx-room3-compiler"))
                add("kspDesktop", libs.lib("androidx-room3-compiler"))
            }
        }
}
