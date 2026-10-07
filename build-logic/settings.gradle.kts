// SPDX-License-Identifier: Unlicense
dependencyResolutionManagement {
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
        // Same S7 fallback repository as the root build: it alone serves com.chaquo.python*
        // (the com.chaquo.python:gradle plugin on this classpath; 01 catalog rule 3).
        exclusiveContent {
            forRepository { maven(uri("../third_party/chaquopy-maven")) }
            filter {
                includeModule("com.chaquo.python", "gradle")
                includeGroup("com.chaquo.python.runtime")
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
include(":convention")
