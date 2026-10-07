# SPDX-License-Identifier: Unlicense
# Keep rules for the androidTest APK of the release build type only
# (testProguardFiles on `release`; the app's own rules live in
# app/src/*/keepRules/, 01). With -PtestBuildType=release the instrumented
# suite runs against the R8-minified APK — the test classes, runner and JUnit
# entry points must survive the test APK's own shrinking.
-keep class ch.lkmc.neutrodyne.** { *; }
-dontwarn junit.**
-dontwarn org.junit.**

# Classes the test APK's dependencies reference but never load on device: Material's
# ShadowDrawableWrapper (pulled in by a test dependency) extends AppCompat's DrawableWrapper,
# which the app's own R8 pass removed as unused; Error Prone's annotations reference the
# compile-time javax.lang.model API. Neither runs in the release smoke tests.
-dontwarn androidx.appcompat.graphics.drawable.DrawableWrapper
-dontwarn javax.lang.model.element.Modifier
