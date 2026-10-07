# SPDX-License-Identifier: Unlicense
# Keep rules for classes Python reaches through Chaquopy's Java interop, which R8 cannot see
# (01 Release build and baseline profiles). android.r8.strictFullModeForKeepRules=true means a
# bare `-keep class A` keeps no constructors, so members are spelled out.

# The AIDL contract's client half: YtxService's binder subclass is the only in-app reference, so
# R8 merges Stub into it and removes both interfaces, Stub.asInterface and Stub.Proxy — exactly
# the pieces the Binder client (the ReleaseSmoke app test today, BinderYtxTransport from M9a)
# calls from the same shared dex (found 2026-10-06, 01 S7/S19).
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine { *; }
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine$* { *; }
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback { *; }
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback$* { *; }

# OkHttp bridge called from Python (`from java import jclass`), with its request/response
# holder classes (M9a names them when PyHttp is written; keep the whole ytx bridge).
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.PyHttp { public <init>(...); public *; }
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.QuickJsEngine { public <init>(...); public *; }

# 04's test hook: inert unless an instrumented test arms it; 09 owns arming on release builds.
-keep class ch.lkmc.neutrodyne.youtube.ytdlp.YtxTestHooks { *; }
