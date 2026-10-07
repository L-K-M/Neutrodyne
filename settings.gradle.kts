// SPDX-License-Identifier: Unlicense
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // The convention classpath (libs.chaquopy.gradlePlugin) resolves with these repositories:
        // S7's self-built plugin lives in third_party/chaquopy-maven (see below).
        exclusiveContent {
            forRepository { maven(uri("third_party/chaquopy-maven")) }
            filter {
                includeModule("com.chaquo.python", "gradle")
                includeGroup("com.chaquo.python.runtime")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Compose Multiplatform, org.jetbrains.androidx.*, Metro, Ktor, kotlinx, Skiko: all on Maven Central
        mavenCentral()
        // S7 fallback: self-built Chaquopy master (17.1.0, commit a41f0c9) is the only source of
        // com.chaquo.python*; the runtime artifacts inside are the published 17.0.0 payloads
        // republished under 17.1.0 (provenance: third_party/chaquopy-maven/README.md).
        exclusiveContent {
            forRepository { maven(uri("third_party/chaquopy-maven")) }
            filter {
                includeModule("com.chaquo.python", "gradle")
                includeGroup("com.chaquo.python.runtime")
            }
        }
    }
}

rootProject.name = "Neutrodyne"

include(":app", ":desktopApp")
include(
    ":core:model",
    ":core:common",
    ":core:domain",
    ":core:navigation",
    ":core:database",
    ":core:datastore",
    ":core:network",
    ":core:network:okhttp",
    ":core:data",
    ":core:artwork",
    ":core:designsystem",
    ":core:ui",
    ":core:testing",
)
include(":feeds", ":feeds:jvm")
include(
    ":playback:api",
    ":playback:core",
    ":playback:impl",
    ":playback:engine",
    ":playback:native",
    ":playback:desktop",
)
include(":desktop:system")
include(":download:api", ":download:impl")
include(":youtube:api", ":youtube:impl", ":youtube:engine", ":youtube:ytdlp", ":youtube:ytdlp-desktop")
include(":sync:protocol", ":sync:api", ":sync:impl", ":sync:server")
// No :update:* modules: the update check lives in :core:domain, :core:model and :core:data (D13, D78)
include(
    ":feature:feeds",
    ":feature:library",
    ":feature:groups",
    ":feature:podcast",
    ":feature:episode",
    ":feature:player",
    ":feature:queue",
    ":feature:downloads",
    ":feature:discover",
    ":feature:importexport",
    ":feature:settings",
    ":feature:sync",
)
// M6b: include(":benchmark")   v1.x: include(":feature:widgets")
