// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    // expect/actual classes and objects (Nfc, DateFormatter, StoragePaths) are 01's shim
    // mechanism for the two JVM islands — silence the still-Beta warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            // WorkerKey's map type names ListenableWorker (01 DI: worker factories)
            api(libs.androidx.work.runtime)
        }
        desktopMain.dependencies {
            // JNA for SHGetKnownFolderPath(FOLDERID_LocalAppData) — AppDirs' Windows fallback
            // when %LOCALAPPDATA% is unset or relative (11 AppDirs).
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
    }
}
