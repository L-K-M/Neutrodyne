// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.android.library)
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        // CI uploads this file even when Gradle restores the test from cache.
        inputs
            .file(rootProject.layout.projectDirectory.file("playback/engine/src/test/resources/dsp-reference.tsv"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(layout.projectDirectory.file("build/md0-reference/dsp-reference.tsv"))
    }
}

dependencies {
    implementation(project(":playback:core"))
    implementation(project(":playback:api"))
    implementation(project(":download:api"))
    implementation(project(":youtube:api"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:artwork"))
    implementation(project(":core:network:okhttp"))
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.inspector)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(project(":core:data"))
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.androidx.sqlite.framework)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
}
