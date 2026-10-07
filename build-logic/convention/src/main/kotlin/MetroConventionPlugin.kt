// SPDX-License-Identifier: Unlicense
import dev.zacsweers.metro.gradle.MetroPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies Metro and nothing else (01 Dependency injection). `generateContributionProviders` is on
 * because implementation modules keep their classes `internal`: without it, `internal` contributions
 * do not aggregate across modules (S8, 2026-10-06).
 */
class MetroConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("dev.zacsweers.metro")
        target.extensions.configure<MetroPluginExtension> {
            generateContributionProviders.set(true)
        }
    }
}
