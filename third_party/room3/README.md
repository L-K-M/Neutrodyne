<!-- SPDX-License-Identifier: Unlicense -->

# `third_party/room3` — patched `androidx.room3:room3-runtime` 3.0.3

Room 3.0.3's connection pool retries forever when a *foreign* timeout or cancellation
reaches a waiting acquisition. `Pool.acquireWithTimeout` classified any caught
`TimeoutCancellationException` as its own acquisition timeout, so a caller's
`withTimeoutOrNull` (for example a repository's deadline around `withWriteTransaction`)
made the waiter invoke the timeout handler and re-enter the retry loop: a zombie
coroutine that dumps the pool state to stderr on every pass (hundreds of MB/s observed),
ignores holder release and cancellation, and leaves later writers pending behind it. The
same routine is byte-identical in 3.1.0-alpha01; there is no supported fail-fast switch.

`acquire()` itself is correct, so `patches/0001` swaps only the classification: the
acquisition attempt runs under `withTimeoutOrNull`, whose `null` result means *this*
timeout elapsed. Foreign cancellation propagates unchanged; a connection acquired on the
way out of the timed block is recycled by `finally` exactly once unless ownership
transfers to the caller. The internal dump-and-retry policy and transaction rollback are
unchanged. Regression coverage: `core/database/.../RoomPoolForeignTimeoutTest.kt`.

## What is vendored

This directory **builds** the runtime; `../room3-maven` carries its outputs. The root
build resolves `androidx.room3:room3-runtime-rebuild:3.0.3` exclusively from that repo
(`settings.gradle.kts` `exclusiveContent`) and substitutes it for every request of
`androidx.room3:room3-runtime` (root `build.gradle.kts`), so consumers, `room3-paging` and
`room3-testing` all bind to the patched variant at the same 3.0.3 API surface. Compiler,
Gradle plugin, schema and data behaviour are upstream 3.0.3, unchanged.

| Path | Provenance |
|---|---|
| `upstream/room3-runtime-{jvm,android}-3.0.3-sources.jar` | the published sources jars from `dl.google.com/dl/android/maven2/androidx/room3/room3-runtime-{jvm,android}/3.0.3/` (`SHA256SUMS.txt`; the jvm jar is byte-identical to the copy retained from the earlier investigation). Apache-2.0, © The Android Open Source Project. |
| `patches/0001-*.patch` | the acquisition fix described above, applied over the merged sources. |
| `src/androidMain/` | upstream's `AndroidManifest.xml` service declaration (namespace/minSdk now via DSL) and the aar consumer `proguard.txt`. |

`prepare-sources.sh` verifies the jars against `SHA256SUMS.txt`, extracts both, checks the
shared source sets are identical, merges them, applies the patch and stages the
`META-INF/.../LICENSE.txt` notice that upstream embeds in its artifacts.

## Rebuild

```sh
JAVA_HOME="$HOME/.local/jdks/jdk21" ANDROID_HOME="$HOME/android-sdk" \
    ./gradlew -p third_party/room3 publish
```

The Gradle/Kotlin/AGP toolchain and dependency versions follow the repository catalog
(`../../gradle/libs.versions.toml`); compile SDK 37, min SDK 23, JVM target 11. Published
variants: common metadata + sources, `-jvm` jar, `-android` aar — all POMs declare
Apache-2.0. Jars are built timestamp-free; a clean rebuild currently reproduces
`ARTIFACT_SHA256SUMS.txt` byte-for-byte. After rebuilding, commit the refreshed
`../room3-maven` tree together with any patch change.

## Removal condition

Delete `third_party/room3`, `third_party/room3-maven`, the `exclusiveContent` block in
`settings.gradle.kts` and the substitution in the root `build.gradle.kts` once a released
Room ships this fix (track the `androidx.room3` release notes), then pin `room3` in
`libs.versions.toml` to that release. The vendored version stays `3.0.3`, so bumping the
catalog entry without removing the substitution would keep resolving the patched 3.0.3 —
do not do that.

Database format is untouched (the patch changes pool scheduling only), so rolling forward
or back across this change keeps existing databases valid.
