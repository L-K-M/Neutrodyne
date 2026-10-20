// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets.named("desktopTest") {
        resources.srcDir(project.file("src/test/resources"))
        kotlin.srcDir(project.file("src/test/kotlin"))
        kotlin.srcDir(project.file("src/replay/kotlin"))
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.json.okio)
            implementation(libs.kotlinx.datetime)
            // api: okio.Source is a parameter of FeedParser.parse, so implementors see it (01 api rule)
            api(libs.okio)
        }
    }
}
