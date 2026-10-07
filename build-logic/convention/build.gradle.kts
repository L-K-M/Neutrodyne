// SPDX-License-Identifier: Unlicense
plugins {
    `kotlin-dsl`
}

group = "ch.lkmc.neutrodyne.buildlogic"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// implementation (not compileOnly): one classloader for AGP, KGP and the plugins that hook into them (01, S1)
dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.composeGradlePlugin)
    implementation(libs.kotlin.serializationGradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.metro.gradlePlugin)
    implementation(libs.room3.gradlePlugin)
    implementation(libs.baselineprofile.gradlePlugin)
    implementation(libs.spotless.gradlePlugin)
    implementation(libs.licensee.gradlePlugin)
    implementation(libs.moduleGraphAssert.gradlePlugin)
    implementation(libs.aboutlibraries.gradlePlugin)
    implementation(libs.chaquopy.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.tomlj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    // ProjectBuilder for the source-scan fixture tests (a real file tree, no build execution)
    testImplementation(gradleTestKit())
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

gradlePlugin {
    plugins {
        fun register(
            id: String,
            className: String,
        ) = register(id) {
            this.id = id
            implementationClass = className
        }
        register("neutrodyne.android.application", "AndroidApplicationConventionPlugin")
        register("neutrodyne.android.library", "AndroidLibraryConventionPlugin")
        register("neutrodyne.android.testing", "AndroidTestingConventionPlugin")
        register("neutrodyne.android.lint", "AndroidLintConventionPlugin")
        register("neutrodyne.kmp.library", "KmpLibraryConventionPlugin")
        register("neutrodyne.kmp.compose", "KmpComposeConventionPlugin")
        register("neutrodyne.kmp.feature", "KmpFeatureConventionPlugin")
        register("neutrodyne.jvm.island", "JvmIslandConventionPlugin")
        register("neutrodyne.desktop.library", "DesktopLibraryConventionPlugin")
        register("neutrodyne.desktop.native", "DesktopNativeConventionPlugin")
        register("neutrodyne.desktop.application", "DesktopApplicationConventionPlugin")
        register("neutrodyne.server.application", "ServerApplicationConventionPlugin")
        register("neutrodyne.metro", "MetroConventionPlugin")
        register("neutrodyne.room", "RoomConventionPlugin")
        register("neutrodyne.quality", "QualityConventionPlugin")
    }
}
