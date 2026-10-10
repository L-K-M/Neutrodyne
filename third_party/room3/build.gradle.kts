// SPDX-License-Identifier: Unlicense
// Rebuild of androidx.room3:room3-runtime 3.0.3 from its published sources jars with
// patches/0001 applied. See README.md for provenance, verification and removal.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

group = "androidx.room3"
version = "3.0.3"

val prepareSources =
    tasks.register<Exec>("prepareRoom3Sources") {
        group = "build setup"
        description = "Verify, extract, merge and patch the upstream room3-runtime sources"
        inputs.files("upstream/room3-runtime-jvm-3.0.3-sources.jar", "upstream/room3-runtime-android-3.0.3-sources.jar")
        inputs.files("upstream/SHA256SUMS.txt", "prepare-sources.sh")
        inputs.dir("patches")
        inputs.dir("src")
        outputs.dir(layout.buildDirectory.dir("room3-src"))
        commandLine("bash", "prepare-sources.sh")
    }

// Providers wired to the preparing task, so every task consuming these dirs
// gets the task dependency implicitly.
val mergedSources =
    prepareSources.map {
        layout.buildDirectory
            .dir("room3-src/merged")
            .get()
            .asFile
    }
val licenseRes =
    prepareSources.map {
        layout.buildDirectory
            .dir("room3-src/lic-res")
            .get()
            .asFile
    }

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    android {
        namespace = "androidx.room3"
        compileSdk = 37 // COMPILE_SDK in build-logic/convention ProjectExtensions.kt
        minSdk = 23 // upstream manifest floor (minCompileSdk 34 is satisfied by 37)
        // androidMain ships Java Binder stubs (IMultiInstanceInvalidation{Service,Callback});
        // without javac the AAR loses them and the Kotlin subclasses cannot link.
        withJava()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    // Upstream source-set DAG, restricted to the two targets we build (android, jvm):
    // commonMain <- nonWebMain <- {jvmAndAndroidMain -> androidMain|jvmMain, jvmNativeWebMain -> jvmMain}
    sourceSets {
        val commonMain = getByName("commonMain")
        val nonWebMain = create("nonWebMain") { dependsOn(commonMain) }
        val jvmAndAndroidMain = create("jvmAndAndroidMain") { dependsOn(nonWebMain) }
        val jvmNativeWebMain = create("jvmNativeWebMain") { dependsOn(nonWebMain) }
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").apply {
            dependsOn(jvmAndAndroidMain)
            dependsOn(jvmNativeWebMain)
        }

        // <set>/kotlin; javac derives <set>/java itself (see prepare-sources.sh).
        fun KotlinSourceSet.upstream(dir: String) = kotlin.srcDir(mergedSources.map { it.resolve("$dir/kotlin") })
        commonMain.upstream("commonMain")
        nonWebMain.upstream("nonWebMain")
        jvmAndAndroidMain.upstream("jvmAndAndroidMain")
        jvmNativeWebMain.upstream("jvmNativeWebMain")
        getByName("androidMain").upstream("androidMain")
        getByName("jvmMain").upstream("jvmMain")

        // Compile-time mirrors of the upstream 3.0.3 POM scopes (api = compile, implementation = runtime).
        commonMain.dependencies {
            api("androidx.annotation:annotation:1.9.1")
            api("androidx.collection:collection:1.5.0")
            api("androidx.room3:room3-common:3.0.3")
            api("androidx.sqlite:sqlite:2.7.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("androidx.sqlite:sqlite-async:2.7.1")
        }
        getByName("androidMain").dependencies {
            api("androidx.sqlite:sqlite-framework:2.7.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            api("org.jspecify:jspecify:1.0.0")
            implementation("androidx.annotation:annotation-experimental:1.5.0")
        }
    }
}

// AGP derives some packaged-input dirs (baseline profiles, packaged assets, the Java
// flat-source dirs once withJava() is on) from the androidMain kotlin srcDir, i.e.
// inside the prepared tree; give those tasks the dep.
tasks.configureEach {
    val scansPreparedTree =
        name.startsWith("prepareAndroidMain") ||
            name == "extractAndroidMainAnnotations" ||
            name == "compileAndroidMainJavaWithJavac" ||
            name == "mergeAndroidMainJavaResource" ||
            name == "processAndroidMainJavaRes"
    if (name != prepareSources.name && scansPreparedTree) {
        dependsOn(prepareSources)
    }
}

// Embed the Apache-2.0 notice at META-INF/androidx/room3/room3-runtime/LICENSE.txt,
// matching upstream's jars.
tasks.named("jvmJar", Jar::class) { from(licenseRes) }
tasks.named("allMetadataJar", Jar::class) { from(licenseRes) }

// The aar carries the same LICENSE.txt plus upstream's consumer proguard.txt. BundleAar
// is a Zip task, so extra root entries attach directly (AGP's KMP mode has no
// consumerProguardFiles hook).
tasks.withType(Zip::class).matching { it.name == "bundleAndroidMainAar" }.configureEach {
    from(licenseRes)
    from("src/androidMain/proguard.txt")
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    repositories {
        maven(layout.projectDirectory.dir("../room3-maven")) { name = "room3Maven" }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Room-Runtime")
            description.set("Android Room-Runtime")
            url.set("https://developer.android.com/jetpack/androidx/releases/room3#3.0.3")
            inceptionYear.set("2017")
            organization {
                name.set("The Android Open Source Project")
            }
            licenses {
                license {
                    this.name.set("The Apache Software License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer { name.set("The Android Open Source Project") }
            }
            scm {
                connection.set("scm:git:https://android.googlesource.com/platform/frameworks/support")
                url.set("https://cs.android.com/androidx/platform/frameworks/support")
            }
        }
    }
}
