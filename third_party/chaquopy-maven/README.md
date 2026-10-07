<!-- SPDX-License-Identifier: Unlicense -->

# `third_party/chaquopy-maven` — S7's self-built Chaquopy master

Local Maven repository that alone serves the plugin `com.chaquo.python:gradle` and the group
`com.chaquo.python.runtime` (the `exclusiveContent`
repositories in `settings.gradle.kts` and `build-logic/settings.gradle.kts`); the contents are
reviewed like source (01 Python and native components).

| Path | Provenance |
|---|---|
| `com/chaquo/python/gradle/17.1.0/` | `product/gradle-plugin` of <https://github.com/chaquo/chaquopy> @ `a41f0c9d309c70a39a13775acfe40fa3dc94bfdd` (master 2026-10-05, `VERSION.txt` 17.1.0), built with `./gradlew :gradle:publish` (Gradle 8.14.3, JDK 21; upstream's wrapper is too old for JDK 21 and `:runtime` was dropped from the product `settings.gradle` for the build — publish-only change). The published 17.0.0 plugin cannot run under the configuration cache; master's `a9f7d91` "Gradle modernizations" (#1465, milestone 17.1) fixes it. |
| `com/chaquo/python/runtime/{chaquopy_java,bootstrap,chaquopy,libchaquopy_java}/17.1.0/` | the published **17.0.0** artifacts from Maven Central republished under 17.1.0 (payload bytes identical, only coordinates/metadata versions rewritten). The plugin resolves its runtime at its own version; the runtime sources relevant to us are unchanged on master (its delta: `AssetPath.parent`, certifi 2026.7.22, test fixes — recorded in 01 S7), so these stand in for a real 17.1.0 runtime build, which needs the full CPython `target/prefix` tree and is out of scope for the spike. |

The CPython runtime zips (`com.chaquo.python:target:3.14.0-0`) are not vendored: they resolve
unchanged from Maven Central.

When Maven Central publishes a released 17.1.x (or newer) that keeps the CC fix, this directory
goes away and `chaquopy` in `gradle/libs.versions.toml` points at the release again.
