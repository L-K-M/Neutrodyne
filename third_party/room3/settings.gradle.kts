// SPDX-License-Identifier: Unlicense
// Standalone rebuild of androidx.room3:room3-runtime 3.0.3 for third_party/room3-maven
// (see README.md). Not part of the root build: invoked as `./gradlew -p third_party/room3`.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
    // Plugin and Kotlin versions track the repository toolchain through the shared catalog.
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "room3-runtime-rebuild"
