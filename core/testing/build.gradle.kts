// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    // expect/actual annotation typealiases (BeforeTest/AfterTest -> org.junit) let commonMain test
    // bases carry JUnit4 lifecycle annotations — silence the still-Beta warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:navigation"))
            implementation(project(":playback:api"))
            implementation(project(":download:api"))
            implementation(project(":youtube:api"))
            implementation(project(":sync:api"))
            implementation(project(":sync:protocol"))
            api(libs.kotlin.test)
            api(libs.kotlinx.coroutines.test)
            api(libs.turbine)
            implementation(libs.kotlinx.collections.immutable)
            api(project.dependencies.platform(libs.coil.bom))
            api(libs.coil.test)
        }
        androidMain.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
        }
        desktopMain.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
        }
    }
}
