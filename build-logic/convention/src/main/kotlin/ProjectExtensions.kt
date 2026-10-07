// SPDX-License-Identifier: Unlicense
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/** Root package of every module (01 Module layout). */
internal const val BASE_PACKAGE = "ch.lkmc.neutrodyne"

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 26
internal const val TARGET_SDK = 37
internal const val BUILD_TOOLS = "36.0.0"

/** JDK feature versions per product (01 JDKs row). */
internal const val ANDROID_JVM_TARGET = 17
internal const val SERVER_JDK = 21
internal const val DESKTOP_JDK = 25

/** Catalog access for plugin classes, which have no type-safe `libs` accessor (catalog rule 5). */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/**
 * Namespace / package for a module path: ":core:model" -> "ch.lkmc.neutrodyne.core.model",
 * ":youtube:ytdlp-desktop" -> "ch.lkmc.neutrodyne.youtube.ytdlpdesktop"; ":app" and ":desktopApp" are special.
 */
internal val Project.neutrodyneNamespace: String
    get() =
        when (path) {
            ":app" -> BASE_PACKAGE
            ":desktopApp" -> "$BASE_PACKAGE.desktop"
            else -> BASE_PACKAGE + path.replace(':', '.').replace("-", "")
        }

/** Rejects plugins the plan bans or confines (01 Common Android configuration). */
internal fun Project.installPluginGuards() {
    pluginManager.withPlugin(
        "org.jetbrains.kotlin.android",
    ) { error("kotlin-android is banned: AGP 9 built-in Kotlin") }
    pluginManager.withPlugin("org.jetbrains.kotlin.kapt") { error("kapt is banned: use KSP") }
    pluginManager.withPlugin("com.google.dagger.hilt.android") { error("Hilt is removed: Metro (D82)") }
    pluginManager.withPlugin("com.chaquo.python") {
        check(path == ":youtube:ytdlp") { "com.chaquo.python is allowed only in :youtube:ytdlp" }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        check(!pluginManager.hasPlugin("com.android.library") && !pluginManager.hasPlugin("com.android.application")) {
            "use com.android.kotlin.multiplatform.library for shared modules (D81)"
        }
    }
}

/** No dynamic or changing versions anywhere (catalog rule 1). */
internal fun Project.forbidDynamicVersions() {
    configurations.configureEach {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}

/** Fails the build when KGP resolved to another version than the catalog's (S1 in-build assertion). */
internal fun Project.assertKotlinPluginVersion() {
    val expected = libs.version("kotlin")
    val actual = getKotlinPluginVersion()
    check(actual == expected) { "KGP drift: expected $expected, resolved $actual (01 S1)" }
}
