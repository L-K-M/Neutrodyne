// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** `:feature:*`: Compose plus Metro and the fixed set of core edges of dependency rule 2 (01 Convention plugins). */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("neutrodyne.kmp.compose")
            pluginManager.apply("neutrodyne.metro")

            val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
            kotlin.sourceSets.getByName("commonMain").dependencies {
                for (core in listOf("domain", "model", "common", "designsystem", "ui", "navigation")) {
                    implementation(project(":core:$core"))
                }
                for (alias in FEATURE_LIBRARIES) implementation(libs.lib(alias))
            }
        }

    private companion object {
        val FEATURE_LIBRARIES =
            listOf(
                "lifecycle-viewmodel-compose",
                "lifecycle-runtime-compose",
                "lifecycle-viewmodel-navigation3",
                "metrox-viewmodel-compose",
                "navigation3-runtime",
                "navigation3-ui-jb",
                "cmp-material3-adaptive-navigation3",
                "androidx-paging-compose",
                "kotlinx-collections-immutable",
            )
    }
}
