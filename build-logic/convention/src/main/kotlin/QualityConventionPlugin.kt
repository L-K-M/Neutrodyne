// SPDX-License-Identifier: Unlicense
import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Root-only quality plugin (01 Convention plugins): Spotless (ktlint 1.8.0 + compose-rules 0.6.7),
 * `checkSpdxHeaders`, `checkBannedApis` and report-only detekt 2.0 on every Kotlin module (09 Static analysis).
 * The brand-asset tasks arrive in M0b step 31.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        check(target == target.rootProject) { "neutrodyne.quality is applied to the root project only" }
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("**/*.kt")
                    targetExclude("**/build/**", "third_party/**", "**/.gradle/**", "**/.cxx/**")
                    ktlint(libs.version("ktlint"))
                        .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:${libs.version("composeRules")}"))
                }
                kotlinGradle {
                    target("**/*.kts")
                    targetExclude("**/build/**", "**/.gradle/**")
                    ktlint(libs.version("ktlint"))
                }
            }
            tasks.matching { it.name == "check" }.configureEach {
                dependsOn(tasks.named("spotlessCheck"))
            }

            registerSourceScanTasks()
            registerBrandAssetTasks()
            // The root project gets verifyDependencyPolicy like every module (01 Gradle-side policy tasks).
            registerDependencyPolicy()

            // detekt is report-only until a stable release supports Kotlin 2.4 / AGP 9 (09 detekt).
            // The shared config/baseline live at the root; modules do not carry their own copies.
            // Versions come from the root's catalog: a subproject's VersionCatalogsExtension is not
            // registered until its own evaluation, so module.libs would fail here.
            val detektVersion = libs.version("detekt")
            val detektConfig = layout.projectDirectory.file("config/detekt/detekt.yml")
            val detektBaseline = layout.projectDirectory.file("config/detekt/baseline.xml")
            for (module in target.subprojects) {
                module.pluginManager.apply("dev.detekt")
                module.extensions.configure<DetektExtension> {
                    toolVersion.set(detektVersion)
                    ignoreFailures.set(true)
                    buildUponDefaultConfig.set(true)
                    parallel.set(true)
                    config.from(detektConfig)
                    if (detektBaseline.asFile.isFile) baseline.set(detektBaseline)
                }
                module.tasks.withType<Detekt>().configureEach {
                    reports.sarif.required.set(true)
                    reports.html.required.set(false)
                }
            }
        }
    }
}
