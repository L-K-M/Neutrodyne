// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** SDK levels, Java 17 and plugin guards for `com.android.application` and `com.android.library` modules. */
internal fun Project.configureAndroidCommon(ext: CommonExtension) {
    ext.compileSdk = COMPILE_SDK
    ext.buildToolsVersion = BUILD_TOOLS
    ext.defaultConfig.minSdk = MIN_SDK
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").isPresent)
        }
    }
    installPluginGuards()
    forbidDynamicVersions()
    afterEvaluate {
        check(!ext.compileOptions.isCoreLibraryDesugaringEnabled) { "core-library desugaring is not used (D3)" }
    }
}
