# SPDX-License-Identifier: Unlicense
# Analysis roots only: never apply these rules to an app or test APK.
-dontobfuscate
-dontoptimize
-ignorewarnings
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

-keep class ch.lkmc.neutrodyne.NeutrodyneTestRunner { *; }
-keep class ch.lkmc.neutrodyne.SmokeTest { *; }
-keep class ch.lkmc.neutrodyne.YtxReleaseSmokeTest { *; }
-keep class ch.lkmc.neutrodyne.playback.impl.PlaybackServiceTest {
    public <init>();
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    public void notificationTransportAndLocalArtwork();
}
-keep class androidx.test.runner.** { *; }
-keep class androidx.test.ext.junit.runners.** { *; }
-keep class org.junit.** { *; }
# Loaded from the test APK's META-INF/services by the target's coroutine runtime.
-keep class kotlinx.coroutines.test.internal.ExceptionCollectorAsService { *; }
-keep class kotlinx.coroutines.test.internal.TestMainDispatcherFactory { *; }
-keepclassmembers class * {
    @org.junit.Rule <fields>;
    @org.junit.Rule <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.Test <methods>;
}
