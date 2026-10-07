# SPDX-License-Identifier: Unlicense
# Keep rules for the androidTest APK of the release build type only
# (testProguardFiles on `release`; the app's own rules live in
# app/src/*/keepRules/, 01). With -PtestBuildType=release the instrumented
# suite runs against the R8-minified APK — the test classes, runner and JUnit
# entry points must survive the test APK's own shrinking.
-keep class ch.lkmc.neutrodyne.** { *; }
-dontwarn junit.**
-dontwarn org.junit.**
# Test-APK compile-time-only references that never exist on the device (minifyReleaseAndroidTest
# missing-class errors, 2026-10-06): material still names appcompat's removed DrawableWrapper, and
# errorprone annotations carry javax.lang.model members.
-dontwarn androidx.appcompat.graphics.drawable.DrawableWrapper
-dontwarn javax.lang.model.element.Modifier
