# 01 — Foundation

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the product owner's decisions (no flavors, per-ABI APKs, yt-dlp engine, no GPL); revised 2026-10-05 for PO-31–PO-35 (notify-only update check in `:core:domain`/`:core:model`/`:core:data` with no `:update:*` modules and no install permission; committed keystore; no mirror, no beta channel); **scope revision 2026-10-05 (S0–S13): the foundation becomes one Kotlin Multiplatform build for three products — the Android app, the desktop app and the sync server — with Compose Multiplatform, Metro instead of Hilt, Ktor over an OkHttp JVM island, source-set rules, release builds signed with the committed public keystore and profiled with baseline profiles, the brand-asset generator, the new licence policy (runtime exception, LGPL dynamically linked, MPL-2.0 data) and the M0a/M0b scaffold with spikes S8–S12 and S19**; **final cross-document review 2026-10-05:** desktop service ports in `:core:common` (`JobLanePoker`, `PowerMonitor`, `DesktopNotifier`, `LinuxDesktopPortal`), `:core:testing` edges, the explicit root contract of `NeutrodyneRoot`, Ktor's `UserAgent` plugin, the `SyncSettingsKey` route, the MS-RL, MPL-2.0-patch and Skiko/PSL licence entries · Implements: R3.7 (build side), R5.5 (brand-asset generator), R6.2–R6.3 (build side: placement of the update check, no install permission), R8.1 (shared-code structure), R8.11 (placement of `AppDirs`) / N1, N2, N3, N5 (APK sizes, build types), N7, N8, N10, N11, N12 (signing config), N13 (the `SYNC` client and the Android LAN permission) (foundation parts) · Milestones: M0a, M0b (primary); M1a–M11b, MD0–MD5, MS0–MS2 (incremental foundation work per [Delivery by milestone](#delivery-by-milestone)) · Honours: D2–D14, D28, D35, D43, D59–D64, D72–D80, D84–D97; PO-1, PO-35 (re-resolved), PO-2, PO-5, PO-8, PO-31–PO-34 resolved; PO-17 partly resolved; PO-3, PO-7, PO-13, PO-18, PO-39, PO-40, PO-42, PO-44 defaults · Owns: D81 (source sets and JVM islands), D82 (Metro and the graphs), the build side of D83 and D96, the toolchain and version catalog, convention plugins, modules and dependency rules, architecture and coroutine conventions, Android processes and the shared start-up bands, the YouTube bindings of both shells, Nav3 wiring and `IntentRouter`, build types (`release` published, `debug` local, `benchmarkRelease`, `nonMinifiedRelease`), the `neutrodynePublic` signing config, ABI splits, R8 and baseline-profile wiring, Chaquopy build integration, the brand-asset generator (`generateBrandAssets`, `checkBrandAssets`), the networking baseline (OkHttp island, Ktor factory, `SYNC` client, LAN guard), the Android 10–17 and desktop compliance checklists, the merged manifest, the licence policy (Gradle, Python, native and runtime components), the M0a/M0b scaffold and spikes S1–S12 and S19

Contents: [Scope](#scope) · [Toolchain and versions](#toolchain-and-versions) · [Module layout](#module-layout) ([Source sets and JVM islands](#source-sets-and-jvm-islands)) · [Dependency rules](#dependency-rules) · [Architecture patterns](#architecture-patterns) · [Dependency injection](#dependency-injection) · [Navigation](#navigation) · [Build variants and ABIs](#build-variants-and-abis) ([Release build and baseline profiles](#release-build-and-baseline-profiles)) · [Networking baseline](#networking-baseline) · [Platform compliance](#platform-compliance) · [Manifest and permissions](#manifest-and-permissions) · [Licensing and dependency policy](#licensing-and-dependency-policy) · [M0 scaffold checklist](#m0-scaffold-checklist) · [Spikes](#spikes) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

This document is the build-and-architecture contract every other design document stands on. Since the scope revision of 2026-10-05 one Gradle build produces three products from one Kotlin Multiplatform code base ([D81](../PLAN.md#3-key-decisions)): the Android app (`:app`), the desktop app for Windows, macOS and Linux (`:desktopApp`, [11](11-desktop.md)) and the self-hosted sync server (`:sync:server`, [10](10-sync.md)). "The app" means both apps unless a sentence names Android or the desktop. An engineer (or AI session) implementing M0a and M0b follows [M0 scaffold checklist](#m0-scaffold-checklist) top to bottom; later milestones come back here for conventions, source-set placement, the manifest and the dependency rules.

**Owned here** (other documents link, never restate):

| Topic | Section |
|---|---|
| Every library and tool version (Gradle and non-Gradle pins); `gradle/libs.versions.toml`; `settings.gradle.kts`; `gradle.properties` | [Toolchain and versions](#toolchain-and-versions) |
| `build-logic` convention plugins (`neutrodyne.*`), Gradle-side policy tasks, the brand-asset generator | [Convention plugins](#convention-plugins) |
| Module creation, packages, targets and source sets, per-module plugins and dependencies; what may live in `commonMain`, `androidMain`, `desktopMain`, a JVM island or a platform-only module | [Module layout](#module-layout), [Source sets and JVM islands](#source-sets-and-jvm-islands) |
| Module-graph and source-set assertion rules, the licence bans (`verifyDependencyPolicy`, Licensee, `checkPythonLicences`, `checkNativeLicences`) | [Dependency rules](#dependency-rules) |
| UDF/MVVM rules, `UiState`, events, paging in ViewModels, use-case rule, `Outcome`, `suspendRunCatching`, `Clock`, logging and redaction, coroutine/threading model, Android processes and start-up, the shared start-up bands, DataStore files and typed setting keys (incl. `synced`) | [Architecture patterns](#architecture-patterns) |
| Metro graphs (`AndroidAppGraph`, `YtxGraph`, `DesktopAppGraph`), scopes, contributions, YouTube bindings of both shells, test overrides | [Dependency injection](#dependency-injection) |
| Nav3 mechanics: installers, per-tab back stacks, `NavKeySerializers`, decorators, scene strategies, `IntentRouter` for Android intents and desktop links and files | [Navigation](#navigation) (behaviour: [08 Navigation](08-ui-ux.md#navigation)) |
| Android build types (`release` published, `debug` local, `benchmarkRelease`, `nonMinifiedRelease`), the `neutrodynePublic` signing config, R8 and baseline profiles, ABI splits, `BuildConfig`/`BuildInfo`, the no-engine switch for both apps, Chaquopy build integration | [Build variants and ABIs](#build-variants-and-abis) (YouTube capability semantics: [04 Capability matrix](04-youtube.md#capability-matrix); engine design: [04 YouTube engine](04-youtube.md#youtube-engine); desktop packages: [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception); server JAR: [10 Deployment](10-sync.md#deployment)) |
| The OkHttp client family (island), the common Ktor factory, derived clients incl. `SYNC`, interceptors, the LAN guard, network security config, network error taxonomy, `NetworkMonitor` on both platforms | [Networking baseline](#networking-baseline) |
| Android 10–17 rules → mechanism → owner; the desktop compliance list | [Platform compliance](#platform-compliance) |
| Merged Android manifest: every permission and component with its declaring module | [Manifest and permissions](#manifest-and-permissions) |
| Licence structure per artefact, Licensee allow-list, SPDX rule, Python, native and runtime lockfiles and their checks, APK/desktop/server scan inputs, AboutLibraries on both apps, About statements, contribution rule | [Licensing and dependency policy](#licensing-and-dependency-policy) |

**Not covered here:** schema, SQL and Room usage conventions ([02 Conventions](02-data-model.md#conventions)); feature behaviour ([03](03-feeds-and-discovery.md)–[07](07-downloads.md)); the shared playback core ([06 Shared playback core](06-playback.md#shared-playback-core)); the YouTube engine's runtime design — `:ytx` lifecycle, Binder API, OkHttp bridge, the shared engine module, engine updates ([04 YouTube engine](04-youtube.md#youtube-engine), [04 Engine updates](04-youtube.md#engine-updates)); screens, theming, brand rules and the `PlayerSheet` ([08](08-ui-ux.md)); CI workflows, test infrastructure, static-analysis gates, the committed keystore's generation and its trade-offs, release assets and the update check's design ([09](09-quality-and-release.md), [09 Update check](09-quality-and-release.md#update-check) — this document only defines the Gradle-side tasks those workflows call, the signing config that uses the committed keystore, and the modules the update check's classes live in); the sync protocol, client engine and server ([10](10-sync.md)); the desktop shell, `AppDirs` table, background runner, OS integration, audio engine, desktop YouTube host and packaging ([11](11-desktop.md)).

---

## Toolchain and versions

Serves N7, N8, N11. Delivered in M0a (catalog grows by milestone; M0b adds the desktop and server rows it uses). Versions never differ from this table without amending it and [D4](../PLAN.md#3-key-decisions).

### Version table

All verified 2026-10-04; the YouTube-engine, signature and CI-action rows on 2026-10-05; the Kotlin Multiplatform, desktop, server and release-build rows on 2026-10-05 for the scope revision (sources in [Sources](#sources)). This is the only document that lists every version; rows marked "not Gradle-managed" are pinned in a lockfile ([Python and native components](#python-and-native-components)).

| Area | Item | Version | Notes |
|---|---|---|---|
| Toolchain | Kotlin (KGP, `org.jetbrains.kotlin.multiplatform`, `org.jetbrains.kotlin.jvm`, Compose compiler plugin, serialization plugin) | 2.4.20 | KGP and the KMP plugin are tested only up to AGP 9.3.1 / Gradle 9.7.0 ([KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)) → [S1](#s1-kgp-2420-under-agp-941) |
| | Android Gradle Plugin | 9.4.1 (fallback 9.3.3) | `com.android.application` (`:app`), `com.android.library` (the Android-only modules), **`com.android.kotlin.multiplatform.library`** (every shared KMP module's Android target: one variant, no build types, flavors, `BuildConfig`, AIDL or NDK; Android resources, host and device tests opt-in, [Android-KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)), `com.android.test` (`:benchmark`). Under AGP 9 the KMP plugin no longer combines with `com.android.application`/`com.android.library`, so the app shells are separate modules ([AGP 9 migration](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)). Built-in Kotlin for `:app` and the Android-only libraries; no `kotlin-android`, no kapt. AGP 9.4 needs Gradle ≥ 9.6.0, JDK 17, Build Tools 36.0.0 |
| | Compose Multiplatform Gradle plugin `org.jetbrains.compose` | 1.12.1 | Built on Jetpack Compose 1.12.1, the line BOM 2026.09.00 gives Android ([CMP compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)); its `compose.*` dependency aliases are deprecated, so coordinates come from the catalog; its ProGuard `*Release*` packaging tasks are never run ([D3](../PLAN.md#3-key-decisions)); the 1.13 `aot {}` DSL is adopted once stable ([PO-42](../PLAN.md#48-further-product-owner-decisions)) |
| | Gradle | **9.7.1** | 9.8.0 is current but outside Kotlin's tested range ([D4](../PLAN.md#3-key-decisions)) |
| | KSP | 2.3.12 | Room only (Hilt and its processors are gone); min AGP 8.12.0 |
| | Metro compiler plugin `dev.zacsweers.metro` | 1.4.5 | Apache-2.0; Kotlin 2.4.20 supported from Metro 1.2.0 ([Metro compatibility](https://zacsweers.github.io/metro/latest/compatibility/)) → [S8](#s8-metro-across-kmp-modules) |
| | JDKs | Temurin 21 runs Gradle; Gradle toolchains: JDK 25 for `:desktopApp` and the desktop-only modules, JDK 21 for `:sync:server` | Bytecode 17 for `:app`, the Android-only libraries, every KMP module and every JVM island (Android consumes them); 21 for the server; 25 for desktop-only modules (FFM is final since JDK 22, [JEP 454](https://openjdk.org/jeps/454)). Unverified: Kotlin 2.4.20 with `jvmTarget` 25 (fallback: `jvmTarget` 21 compiled against the JDK 25 toolchain; S13 records it). Robolectric SDK 36+ needs JDK 21 |
| | Android Studio | Rabbit 1 (2026.2.1) | Supports AGP 7.1–9.4 |
| | SDK levels (Android) | minSdk 26, compileSdk 37, targetSdk 37; Build Tools 36.0.0 | [D5](../PLAN.md#3-key-decisions), [PO-7](../PLAN.md#po-7-minsdk); every KMP module's Android target uses the same levels. Compose 1.12 requires compileSdk 37 and AGP ≥ 9.2 |
| | Desktop and server platforms | Windows 10+ x64, macOS 13+ arm64, Linux x64/arm64 (glibc ≥ 2.31); server on Java 21+ | [D88](../PLAN.md#3-key-decisions), [D95](../PLAN.md#3-key-decisions); per-target detail in [11 Platform matrix](11-desktop.md#platform-matrix) |
| UI | Compose Multiplatform core (`org.jetbrains.compose.runtime:runtime`, `…ui:ui`, `…foundation:foundation`, `…animation:animation`, `org.jetbrains.compose.components:components-resources`, `…ui:ui-tooling-preview`) | 1.12.1 | Android variants resolve `androidx.compose.*` 1.12.1. Unverified: the coordinate of the common `@Preview` artifact under 1.12 (S9) |
| | Compose BOM (Android-only artifacts of `:app` and `:benchmark`: `ui-test-junit4`, `ui-test-junit4-accessibility`, `ui-test-manifest`, `ui-tooling`) | 2026.09.00 → ui 1.12.1, material3 1.4.0 | Also a platform constraint on `:app`'s runtime classpath, so Android's androidx Compose artifacts stay on the BOM line. Expressive (`material3` 1.5.0-alpha29) rejected: it pulls Compose core 1.13.0-alpha01 ([D6](../PLAN.md#3-key-decisions)) |
| | Material 3 multiplatform (`org.jetbrains.compose.material3:material3`, `material3-adaptive-navigation-suite`) | 1.9.0 | Android variants depend on androidx `material3` and `material3-adaptive-navigation-suite` **1.4.0 stable**; the desktop binary was built against Compose 1.9.1 (binary compatibility with 1.12.1: [S9](#s9-compose-multiplatform-ui-stack)); every newer multiplatform material3 is an alpha of the Expressive line ([PO-4](../PLAN.md#po-4-material-3-expressive)) |
| | Material 3 adaptive multiplatform (`org.jetbrains.compose.material3.adaptive:adaptive`, `adaptive-layout`, `adaptive-navigation3`) | 1.3.0-rc01 | Android variants depend on androidx 1.3.0 stable; only the desktop binaries are a release candidate (S9) |
| | Navigation 3 | `androidx.navigation3:navigation3-runtime` 1.2.0 (KMP); `org.jetbrains.androidx.navigation3:navigation3-ui` 1.1.2 | Google's `navigation3-ui` is Android-only; JetBrains 1.1.2's Android variant resolves androidx `navigation3-ui` 1.1.7, and `:app` pins androidx 1.2.0, so Android runs 1.2.0 while common code compiles against the 1.1 API ([Open questions](#open-questions) 17) |
| | Lifecycle multiplatform (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-navigation3`) | 2.11.0 | Android-only `androidx.lifecycle:lifecycle-process` 2.11.0 stays in `androidMain` and `:app` |
| | activity-compose / appcompat / core-ktx / core-splashscreen | 1.13.0 / 1.8.0 / 1.19.1 / 1.2.0 | `:app` only; AppCompat for per-app language |
| | graphics-shapes | 1.1.0 | Play/pause morph only. Unverified: a desktop variant (S9; fallback: a crossfade of two icons in common code) |
| | `com.materialkolor:material-color-utilities` | 5.0.1 | KMP; not `material-kolor`, not `androidx.palette` ([D57](../PLAN.md#3-key-decisions)). Unverified: POM licence MIT vs Apache-2.0 (both allowed) |
| | Coil 3 (`coil`, `coil-compose`, `coil-network-okhttp`, `coil-test`) | 3.6.3 | `coil`/`coil-compose` in common code; `coil-network-okhttp` (Android and JVM variants) only in platform source sets, on the island's IMAGE client ([D10](../PLAN.md#3-key-decisions)) |
| | `sh.calvin.reorderable:reorderable` | 3.1.0 | KMP, Apache-2.0 |
| | AboutLibraries (Gradle plugin + `aboutlibraries-core`) | 15.2.0 | Core is KMP, no Compose UI artifact ([Licensing](#aboutlibraries-and-the-licences-screen)); the plugin runs on `:app` and `:desktopApp` |
| | Glance (`glance-appwidget`, `glance-material3`) | 1.2.0 | v1.x (M13) only |
| DI | Metro runtime (added by the plugin), `dev.zacsweers.metro:metrox-viewmodel`, `metrox-viewmodel-compose` | 1.4.5 | `metrox-android` is not used: it needs minSdk 28 ([MetroX Android](https://zacsweers.github.io/metro/latest/metrox-android/)) |
| | Koin + its compiler plugin | 4.2.2 / 1.2.1 | **Fallback only**, absent from the catalog unless S8 fails ([D8](../PLAN.md#3-key-decisions)) |
| Data | Room 3 KMP (`room3-runtime`, `room3-paging`, `room3-compiler`, `room3-testing`, plugin `androidx.room3`) | 3.0.3 | KSP per target (`kspAndroid`, `kspDesktop`); UUIDs as `TEXT` ([D21](../PLAN.md#3-key-decisions)) |
| | `androidx.sqlite:sqlite-bundled` / `sqlite-framework` | 2.7.1 | `BundledSQLiteDriver` in production on Android and the desktop (JVM natives `linux_x64`, `linux_arm64`, `osx_arm64`, `windows_x64`; none for `windows_arm64` or `osx_x64`, [sqlite-bundled-jvm 2.7.1](https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled-jvm/2.7.1/sqlite-bundled-jvm-2.7.1.jar)); `sqlite-framework` (`AndroidSQLiteDriver`) only in Android Robolectric tests ([D9](../PLAN.md#3-key-decisions)) |
| | Paging (`paging-common`, `paging-compose`, `paging-testing`) | 3.5.1 | KMP, desktop variants included |
| | DataStore (`datastore-preferences-core`) | 1.2.1 | Preferences DataStore is the KMP one ([DataStore KMP](https://developer.android.com/kotlin/multiplatform/datastore)) |
| | WorkManager (`work-runtime`, `work-testing`) | 2.12.0 | Android only (`androidMain`, `:youtube:ytdlp`) |
| | documentfile | 1.1.0 | SAF, v1.x |
| Network | OkHttp BOM (`okhttp`, `okhttp-coroutines`, `okhttp-tls`, `mockwebserver3`, `mockwebserver3-junit4`) | 5.5.0 | Clients are built only in the island `:core:network:okhttp`; **no OkHttp `Cache`** ([D10](../PLAN.md#3-key-decisions)) |
| | Ktor client (`ktor-client-core`, `ktor-client-okhttp`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`) via `ktor-bom` | 3.6.0 | The HTTP API of common code, on the OkHttp engine with the island's client as `preconfigured` ([Ktor client engines](https://ktor.io/docs/client-engines.html)); `ktor-client-okhttp` is JVM-only, so it appears only in platform source sets; client SSE ([Ktor client SSE](https://ktor.io/docs/client-server-sent-events.html)) for 10's `SyncEventsClient` → [S12](#s12-ktor-fetch-pipeline) |
| | Okio | 3.18.2 | KMP file system and hashing |
| | kotlinx.serialization JSON | 1.11.0 | |
| | kotlinx.coroutines (`core`, `android`, `swing`, `guava`, `test`) | 1.11.0 | `-swing` gives the desktop `Dispatchers.Main` (Compose desktop does not depend on it); `-guava` for Media3 futures |
| | kotlinx-datetime | 0.8.0 | With `kotlin.time.Instant`/`Clock` (stable since Kotlin 2.3) |
| | kotlinx-collections-immutable | 0.5.2 | |
| | jsoup | 1.23.2 | MIT; `:feeds:jvm` only |
| | kxml2 | 2.3.0 | `compileOnly` + `testImplementation` in `:feeds:jvm`; `runtimeOnly` in `:desktopApp` (Android has the platform `XmlPullParser`) |
| Media | Media3 (`exoplayer`, `session`, `datasource-okhttp`, `ui-compose`, `common-ktx`, `inspector`, `test-utils`, `test-utils-robolectric`) | 1.11.1 | Android only; `@UnstableApi` opt-in module-wide only in `:playback:impl` (lint config, [Convention plugins](#convention-plugins)); `ui-compose` unused until M14 ([06 UI boundary](06-playback.md#ui-boundary)); no `media3-cast` (Chromecast not planned, [PO-6](../PLAN.md#po-6-chromecast)) |
| | FFmpeg, minimal LGPL-2.1 build (`avutil`, `swresample`, `avcodec`, `avformat`) | 9.0.x (9.0.2 measured) | Not Gradle-managed: built by `playback/native/ffmpeg/build.sh` on each desktop runner, pinned with its source SHA-256 in `playback/native/native-components.lock` ([D86](../PLAN.md#3-key-decisions), [11 Desktop playback engine](11-desktop.md#desktop-playback-engine)) |
| | miniaudio | 0.11.x (0.11.25 measured) | Not Gradle-managed; vendored source in `:playback:native`, public domain or MIT-0 ([miniaudio](https://github.com/mackron/miniaudio)) |
| Desktop | JNA (`jna`, `jna-platform`) | 5.19.1 | Dual `Apache-2.0 OR LGPL-2.1-or-later`, used under Apache-2.0 ([JNA licence](https://github.com/java-native-access/jna/blob/master/LICENSE)); DPAPI, the Windows Run key, `AllowSetForegroundWindow`, `SHGetKnownFolderPath` |
| | dbus-java (`dbus-java-core`, `dbus-java-transport-native-unixsocket`) | 5.2.2 | MIT ([dbus-java](https://github.com/hypfvieh/dbus-java)); Linux MPRIS, logind, portals, notifications in `:desktop:system` |
| | Temurin (bundled desktop runtime and the server image's JDK) | 25 LTS (25.0.4.1+1 at planning time) | Not Gradle-managed: one vendor and version per release, pinned per target with its source tarball in `desktopApp/runtime.lock`; jlink'd, never modified; runtime exception of [D3](../PLAN.md#3-key-decisions) ([Temurin 25 releases](https://github.com/adoptium/temurin25-binaries/releases)); no Windows AArch64 build ([D88](../PLAN.md#3-key-decisions)) |
| | WiX Toolset | 3.14 or 5.x | Build tool on the Windows runner only (MS-RL), found by jpackage; Unverified whether a default jpackage MSI embeds WiX-licensed binaries ([11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)) |
| YouTube engine (Android: `:youtube:ytdlp` only, [D72](../PLAN.md#3-key-decisions)) | Chaquopy Gradle plugin and runtime (`com.chaquo.python:gradle`, plugin ID `com.chaquo.python`) | **17.1.0, self-built** from master @ `a41f0c9` into `third_party/chaquopy-maven` (S7, 2026-10-06: released 17.0.0 of 2025-11-30 is not configuration-cache safe; the runtime payloads inside are the released 17.0.0 bits republished under 17.1.0) | MIT. Its published documentation names AGP 7.3–9.2 and allows the plugin in one module per app; master carries the AGP 9.x updates up to 9.4.1 and target API 37 → [S7](#s7-chaquopy-under-agp-941) |
| | CPython runtime (Chaquopy `com.chaquo.python:target`) | 3.14.0-0 (fallback 3.13.9-0) | PSF-2.0; Python ≥ 3.12 exists only for `arm64-v8a` and `x86_64` ([D77](../PLAN.md#3-key-decisions)). Build-time `.pyc` compilation needs a build-host Python of the same minor version (`buildPython`): 3.14 in CI via `actions/setup-python`, and in 09's release container `python:3.14-slim-trixie` ([09 release.yml](09-quality-and-release.md#releaseyml); Debian trixie's own `python3` is 3.13). Bundled native libraries per [Python and native components](#python-and-native-components) |
| YouTube engine (desktop: `:youtube:ytdlp-desktop`, [D90](../PLAN.md#3-key-decisions)) | python-build-standalone | release `20261003` (CPython 3.14.8) | Not Gradle-managed: fetched by `fetchPythonStandalone`, trimmed by `trimPythonStandalone`, pinned per target in `youtube/ytdlp-desktop/python-components.lock`; libedit instead of readline, `_gdbm` disabled upstream, `_dbm` and Tcl/Tk removed by our trim ([python-build-standalone](https://github.com/astral-sh/python-build-standalone/blob/main/docs/running.rst), [11 Desktop YouTube engine host](11-desktop.md#desktop-youtube-engine-host)) |
| Both engine hosts | yt-dlp (official zipimport release asset `yt-dlp`, incl. yt-dlp-ejs 0.8.0) | 2026.08.19 | Unlicense; vendored once and packaged by both hosts (path and layout: 04), not Gradle-managed and not a Renovate dependency: bumped only to canary-approved versions by `scripts/engine/bump-ytdlp.sh` ([04 Engine updates](04-youtube.md#engine-updates)); no optional extras (never `mutagen`) |
| | Tink (`com.google.crypto.tink:tink-android`) | 1.23.0 | Apache-2.0; M9b, Android only: Ed25519 verification of the engine manifest below API 33 (`java.security.Signature` supports Ed25519 from API 33); the desktop uses the JDK's Ed25519 |
| | quickjs-kt (`io.github.dokar3:quickjs-kt-android`; desktop `quickjs-kt-jvm`) | 1.0.15 | Apache-2.0, bundles QuickJS (MIT); **only if** the JS challenge provider passes the M9a spike ([D75](../PLAN.md#3-key-decisions)); the JVM artifact has no Windows arm64 native ([quickjs-kt-jvm 1.0.15](https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt-jvm/1.0.15/)) |
| | OpenPGP verification of yt-dlp's `SHA2-256SUMS.sig` | — (no library) | 04's `OpenPgpDetachedVerifier` and build-logic's `verifyBundledYtDlp` parse the v4 signature packet and verify it with the JDK's `Signature("SHA512withRSA")` against the pinned key; Bouncy Castle and PGPainless are not used for this |
| Server (`:sync:server` only, [D94](../PLAN.md#3-key-decisions)) | Ktor server (`ktor-server-core`, `-cio`, `-content-negotiation`, `-sse`, `-auth`, `-sessions`, `-rate-limit`, `-csrf`, `-compression`, `-call-id`, `-status-pages`, `-default-headers`, `-html-builder`; `ktor-server-test-host`) via `ktor-bom` | 3.6.0 | Apache-2.0; plugin use: [10 Server architecture](10-sync.md#server-architecture); server SSE ([Ktor server SSE](https://ktor.io/docs/server-server-sent-events.html)) |
| | kotlinx-html | 0.12.0 | Apache-2.0; through `ktor-server-html-builder` |
| | sqlite-jdbc (`org.xerial:sqlite-jdbc`) | 3.53.4.0 | Apache-2.0, SQLite public domain ([sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)) |
| | Bouncy Castle `org.bouncycastle:bcprov-jdk18on` | 1.86 | MIT ([licence](https://www.bouncycastle.org/about/license/)); Argon2id |
| | slf4j-api / slf4j-simple | 2.0.20 | MIT; logback is banned (EPL-2.0/LGPL-2.1, [logback licence](https://logback.qos.ch/license.html)) |
| Quality | JUnit 4 / TestParameterInjector / Truth / Turbine / MockK / `kotlin-test` | 4.13.2 / 1.24 / 1.4.5 / 1.2.1 / 1.14.11 / 2.4.20 | `kotlin-test` and Turbine in `commonTest`; JUnit 4, Truth and TestParameterInjector are JVM-only, so tests that need them live in `desktopTest` or Android tests; MockK never in `androidTest` |
| | Robolectric | 4.17 (pin `sdk=36`) | Android-only code; force 4.17 and `okhttp-bom` 5.5.0 over Media3 test-utils' 4.16 / MockWebServer 4.12 |
| | Roborazzi (`roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule`, `roborazzi-compose-desktop`) | 1.76.0 | [D59](../PLAN.md#3-key-decisions); desktop goldens rendered on Linux only |
| | androidx.test runner / ext-junit / espresso / orchestrator / uiautomator | 1.7.0 / 1.3.0 / 3.7.0 / 1.6.1 / 2.4.0 | |
| | Compose tests: `ui-test-junit4` (v2 APIs), `ui-test-junit4-accessibility`, `ui-test-manifest` (BOM); `org.jetbrains.compose.ui:ui-test` | 1.12.1 | `runComposeUiTest` for shared UI on the desktop JVM and on Android ([Compose MP testing](https://kotlinlang.org/docs/multiplatform/compose-test.html)); `ui-test-manifest` as `debugImplementation` of `:app` only (never on `releaseRuntimeClasspath`, [Gradle-side policy tasks](#gradle-side-policy-tasks)) |
| | benchmark-macro-junit4; profileinstaller | 1.5.0; 1.4.1 | Macrobenchmarks in `:benchmark` against `benchmarkRelease` (M10); `profileinstaller` is an `implementation` dependency of `:app` from M0a, because outside Google Play the baseline profile reaches the device only through ProfileInstaller ([Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)) |
| | `androidx.baselineprofile` Gradle plugin | 1.5.0 | Latest release ([maven metadata](https://dl.google.com/android/maven2/androidx/baselineprofile/androidx.baselineprofile.gradle.plugin/maven-metadata.xml)); producer in `:benchmark`, consumer in `:app`; creates `benchmarkRelease` and `nonMinifiedRelease` → [S19](#s19-release-build-with-r8-and-baseline-profiles) |
| | LeakCanary | 2.14 | `debugImplementation` of `:app` only ([Debug build type](#debug-build-type)) |
| Tooling | Spotless / ktlint / compose-rules | 8.10.3 / 1.8.0 / 0.6.7 | blocking |
| | detekt | 2.0.0-alpha.6 | non-blocking |
| | Licensee / module-graph-assertion | 1.14.1 / 2.9.1 | Licensee supports `org.jetbrains.kotlin.multiplatform` and `kotlin("jvm")` ([Licensee](https://github.com/cashapp/licensee)); the graph plugin sees KMP source-set edges through their configuration names (verified 2026-10-06, [Module-graph assertion configuration](#module-graph-assertion-configuration)) |
| | ACRA (`acra-mail`, `acra-dialog`) | 5.14.2 | [D62](../PLAN.md#3-key-decisions); Android release builds; never installed in `:ytx`; mailbox forced empty in `debug` |
| CI (09) | actions/checkout, setup-java, gradle/actions, upload-artifact, codeql-action | v7.0.1, v6.0.1, v6.4.0, v7.0.1, v4.38.2 (SHA-pinned) | android-emulator-runner v2.38.0 as GMD fallback; no release action: `release.yml` publishes with the runner's preinstalled `gh` CLI ([09 release.yml](09-quality-and-release.md#releaseyml)) |
| | actions/setup-python, actions/attest, actions/deploy-pages | v7.0.0, v4.2.2, v5.0.1 (SHA-pinned; checked 2026-10-05) | host CPython 3.14 for `.pyc` compilation, `shimTest` and `shimTestStdio`; provenance attestations ([D79](../PLAN.md#3-key-decisions)); engine manifest on GitHub Pages ([D76](../PLAN.md#3-key-decisions)) |

**Banned** (enforced by [`verifyDependencyPolicy`](#gradle-side-policy-tasks) and plugin guards): `org.jetbrains.kotlin.android`, `kotlin-kapt` / `org.jetbrains.kotlin.kapt`, `androidx.compose.material:material-icons-extended`, `androidx.palette:*`, `com.materialkolor:material-kolor*` (Compose artifact), OkHttp `Cache`, any `com.google.android.gms`, `com.google.firebase`, `com.google.android.play`, `com.crashlytics`, `io.sentry` artifact in any configuration (no build carries a proprietary SDK, [D62](../PLAN.md#3-key-decisions)), `androidx.security:security-crypto` (deprecated; Keystore directly), `com.google.dagger:*` and `androidx.hilt:*` (Metro replaces Hilt, [D82](../PLAN.md#3-key-decisions)), `dev.dirs:directories` (MPL-2.0 code; `AppDirs` is ours), and every component [D3](../PLAN.md#3-key-decisions) rules out: NewPipe Extractor (`com.github.teamnewpipe`, `com.github.TeamNewPipe`), Rhino (`org.mozilla:rhino*`), any `*youtubedl-android*` coordinate, `com.android.tools:desugar_jdk_libs*` (`java.time` is native from API 26; AGP's AAR-metadata check fails the build if a dependency still demands desugaring), `com.guardsquare:proguard*` (GPL-2.0; the Compose desktop `*Release*` tasks would pull it), `org.openjdk:jextract*` (GPL-2.0; FFM bindings are hand-written), `org.openjfx:javafx-media`, `org.bytedeco:*-gpl`, `uk.co.caprica:vlcj` (GPL-3.0), `org.freedesktop.gstreamer:*` and `gst1-java-core`, `ch.qos.logback:*` (use slf4j-simple), `de.mkammerer:argon2-jvm` (LGPL; Bouncy Castle instead), `org.mariadb.jdbc:*` (LGPL). The `com.chaquo.python` plugin is allowed only in `:youtube:ytdlp` ([common Android configuration](#common-android-configuration)).

### `gradle/libs.versions.toml`

Complete catalog. Coordinates marked `# M9b` / `# v1.x` are declared now so Renovate tracks them; nothing references them until that milestone. yt-dlp, FFmpeg, miniaudio, python-build-standalone and the Temurin runtime are not in the catalog (lockfiles, [Python and native components](#python-and-native-components)).

```toml
[versions]
agp = "9.4.1"                       # fallback "9.3.3" (Spike S1)
kotlin = "2.4.20"
ksp = "2.3.12"                      # Room only
composeMultiplatform = "1.12.1"     # org.jetbrains.compose plugin and the org.jetbrains.compose.* core artifacts
composeBom = "2026.09.00"           # Android-only Compose artifacts of :app and :benchmark
cmpMaterial3 = "1.9.0"              # -> androidx material3 1.4.0 on Android (D6)
cmpMaterial3Adaptive = "1.3.0-rc01" # -> androidx 1.3.0 on Android; rc only on the desktop (S9)
navigation3 = "1.2.0"               # Google navigation3-runtime (KMP); :app pins androidx navigation3-ui to it
navigation3UiJb = "1.1.2"           # JetBrains navigation3-ui for common code
lifecycle = "2.11.0"                # JetBrains lifecycle (common) and androidx lifecycle-process (Android)
activity = "1.13.0"
appcompat = "1.8.0"
coreKtx = "1.19.1"
coreSplashscreen = "1.2.0"
graphicsShapes = "1.1.0"
glance = "1.2.0"                    # v1.x
materialColorUtilities = "5.0.1"
coil = "3.6.3"
reorderable = "3.1.0"
aboutlibraries = "15.2.0"
metro = "1.4.5"
room3 = "3.0.3"
sqlite = "2.7.1"
paging = "3.5.1"
datastore = "1.2.1"
work = "2.12.0"
documentfile = "1.1.0"              # v1.x
okhttp = "5.5.0"
ktor = "3.6.0"
okio = "3.18.2"
kotlinxSerialization = "1.11.0"
kotlinxCoroutines = "1.11.0"
kotlinxDatetime = "0.8.0"
kotlinxCollectionsImmutable = "0.5.2"
kotlinxHtml = "0.12.0"              # resolved through ktor-server-html-builder; declared for Renovate and the constraint
jsoup = "1.23.2"
kxml2 = "2.3.0"
media3 = "1.11.1"
jna = "5.19.1"
dbusJava = "5.2.2"
sqliteJdbc = "3.53.4.0"
bouncycastle = "1.86"
slf4j = "2.0.20"
chaquopy = "17.1.0"                 # :youtube:ytdlp only; S7 (2026-10-06): self-built master @ a41f0c9 in third_party/chaquopy-maven (17.0.0 cannot do the configuration cache)
tink = "1.23.0"                     # M9b
quickjsKt = "1.0.15"                # M9b / MD3, only if the JS challenge provider ships (D75)
acra = "5.14.2"
junit4 = "4.13.2"
testParameterInjector = "1.24"
truth = "1.4.5"
turbine = "1.2.1"
mockk = "1.14.11"
robolectric = "4.17"
roborazzi = "1.76.0"
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"
espresso = "3.7.0"
androidxTestOrchestrator = "1.6.1"
uiautomator = "2.4.0"
benchmark = "1.5.0"
baselineprofile = "1.5.0"
profileinstaller = "1.4.1"
leakcanary = "2.14"
spotless = "8.10.3"
ktlint = "1.8.0"
composeRules = "0.6.7"
detekt = "2.0.0-alpha.6"
licensee = "1.14.1"
moduleGraphAssert = "2.9.1"
```

```toml
[libraries]
# Compose Multiplatform (common code; Android variants resolve androidx.compose 1.12.1)
cmp-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "composeMultiplatform" }
cmp-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "composeMultiplatform" }
cmp-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "composeMultiplatform" }
cmp-animation = { module = "org.jetbrains.compose.animation:animation", version.ref = "composeMultiplatform" }
cmp-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "composeMultiplatform" }
cmp-ui-tooling-preview = { module = "org.jetbrains.compose.ui:ui-tooling-preview", version.ref = "composeMultiplatform" }   # Unverified coordinate (S9)
cmp-ui-test = { module = "org.jetbrains.compose.ui:ui-test", version.ref = "composeMultiplatform" }                          # runComposeUiTest
# :desktopApp adds the per-OS desktop runtime with the Compose plugin's `compose.desktop.currentOs` (desktop-jvm-<os>-<arch> + Skiko natives of the runner; 11)
cmp-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "cmpMaterial3" }
cmp-material3-navigationSuite = { module = "org.jetbrains.compose.material3:material3-adaptive-navigation-suite", version.ref = "cmpMaterial3" }
cmp-material3-adaptive = { module = "org.jetbrains.compose.material3.adaptive:adaptive", version.ref = "cmpMaterial3Adaptive" }
cmp-material3-adaptive-layout = { module = "org.jetbrains.compose.material3.adaptive:adaptive-layout", version.ref = "cmpMaterial3Adaptive" }
cmp-material3-adaptive-navigation3 = { module = "org.jetbrains.compose.material3.adaptive:adaptive-navigation3", version.ref = "cmpMaterial3Adaptive" }
navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui-jb = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "navigation3UiJb" }
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-navigation3 = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
graphics-shapes = { module = "androidx.graphics:graphics-shapes", version.ref = "graphicsShapes" }
materialColorUtilities = { module = "com.materialkolor:material-color-utilities", version.ref = "materialColorUtilities" }
coil-bom = { module = "io.coil-kt.coil3:coil-bom", version.ref = "coil" }
coil-core = { module = "io.coil-kt.coil3:coil" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp" }        # platform source sets only
coil-test = { module = "io.coil-kt.coil3:coil-test" }
reorderable = { module = "sh.calvin.reorderable:reorderable", version.ref = "reorderable" }
aboutlibraries-core = { module = "com.mikepenz:aboutlibraries-core", version.ref = "aboutlibraries" }
# Android-only UI (:app, :benchmark; Compose artifacts take versions from the BOM)
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "coreSplashscreen" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }                 # debugImplementation of :app only
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-junit4-accessibility = { module = "androidx.compose.ui:ui-test-junit4-accessibility" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }     # debugImplementation of :app only
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }   # :app pin, Android only
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
androidx-glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
androidx-glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
# DI (the Metro runtime itself is added by the dev.zacsweers.metro plugin)
metrox-viewmodel = { module = "dev.zacsweers.metro:metrox-viewmodel", version.ref = "metro" }
metrox-viewmodel-compose = { module = "dev.zacsweers.metro:metrox-viewmodel-compose", version.ref = "metro" }
# Data
androidx-room3-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room3" }
androidx-room3-paging = { module = "androidx.room3:room3-paging", version.ref = "room3" }
androidx-room3-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room3" }
androidx-room3-testing = { module = "androidx.room3:room3-testing", version.ref = "room3" }
androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
androidx-sqlite-framework = { module = "androidx.sqlite:sqlite-framework", version.ref = "sqlite" }   # Android Robolectric tests only
androidx-paging-common = { module = "androidx.paging:paging-common", version.ref = "paging" }
androidx-paging-compose = { module = "androidx.paging:paging-compose", version.ref = "paging" }
androidx-paging-testing = { module = "androidx.paging:paging-testing", version.ref = "paging" }
androidx-datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "datastore" }
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
androidx-documentfile = { module = "androidx.documentfile:documentfile", version.ref = "documentfile" }
# Network and serialization (OkHttp and Ktor artifacts take versions from their BOMs)
okhttp-bom = { module = "com.squareup.okhttp3:okhttp-bom", version.ref = "okhttp" }
okhttp = { module = "com.squareup.okhttp3:okhttp" }
okhttp-coroutines = { module = "com.squareup.okhttp3:okhttp-coroutines" }
okhttp-tls = { module = "com.squareup.okhttp3:okhttp-tls" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3" }
okhttp-mockwebserver3-junit4 = { module = "com.squareup.okhttp3:mockwebserver3-junit4" }
ktor-bom = { module = "io.ktor:ktor-bom", version.ref = "ktor" }
ktor-client-core = { module = "io.ktor:ktor-client-core" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp" }                          # platform source sets only
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json" }
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-guava = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-guava", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinxDatetime" }
kotlinx-collections-immutable = { module = "org.jetbrains.kotlinx:kotlinx-collections-immutable", version.ref = "kotlinxCollectionsImmutable" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
kxml2 = { module = "net.sf.kxml:kxml2", version.ref = "kxml2" }
# Media (Android)
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
androidx-media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }
androidx-media3-datasource-okhttp = { module = "androidx.media3:media3-datasource-okhttp", version.ref = "media3" }
androidx-media3-ui-compose = { module = "androidx.media3:media3-ui-compose", version.ref = "media3" }
androidx-media3-common-ktx = { module = "androidx.media3:media3-common-ktx", version.ref = "media3" }
androidx-media3-inspector = { module = "androidx.media3:media3-inspector", version.ref = "media3" }
androidx-media3-test-utils = { module = "androidx.media3:media3-test-utils", version.ref = "media3" }
androidx-media3-test-utils-robolectric = { module = "androidx.media3:media3-test-utils-robolectric", version.ref = "media3" }
# Desktop
jna = { module = "net.java.dev.jna:jna", version.ref = "jna" }
jna-platform = { module = "net.java.dev.jna:jna-platform", version.ref = "jna" }
dbus-java-core = { module = "com.github.hypfvieh:dbus-java-core", version.ref = "dbusJava" }
dbus-java-transport-native-unixsocket = { module = "com.github.hypfvieh:dbus-java-transport-native-unixsocket", version.ref = "dbusJava" }
# Server (:sync:server only)
ktor-server-core = { module = "io.ktor:ktor-server-core" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation" }
ktor-server-sse = { module = "io.ktor:ktor-server-sse" }
ktor-server-auth = { module = "io.ktor:ktor-server-auth" }
ktor-server-sessions = { module = "io.ktor:ktor-server-sessions" }
ktor-server-rate-limit = { module = "io.ktor:ktor-server-rate-limit" }
ktor-server-csrf = { module = "io.ktor:ktor-server-csrf" }
ktor-server-compression = { module = "io.ktor:ktor-server-compression" }
ktor-server-call-id = { module = "io.ktor:ktor-server-call-id" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages" }
ktor-server-default-headers = { module = "io.ktor:ktor-server-default-headers" }
ktor-server-html-builder = { module = "io.ktor:ktor-server-html-builder" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host" }
kotlinx-html = { module = "org.jetbrains.kotlinx:kotlinx-html", version.ref = "kotlinxHtml" }        # constraint only
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqliteJdbc" }
bouncycastle-bcprov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
# YouTube engine (the Chaquopy runtime comes through its plugin, not the catalog)
tink-android = { module = "com.google.crypto.tink:tink-android", version.ref = "tink" }                 # M9b, :youtube:ytdlp
quickjs-kt-android = { module = "io.github.dokar3:quickjs-kt-android", version.ref = "quickjsKt" }      # M9b, conditional (D75)
quickjs-kt-jvm = { module = "io.github.dokar3:quickjs-kt-jvm", version.ref = "quickjsKt" }              # MD3, conditional (D75)
# Crash reporting (Android)
acra-mail = { module = "ch.acra:acra-mail", version.ref = "acra" }
acra-dialog = { module = "ch.acra:acra-dialog", version.ref = "acra" }
# Test
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
testParameterInjector = { module = "com.google.testparameterinjector:test-parameter-injector", version.ref = "testParameterInjector" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
roborazzi = { module = "io.github.takahirom.roborazzi:roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
roborazzi-compose-desktop = { module = "io.github.takahirom.roborazzi:roborazzi-compose-desktop", version.ref = "roborazzi" }
roborazzi-junit-rule = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }
androidx-test-orchestrator = { module = "androidx.test:orchestrator", version.ref = "androidxTestOrchestrator" }
androidx-test-uiautomator = { module = "androidx.test.uiautomator:uiautomator", version.ref = "uiautomator" }
androidx-benchmark-macro-junit4 = { module = "androidx.benchmark:benchmark-macro-junit4", version.ref = "benchmark" }
androidx-profileinstaller = { module = "androidx.profileinstaller:profileinstaller", version.ref = "profileinstaller" }   # :app implementation (M0a)
leakcanary-android = { module = "com.squareup.leakcanary:leakcanary-android", version.ref = "leakcanary" }   # debugImplementation of :app
# build-logic classpath only (plugin markers for third-party plugins: <id>:<id>.gradle.plugin)
android-gradlePlugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-composeGradlePlugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
kotlin-serializationGradlePlugin = { module = "org.jetbrains.kotlin:kotlin-serialization", version.ref = "kotlin" }
compose-gradlePlugin = { module = "org.jetbrains.compose:compose-gradle-plugin", version.ref = "composeMultiplatform" }
ksp-gradlePlugin = { module = "com.google.devtools.ksp:symbol-processing-gradle-plugin", version.ref = "ksp" }
metro-gradlePlugin = { module = "dev.zacsweers.metro:gradle-plugin", version.ref = "metro" }                # confirmed by S8 (2026-10-06)
room3-gradlePlugin = { module = "androidx.room3:room3-gradle-plugin", version.ref = "room3" }
baselineprofile-gradlePlugin = { module = "androidx.baselineprofile:androidx.baselineprofile.gradle.plugin", version.ref = "baselineprofile" }
spotless-gradlePlugin = { module = "com.diffplug.spotless:com.diffplug.spotless.gradle.plugin", version.ref = "spotless" }
licensee-gradlePlugin = { module = "app.cash.licensee:app.cash.licensee.gradle.plugin", version.ref = "licensee" }
moduleGraphAssert-gradlePlugin = { module = "com.jraska.module.graph.assertion:com.jraska.module.graph.assertion.gradle.plugin", version.ref = "moduleGraphAssert" }
aboutlibraries-gradlePlugin = { module = "com.mikepenz.aboutlibraries.plugin:com.mikepenz.aboutlibraries.plugin.gradle.plugin", version.ref = "aboutlibraries" }
chaquopy-gradlePlugin = { module = "com.chaquo.python:gradle", version.ref = "chaquopy" }   # same classloader as AGP (catalog rule 3)

[bundles]
common-test = ["kotlin-test", "turbine", "kotlinx-coroutines-test"]
jvm-test = ["junit4", "truth", "testParameterInjector"]
cmp-core = ["cmp-runtime", "cmp-foundation", "cmp-ui", "cmp-animation", "cmp-material3", "cmp-resources", "cmp-ui-tooling-preview"]
server-ktor = ["ktor-server-core", "ktor-server-cio", "ktor-server-content-negotiation", "ktor-serialization-kotlinx-json", "ktor-server-sse",
               "ktor-server-auth", "ktor-server-sessions", "ktor-server-rate-limit", "ktor-server-csrf", "ktor-server-compression",
               "ktor-server-call-id", "ktor-server-status-pages", "ktor-server-default-headers", "ktor-server-html-builder"]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
android-test = { id = "com.android.test", version.ref = "agp" }
android-lint = { id = "com.android.lint", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
metro = { id = "dev.zacsweers.metro", version.ref = "metro" }
room3 = { id = "androidx.room3", version.ref = "room3" }
baselineprofile = { id = "androidx.baselineprofile", version.ref = "baselineprofile" }
detekt = { id = "dev.detekt", version.ref = "detekt" }            # Unverified: 2.0 plugin id (1.x was io.gitlab.arturbosch.detekt)
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
# com.chaquo.python has no entry: it is on the build-logic classpath and applied by bare id (catalog rule 3)
# convention plugins (no version: provided by the included build)
neutrodyne-android-application = { id = "neutrodyne.android.application" }
neutrodyne-android-library = { id = "neutrodyne.android.library" }
neutrodyne-android-testing = { id = "neutrodyne.android.testing" }
neutrodyne-android-lint = { id = "neutrodyne.android.lint" }
neutrodyne-kmp-library = { id = "neutrodyne.kmp.library" }
neutrodyne-kmp-compose = { id = "neutrodyne.kmp.compose" }
neutrodyne-kmp-feature = { id = "neutrodyne.kmp.feature" }
neutrodyne-jvm-island = { id = "neutrodyne.jvm.island" }
neutrodyne-desktop-library = { id = "neutrodyne.desktop.library" }
neutrodyne-desktop-native = { id = "neutrodyne.desktop.native" }
neutrodyne-desktop-application = { id = "neutrodyne.desktop.application" }
neutrodyne-server-application = { id = "neutrodyne.server.application" }
neutrodyne-metro = { id = "neutrodyne.metro" }
neutrodyne-room = { id = "neutrodyne.room" }
neutrodyne-quality = { id = "neutrodyne.quality" }
```

Rules for the catalog:

1. Every external coordinate lives here; build files never hard-code a version. `resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() }` is applied by every convention plugin. Release candidates are allowed only where this table names them (`cmpMaterial3Adaptive`); alphas and betas never ([PO-4](../PLAN.md#po-4-material-3-expressive)).
2. Module build files apply plugins only by `alias(libs.plugins.neutrodyne-*)` or by bare `id("…")` for plugins already on the build-logic classpath; versioned `alias(...)` is used only for tooling plugins not on that classpath (`detekt`, `roborazzi`, `android-test`).
3. The Chaquopy plugin (`chaquopy-gradlePlugin`) is an `implementation` dependency of `build-logic/convention`, so it loads in the same classloader as AGP, and `:youtube:ytdlp` applies it by bare `id("com.chaquo.python")`. S7 recorded (2026-10-06): this form works — the plugin never needed a versioned `plugins {}` entry. No other module may apply it ([common Android configuration](#common-android-configuration)). Its runtime artifacts (`com.chaquo.python.runtime:*`) resolve from the `third_party/chaquopy-maven` repository of S7's self-built fallback, which alone serves them and the plugin; the CPython `com.chaquo.python:target` zips come unchanged from Maven Central (they are not Maven artifacts on the runtime classpath — S7 measured `releaseRuntimeClasspath` empty of them; detached configurations package them straight into the variant).
4. BOM-managed artifacts (OkHttp, Ktor, Coil; Android-only Compose) are declared without a version, so their platform must be on the same configuration. The convention plugins add `platform(okhttp-bom)`, `platform(ktor-bom)` and `platform(coil-bom)` to every configuration that declares one of their artifacts (in KMP modules through the source set's `dependencies { implementation(project.dependencies.platform(...)) }`; S8, 2026-10-06: platforms in KMP source-set dependency blocks work on both targets — `:core:network` compiles this way), and `neutrodyne.android.application` adds `platform(compose-bom)` to `:app`'s `implementation`, `debugImplementation` and `androidTestImplementation`. A module that exposes a BOM-managed artifact as `api` (`:core:network:okhttp` → `okhttp`, `:core:network` → `ktor-client-core`) also declares the platform as `api`.
5. Plugin classes in `build-logic` have no type-safe `libs` accessor. They read the catalog with `val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")` and `libs.findLibrary("okhttp-bom").get()` / `libs.findVersion("kotlin").get().requiredVersion`. Module build scripts use the type-safe accessors.
6. JetBrains' multiplatform builds of AndroidX libraries (`org.jetbrains.androidx.*`, `org.jetbrains.compose.material3*`) and the androidx coordinates they resolve to on Android are upgraded together in one Renovate group with Kotlin, KSP, Metro and Compose Multiplatform ([09 Dependency updates](09-quality-and-release.md#dependency-updates)); a bump that changes the Android resolution of `material3`, `adaptive` or `navigation3-ui` is reviewed against [D6](../PLAN.md#3-key-decisions) and [D7](../PLAN.md#3-key-decisions).

### `settings.gradle.kts`, `gradle.properties`, root build

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()   // Compose Multiplatform, org.jetbrains.androidx.*, Metro, Ktor, kotlinx, Skiko: all on Maven Central
        // S7 fell back to a self-built Chaquopy master (2026-10-06): a local repository that alone serves com.chaquo.python
        exclusiveContent { forRepository { maven(uri("third_party/chaquopy-maven")) }; filter { includeGroupByRegex("com\\.chaquo\\.python.*") } }
    }
    // S7 measured: the same exclusiveContent block is also needed in this file's pluginManagement{}
    // (the convention classpath resolves with pluginManagement repositories, not these) and in
    // build-logic/settings.gradle.kts.
}
rootProject.name = "Neutrodyne"
include(":app", ":desktopApp")
include(":core:model", ":core:common", ":core:domain", ":core:navigation", ":core:database", ":core:datastore",
        ":core:network", ":core:network:okhttp", ":core:data", ":core:artwork", ":core:designsystem", ":core:ui", ":core:testing")
include(":feeds", ":feeds:jvm")
include(":playback:api", ":playback:core", ":playback:impl", ":playback:engine", ":playback:native", ":playback:desktop")
include(":desktop:system")
include(":download:api", ":download:impl")
include(":youtube:api", ":youtube:impl", ":youtube:engine", ":youtube:ytdlp", ":youtube:ytdlp-desktop")
include(":sync:protocol", ":sync:api", ":sync:impl", ":sync:server")
// no :update:* modules: the update check lives in :core:domain, :core:model and :core:data (D13, D78)
include(":feature:feeds", ":feature:library", ":feature:groups", ":feature:podcast", ":feature:episode", ":feature:player",
        ":feature:queue", ":feature:downloads", ":feature:discover", ":feature:importexport", ":feature:settings", ":feature:sync")
// M6b: include(":benchmark")   v1.x: include(":feature:widgets")
```

The google repository's content filter is matched against the whole group name, so `org.jetbrains.androidx.*` (JetBrains' multiplatform AndroidX builds) resolves from Maven Central only. `:youtube:ytdlp` and `:youtube:ytdlp-desktop` stay included in the emergency build without the engine; only the shells' dependencies on them are dropped ([Emergency build without the engine](#emergency-build-without-the-engine)), so CI keeps compiling and testing them. Every repository serves immutable releases; the same repositories, minus the commented fallback, are declared for `pluginManagement` above and for `build-logic`.

**Gradle toolchains.** Gradle runs on Temurin 21. The desktop and server convention plugins request `JavaLanguageVersion.of(25)` and `.of(21)` from Gradle's toolchain support for compilation, tests and packaging; auto-provisioning is off (`org.gradle.java.installations.auto-download=false`), so a missing JDK fails the build instead of downloading one from a resolver service. CI installs Temurin 21 and 25 with `actions/setup-java` (09); developers install both locally. The JDK that jpackage bundles is not the toolchain: it is the pinned Temurin archive of `desktopApp/runtime.lock`, unpacked and checksum-checked by `:desktopApp`'s packaging tasks ([11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)), so the bundled runtime matches the attached source tarball exactly (risk L5).

```properties
# gradle.properties (committed; never contains secrets)
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.java.installations.auto-download=false
kotlin.code.style=official
kotlin.daemon.jvmargs=-Xmx3g
# AGP 9 defaults we rely on; do NOT opt out:
#   android.builtInKotlin=true, android.newDsl=true, android.uniquePackageNames=true,
#   android.r8.optimizedResourceShrinking=true, android.r8.strictFullModeForKeepRules=true,
#   android.onlyEnableUnitTestForTheTestedBuildType=true, android.defaults.buildfeatures.resValues=false
# Version, single source of truth for every product (scheme D63, procedure in 09; no pre-release suffixes since PO-33, S = 95):
neutrodyne.versionName=0.1.0
neutrodyne.versionCode=10095
neutrodyne.repoUrl=https://github.com/OWNER/Neutrodyne
# Approved YouTube-engine manifest on GitHub Pages (D76; 04 Engine updates):
neutrodyne.engineManifestUrl=https://OWNER.github.io/Neutrodyne/engine/ytdlp-approved.json
# false = emergency builds without the YouTube engine, APKs and desktop images (Build variants and ABIs; risk L1):
neutrodyne.youtubeEngine=true
# Committed so every build of a tag reads the same value (empty until PO-10 names the mailbox):
neutrodyne.acraMailto=
# Read with providers.gradleProperty(...).orElse(""); supplied via -P only by release.yml to the assembleRelease run and the
# desktop packaging jobs that build a published release, and only after Podcast Index has granted written permission
# (PO-3, D26); never committed:
#   neutrodyne.podcastIndexKey, neutrodyne.podcastIndexSecret
```

`OWNER` is replaced when PO-18 names the GitHub owner (M0a blocker only for the About link, the update and engine-manifest URLs and the image name `ghcr.io/{owner}/neutrodyne-server`, not for the build); `BuildInfo` derives the update-manifest URL from `neutrodyne.repoUrl` ([Build variants and ABIs](#build-variants-and-abis)). `:desktopApp` and `:sync:server` read the same version properties (`packageVersion = versionName` for jpackage, 11; the server's `serverVersion`, 10). Gradle logic never reads git, never embeds timestamps (the nightly reproducibility report, [09 Reproducible builds](09-quality-and-release.md#reproducible-builds)), and never reads environment variables: the only signing input is the committed keystore ([Signing config](#signing-config), [09 Committed keystore](09-quality-and-release.md#committed-keystore)). The former `neutrodyne.devTools` property no longer exists; developer tooling keys to the `debug` build type ([Debug build type](#debug-build-type)).

Root `build.gradle.kts` contains only `plugins { alias(libs.plugins.neutrodyne.quality); alias(libs.plugins.detekt) apply false }`.

**Gradle dependency verification (decision):** not enabled. Google Maven, Maven Central and the Gradle Plugin Portal serve immutable releases, and full verification would make every Renovate PR fail until someone regenerates metadata locally. Everything outside Gradle's resolution is pinned by SHA-256 in a reviewed lockfile and checked when it is fetched: the vendored yt-dlp by [`verifyBundledYtDlp`](#python-and-native-components) against yt-dlp's signed checksums; python-build-standalone by `fetchPythonStandalone`; FFmpeg's source tarball, miniaudio and the C++/WinRT headers by `buildFfmpeg`/`buildNdmedia` against `native-components.lock`; the Temurin archives and source tarball against `desktopApp/runtime.lock`; a self-built Chaquopy (S7 fallback) by commit, its local repository reviewed like source.

### Convention plugins

`build-logic/settings.gradle.kts` declares its own repositories (`dependencyResolutionManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`, with the same `google { content { … } }` filter as the root) and reuses the root catalog (`versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`). `build-logic/convention/build.gradle.kts` applies `kotlin-dsl` and declares as **`implementation`** (not `compileOnly`, see [S1](#s1-kgp-2420-under-agp-941)): `android-gradlePlugin`, `kotlin-gradlePlugin`, `kotlin-composeGradlePlugin`, `kotlin-serializationGradlePlugin`, `compose-gradlePlugin`, `ksp-gradlePlugin`, `metro-gradlePlugin`, `room3-gradlePlugin`, `baselineprofile-gradlePlugin`, `spotless-gradlePlugin`, `licensee-gradlePlugin`, `moduleGraphAssert-gradlePlugin`, `aboutlibraries-gradlePlugin`, `chaquopy-gradlePlugin` (catalog rule 3). Plugin classes live in `build-logic/convention/src/main/kotlin/` and are registered under the canonical IDs.

| Plugin ID | Applied to | Configures |
|---|---|---|
| `neutrodyne.android.application` | `:app` | `com.android.application`; [common Android config](#common-android-configuration); `applicationId`, version from `gradle.properties`; the build types `release` (published) and `debug` (local, `.debug`), the `neutrodynePublic` signing config on every build type including the ones the baseline-profile plugin creates, ABI splits, the engine switch ([Build variants and ABIs](#build-variants-and-abis)); `androidx.baselineprofile` as consumer ([Release build and baseline profiles](#release-build-and-baseline-profiles)); Compose for `MainActivity` (`org.jetbrains.kotlin.plugin.compose`, `buildFeatures.compose = true`, `platform(compose-bom)`); `androidResources.generateLocaleConfig = true`; `dependenciesInfo { includeInApk = false; includeInBundle = false }` (no Google-encrypted dependency block in a GitHub APK); applies `app.cash.licensee`, `com.jraska.module.graph.assertion`, `com.mikepenz.aboutlibraries.plugin`, `neutrodyne.metro`; registers [`verifyDependencyPolicy`, `verifyManifestPermissions`](#gradle-side-policy-tasks); applies `neutrodyne.android.lint` and `neutrodyne.android.testing` |
| `neutrodyne.android.library` | the Android-only libraries `:playback:impl`, `:youtube:ytdlp` | `com.android.library`; common Android config; `namespace` derived from the path; `consumerProguardFiles("consumer-rules.pro")` when present; no `buildFeatures` lines (AGP 9 already defaults `buildConfig`, `aidl`, `resValues` and `shaders` to off; only `:app` turns `buildConfig` on, and `:youtube:ytdlp`'s own build file turns `aidl` on for `IYtxEngine`); no Compose (neither module renders UI in v1.0); applies `neutrodyne.metro`, `neutrodyne.android.lint` and `neutrodyne.android.testing` |
| `neutrodyne.kmp.library` | every shared KMP module (contracts, shared models, infrastructure, shared implementations, `:core:testing`) | `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library`; `kotlin { android { namespace = <path namespace>; compileSdk = 37; minSdk = 26 }; jvm("desktop"); applyDefaultHierarchyTemplate() }`; `compilerOptions.jvmTarget = 17` on both targets; Android host tests only where a module has `androidMain` code to test (`withHostTestBuilder {}`; Robolectric), device tests never in library modules (they run in `:app`); consumer keep rules published from `src/androidMain/consumer-rules.pro` when present (Unverified DSL name, S19); `commonTest` gets the `common-test` bundle and `project(":core:testing")` (except in `:core:testing` and the modules it depends on), `desktopTest` the `jvm-test` bundle; calls 09's `configureNeutrodyneTestTasks()`; applies `com.android.lint` through `neutrodyne.android.lint` (Unverified that Lint analyses the KMP Android target's sources, S8) |
| `neutrodyne.kmp.compose` | `:core:designsystem`, `:core:ui`, every feature | `neutrodyne.kmp.library` + `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.compose`; the `cmp-core` bundle in `commonMain`; `compose.resources { packageOfResClass = "<namespace>.resources"; publicResClass = (path == ":core:ui"); generateResClass = always }` and `android { androidResources { enable = true } }` (Compose resources on the Android-KMP target need it, [resources setup](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html)); `composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf")); if (-PcomposeReports) reportsDestination/metricsDestination = build/compose }`. Adds `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api` **only** when the project path is `:core:designsystem`; `Nd*` wrappers therefore must not expose experimental Material 3 types in their public signatures ([08 Theming and colour](08-ui-ux.md#theming-and-colour)). Never adds a per-OS desktop runtime (only `:desktopApp` does) |
| `neutrodyne.kmp.feature` | `:feature:*` | `neutrodyne.kmp.compose` + `neutrodyne.metro`; adds to `commonMain`: `:core:{domain, model, common, designsystem, ui, navigation}`, the three JetBrains lifecycle artifacts, `metrox-viewmodel-compose`, `navigation3-runtime`, `navigation3-ui-jb`, `cmp-material3-adaptive-navigation3`, `androidx-paging-compose`, `kotlinx-collections-immutable`. `:*:api` modules are added explicitly per feature |
| `neutrodyne.jvm.island` | `:feeds:jvm`, `:core:network:okhttp`, `:youtube:engine` | `org.jetbrains.kotlin.jvm`; no toolchain: Kotlin `jvmTarget = 17` plus `-Xjdk-release=17`, `JavaCompile.options.release = 17`, because Android consumes the JAR; `neutrodyne.metro`; `com.android.lint`; JUnit 4 test dependencies and `:core:testing`'s JVM variant; calls 09's `configureNeutrodyneTestTasks()` ([Source sets and JVM islands](#source-sets-and-jvm-islands)) |
| `neutrodyne.desktop.library` | `:playback:engine`, `:playback:native`, `:playback:desktop`, `:desktop:system`, `:youtube:ytdlp-desktop` | `org.jetbrains.kotlin.jvm` on the JDK 25 toolchain, `jvmTarget` 25 (fallback 21, [D4](../PLAN.md#3-key-decisions)); `neutrodyne.metro`; tests run with `--enable-native-access=ALL-UNNAMED` |
| `neutrodyne.desktop.native` | `:playback:native` | The per-host native builds: `buildNdmedia` (CMake: miniaudio, ring buffer, OS shims) and `buildFfmpeg` (`playback/native/ffmpeg/build.sh`), `assembleFfmpegSource` (the release's FFmpeg source bundle) and `checkNativeLicences`; outputs land in the Compose resources layout `appResourcesRootDir` (`common/`, `<os>/`, `<os>-<arch>/`) that `:desktopApp` packages; never cross-compiles (one runner per target). Content and configure lines: [11 Desktop playback engine](11-desktop.md#desktop-playback-engine) |
| `neutrodyne.desktop.application` | `:desktopApp` | `org.jetbrains.kotlin.jvm` on the JDK 25 toolchain, `org.jetbrains.compose` (application), the Compose compiler plugin, `neutrodyne.metro`; `compose.desktop.application { mainClass = "ch.lkmc.neutrodyne.desktop.MainKt"; jvmArgs += "--enable-native-access=ALL-UNNAMED"; nativeDistributions { … } }` with the formats, jlink modules, identifiers and resources of [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception); **never the ProGuard `*Release*` tasks**: the plugin disables every Compose desktop task whose name contains `Release` and `verifyDependencyPolicy` fails if `com.guardsquare` resolves ([D3](../PLAN.md#3-key-decisions)); `trainAotCache` (PO-42), the python-build-standalone bundle from `:youtube:ytdlp-desktop`, the native resources from `:playback:native`; applies `app.cash.licensee` (`licensee`), `com.jraska.module.graph.assertion`, `com.mikepenz.aboutlibraries.plugin`; registers `verifyDependencyPolicy` |
| `neutrodyne.server.application` | `:sync:server` | `org.jetbrains.kotlin.jvm` on the JDK 21 toolchain, `jvmTarget` 21; `application` (`mainClass = ch.lkmc.neutrodyne.sync.server.MainKt`); `fatJar` (one JAR from `runtimeClasspath` with merged `META-INF/services`, `Main-Class` and the licence files of every dependency kept, named `neutrodyne-server-{v}.jar`); applies `app.cash.licensee` (`licensee`), `com.jraska.module.graph.assertion`; registers `verifyDependencyPolicy` (which also bans logback, [D94](../PLAN.md#3-key-decisions)); no Metro (plain constructor wiring, [D82](../PLAN.md#3-key-decisions)) |
| `neutrodyne.metro` | every module that declares or injects bindings | `dev.zacsweers.metro` with `metro { generateContributionProviders.set(true) }` so `internal` classes contribute across modules (S8; [Dependency injection](#dependency-injection)) |
| `neutrodyne.room` | `:core:database` | `androidx.room3` + KSP with the compiler on each target (`add("kspAndroid", room3-compiler)`, `add("kspDesktop", room3-compiler)`); `room3 { schemaDirectory("$projectDir/schemas") }` (→ `core/database/schemas/`; Unverified extension name — Room 2's is `room { }`; S2 confirms); `commonMain`: `api(room3-runtime)`, `api(room3-paging)`, `api(paging-common)`, `implementation(sqlite-bundled)`; `desktopTest`: `room3-testing` (DAO and migration tests on the JVM with the bundled driver, [D9](../PLAN.md#3-key-decisions)); Android host tests: `room3-testing`, `sqlite-framework`. Room KMP needs `@ConstructedBy` with an `expect object NeutrodyneDatabaseConstructor` ([Room KMP](https://developer.android.com/kotlin/multiplatform/room)). Room usage conventions: [02 Conventions](02-data-model.md#conventions) |
| `neutrodyne.android.testing` | applied by `neutrodyne.android.application` and `neutrodyne.android.library` | Hook only; content owned by [09 Test infrastructure](09-quality-and-release.md#test-infrastructure) (Robolectric `sdk=36`, JDK 21, `de_DE` + `America/St_Johns`, golden switch, `okhttp-bom` and Robolectric forcing, orchestrator, GMD definitions). Adds `testImplementation(project(":core:testing"))` |
| `neutrodyne.android.lint` | Android modules, KMP modules (Android target), JVM islands (via `com.android.lint`) | Gates owned by [09 Static analysis](09-quality-and-release.md#static-analysis) (`warningsAsErrors`, baseline, SARIF, `checkDependencies` in `:app`). One foundation rule: Media3's `@UnstableApi` is an AndroidX `RequiresOptIn` marker enforced by Lint (`UnsafeOptInUsageError`), not by the Kotlin compiler, so the module-wide opt-in is a lint config: when the project path is `:playback:impl`, `lint { lintConfig = file("lint.xml") }` with `<issue id="UnsafeOptInUsageError"><ignore regexp='\(markerClass = androidx\.media3\.common\.util\.UnstableApi\.class\)' /></issue>` ([UnstableApi](https://developer.android.com/reference/androidx/media3/common/util/UnstableApi)). Everywhere else an unstable Media3 call stays a lint error |
| `neutrodyne.quality` | root only | Spotless (ktlint 1.8.0 + compose-rules 0.6.7 for `**/*.kt` and `**/*.kts`; settings in `.editorconfig`, owned by 09); registers [`checkSpdxHeaders`, `checkBannedApis`](#gradle-side-policy-tasks), `generateBrandAssets` and `checkBrandAssets` ([brand assets](#brand-asset-generator)) and wires the checks into `check` |

Removed by the scope revision: `neutrodyne.hilt`, `neutrodyne.jvm.library` (contracts are common-only KMP modules now), `neutrodyne.android.feature` and `neutrodyne.android.compose` (shared UI is Compose Multiplatform; `:app`'s Compose settings live in `neutrodyne.android.application`).

#### Common Android configuration

Applied by `neutrodyne.android.application` and `neutrodyne.android.library` (sketch; AGP 9.4 `CommonExtension` is non-generic). `neutrodyne.kmp.library` sets the same SDK levels and `jvmTarget` in the KMP `android {}` target block and installs the same plugin guards.

```kotlin
internal fun Project.configureAndroidCommon(ext: CommonExtension) {
    ext.compileSdk = 37                          // AGP 9.4 also offers a compileSdk {} block; either, never compileSdkVersion()
    ext.buildToolsVersion = "36.0.0"
    ext.defaultConfig.minSdk = 26
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")   // never META-INF/LICENSE* or NOTICE*
    extensions.configure<KotlinAndroidProjectExtension> {                     // Unverified type name under built-in Kotlin (S1)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17); allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").isPresent) }
    }
    installPluginGuards()
    afterEvaluate { check(!ext.compileOptions.isCoreLibraryDesugaringEnabled) { "core-library desugaring is not used (D3)" } }
    configurations.configureEach { resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() } }
}
internal fun Project.installPluginGuards() {                                  // also called by kmp.library, jvm.island, desktop.*
    pluginManager.withPlugin("org.jetbrains.kotlin.android") { error("kotlin-android is banned: AGP 9 built-in Kotlin") }
    pluginManager.withPlugin("org.jetbrains.kotlin.kapt") { error("kapt is banned: use KSP") }
    pluginManager.withPlugin("com.google.dagger.hilt.android") { error("Hilt is removed: Metro (D82)") }
    pluginManager.withPlugin("com.chaquo.python") {                           // Chaquopy allows one module per app (D72)
        check(path == ":youtube:ytdlp") { "com.chaquo.python is allowed only in :youtube:ytdlp" }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {         // AGP 9: KMP never with com.android.library/application
        check(!pluginManager.hasPlugin("com.android.library") && !pluginManager.hasPlugin("com.android.application")) {
            "use com.android.kotlin.multiplatform.library for shared modules (D81)" }
    }
}
// targetSdk is set explicitly in the application plugin (AGP 9 defaults it to compileSdk if unset):
//   defaultConfig.targetSdk = 37
// namespace / package: "ch.lkmc.neutrodyne" + path with ':' -> '.' and '-' removed
//   (":core:model" -> "ch.lkmc.neutrodyne.core.model", ":youtube:ytdlp-desktop" -> "ch.lkmc.neutrodyne.youtube.ytdlpdesktop")
```

`compose-stability.conf` (repo root) lists `ch.lkmc.neutrodyne.core.model.**`, `kotlinx.collections.immutable.*`, `kotlin.time.Duration`, `kotlin.time.Instant`. It is valid only because `:core:model` types are deeply immutable by rule ([Architecture patterns](#model-and-state-rules)).

#### Gradle-side policy tasks

These run in `check`; CI (09) only invokes Gradle.

| Task | Project | Fails when |
|---|---|---|
| `assertModuleGraph` | `:app`, `:desktopApp`, `:sync:server`, `:core:testing` (module-graph-assertion plugin) | any edge violates [Dependency rules](#dependency-rules) |
| `licenseeRelease` | `:app` | a runtime dependency of the published `release` variant has a licence that is not allowed ([allow-list](#licensee-allow-list)); `benchmarkRelease` adds no runtime dependency of its own |
| `licensee` | `:desktopApp`, `:sync:server` | a dependency on `runtimeClasspath` has a licence that is not allowed |
| `verifyDependencyPolicy` | registered in every project by its convention plugin; aggregated by `check` | **declared** dependencies of any configuration name a banned coordinate ([list above](#version-table)) — Google Play services, Firebase, Play Core, Crashlytics, Sentry, Dagger/Hilt, ProGuard, jextract, logback included; in the shells the **resolved** runtime classpaths (`:app` `releaseRuntimeClasspath` and `benchmarkReleaseRuntimeClasspath`, `:desktopApp` and `:sync:server` `runtimeClasspath`) contain a banned artifact, or any artifact whose Licensee SPDX is GPL, AGPL, LGPL or MPL — LGPL and MPL components are allowed only outside Gradle (native libraries and data files with lockfile entries, [D3](../PLAN.md#3-key-decisions)); the one Gradle exception is JNA's dual licence, accepted only as its Apache-2.0 alternative; `releaseRuntimeClasspath` contains LeakCanary (`com.squareup.leakcanary:*`), `androidx.compose.ui:ui-tooling` (the `-preview` artifact is allowed) or `androidx.compose.ui:ui-test-manifest`; the Compose plugin's ProGuard configuration of `:desktopApp` resolves; any `*AndroidTestRuntimeClasspath` contains `io.mockk` (09: no MockK on devices) |
| `verifyManifestPermissions` | `:app` | the merged manifest of the published `release` variant (`SingleArtifact.MERGED_MANIFEST`) declares a `uses-permission` or `uses-permission-sdk-23` not listed in `app/policy/permissions.txt` or lacks one listed there (both declaration forms apply on every supported device: minSdk 26 ≥ 23, [uses-permission-sdk-23](https://developer.android.com/guide/topics/manifest/uses-permission-sdk-23-element); recorded 2026-10-06 — the first implementation ignored the SDK-qualified form); sets `android:debuggable="true"` or `android:testOnly`; or declares an activity from a test or tooling artifact (ui-test-manifest's `ComponentActivity`, `ui-tooling`'s `PreviewActivity`). The `debug` variant is not checked (its `.debug` application ID and LeakCanary's components are not the published set) |
| `checkSpdxHeaders` | root | any `*.kt`/`*.java`/`*.kts`/`*.py`/`*.aidl`/`*.c`/`*.cpp`/`*.h`/`*.m`/`*.mm` file of ours contains an `SPDX-License-Identifier` naming GPL, LGPL, AGPL or MPL (any version or suffix); a file listed as copied or ported in `THIRD_PARTY_NOTICES.md` lacks its original `SPDX-License-Identifier` line or credit header ([contribution rule](#copied-code-and-contributions)). Vendored third-party trees with their own lock entry (miniaudio, the FFmpeg source tarball, C++/WinRT headers, yt-dlp) are excluded by path; Markdown and other docs are not scanned |
| `checkBannedApis` | root | a source-set-aware text scan ([rules below](#checkbannedapis-rules)) |
| `checkBrandAssets` | root | the committed brand outputs differ from what `generateBrandAssets` produces from `media-sources/` ([brand-asset generator](#brand-asset-generator)) |
| `:youtube:ytdlp:checkPythonLicences`, `:youtube:ytdlp:verifyBundledYtDlp` | `:youtube:ytdlp` | the Android Python component lockfile or the vendored yt-dlp fails its checks ([Python and native components](#python-and-native-components)) |
| `:youtube:ytdlp-desktop:checkPythonLicences`, `:youtube:ytdlp-desktop:verifyBundledYtDlp` | `:youtube:ytdlp-desktop` | the desktop Python lockfile (python-build-standalone components) or the vendored yt-dlp fails its checks (from MD3; the lock exists from M0a with an empty component list) |
| `:playback:native:checkNativeLicences` | `:playback:native` | `native-components.lock` fails its checks ([Python and native components](#python-and-native-components)); from MD0 |

##### `checkBannedApis` rules

A plain text scan (fast, configuration-cache safe) of the Kotlin, Java, C/C++/Objective-C sources and Android manifests of every root-build module — every source set (`commonMain`, `androidMain`, `desktopMain`, `main`, and `:app`'s `debug`, `release`, `benchmarkRelease`, `youtubeEngine`, `noYouTubeEngine` directories, `:desktopApp`'s `youtubeEngine` and `noYouTubeEngine` directories) — plus the module build scripts (not `build-logic/`; tests may build their own clients). False positives are fixed by rewording, never by suppression lists. It fails when:

1. **`commonMain`** of any module contains an import or fully qualified use of `java.`, `javax.` or `android.` (not `androidx.`, whose KMP libraries are allowed), or `System.currentTimeMillis` (the compiler already rejects most of these; the scan also covers `expect` declarations and string templates, and keeps [D81](../PLAN.md#3-key-decisions)'s iOS door open).
2. A **build script** adds a JVM island (`:feeds:jvm`, `:core:network:okhttp`, `:youtube:engine`) to a `commonMain` dependency block, or a platform-only module to any KMP module (the module-graph plugin cannot see source sets, [Module-graph assertion configuration](#module-graph-assertion-configuration)). Enforced twice (2026-10-06): `verifyDependencyPolicy` checks the declared project edges of every `commonMain*` configuration for islands and every configuration of a KMP module for platform-only modules — it sees an edge whichever DSL form declared it (`commonMain.dependencies { }`, `commonMain { dependencies { } }`, `by getting`, `getByName`/`named`, `.apply`/`.also`) — and this text scan stays as the second line of defence for the same forms in build scripts.
3. Source outside `core/designsystem/` contains `ExperimentalMaterial3Api` or `ExperimentalMaterial3ExpressiveApi`; source outside `playback/impl/` contains `UnstableApi`.
4. Source outside `youtube/ytdlp/` contains `com.chaquo.python`, or a manifest outside `youtube/ytdlp/` declares `android:process`.
5. Source outside `youtube/ytdlp-desktop/` contains `ProcessBuilder(` or `Runtime.getRuntime().exec` (the only process any app starts is the desktop engine child, [D90](../PLAN.md#3-key-decisions); Android's engine runs in-process, [Platform compliance](#platform-compliance) P33).
6. `java.awt.Desktop` appears outside `desktopApp/`, `desktop/system/` and `core/ui/src/desktopMain/`; `java.lang.foreign` appears outside `playback/native/`, `desktop/system/` and `playback/engine/` (`:playback:engine` binds FFmpeg only; our own native code is called only from the first two, PLAN 5.1 rule 6).
7. Any source contains `System.load(` or `System.loadLibrary(` (native code is loaded through FFM `SymbolLookup.libraryLookup` from the app image on the desktop and by the libraries themselves elsewhere; Android P35), `DexClassLoader`, `InMemoryDexClassLoader`, `PackageInstaller` (the apps install nothing, [D78](../PLAN.md#3-key-decisions)), `api.github.com`, `GlobalScope`, `override fun onBackPressed`, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; any manifest declares `REQUEST_INSTALL_PACKAGES` or `UPDATE_PACKAGES_WITHOUT_USER_ACTION` or sets `android:debuggable`.
8. Source contains `okhttp3.Cache(`, `.cache(Cache(`, `OkHttpClient()` or `OkHttpClient.Builder()` outside `core/network/okhttp/`, or a Ktor `HttpClient(` outside `core/network/` ([Networking baseline](#one-client-family)); `sync/server/` is exempt from the Ktor rule (server code).
9. A module other than `:app` has a `src/debug/` directory, or a module build script other than `:app`'s declares `debugImplementation` or `debugApi` (KMP library modules have no build types; developer tooling lives in `:app`'s `debug`, [Debug build type](#debug-build-type)).
10. `collectAsState()` appears in `feature/`; a `Text("` string literal appears in `feature/**/src/commonMain` or `core/ui/src/commonMain` (hard-coded UI text; Android Lint's `HardcodedText` does not see Compose resources, [09 String conventions](09-quality-and-release.md#string-conventions)).

The exec, FFM and `System.load` rules keep native and process boundaries reviewable ([Platform compliance](#platform-compliance) P33–P35 and the desktop list). `verifyDependencyPolicy` and `verifyManifestPermissions` must stay configuration-cache safe: they take their inputs as providers (`configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }`, the Licensee JSON report, the `release` variant's `SingleArtifact.MERGED_MANIFEST` from `androidComponents.onVariants`, and — for rule 2 — a `MapProperty<String, List<String>>` of configuration name → declared `ProjectDependency` paths captured in `afterEvaluate`, never by resolving configurations at configuration time). `:youtube:ytdlp:shimTest` and `:youtube:ytdlp-desktop:shimTestStdio` (host `python -m pytest`) are deliberately not part of `check`: they need a host CPython of the target minor version, which CI's `unit` job provides ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)).

##### Brand-asset generator

[D97](../PLAN.md#3-key-decisions); 08 owns the brand rules, sizes and safe zones ([08 Brand assets](08-ui-ux.md#brand-assets)), this document owns the build side. `generateBrandAssets` (root, `neutrodyne.quality`, JDK `javax.imageio` and `java.awt` only — no ImageMagick, GIMP or Inkscape, no Gradle dependency) reads `media-sources/icon.png` (1254 × 1254 px) and the hand-drawn silhouette `media-sources/neutrodyne-mono.svg` and writes the committed outputs: `app/src/main/res/mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml`, `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` (the "N" extracted with soft alpha against the measured navy `#00192E` and scaled into the 66-dp safe zone), `drawable/ic_launcher_background.xml` (navy), `drawable/ic_launcher_monochrome.xml` and `drawable/ic_stat_neutrodyne.xml` (vector silhouette), the splash icon; `desktopApp/icons/neutrodyne.ico` (16–256 px, the sizes ≤ 32 px from the silhouette in amber `#F3881C` on navy), `neutrodyne.icns` (16–1024 px incl. @2x), `png/neutrodyne-{16,22,24,32,48,64,128,256,512}.png`, `tray/neutrodyne-template.png` (macOS menu-bar template) and `tray/neutrodyne-tray-{16,22,32}.png`. ICO and ICNS writers are our own (≈ 100 lines each; PNG-compressed entries). The SVG is read by a minimal path parser for the subset the silhouette uses (`M`, `L`, `C`, `Z`), so no SVG library is needed; Unverified that the silhouette needs nothing beyond that subset. `checkBrandAssets` runs the generator into `build/` and fails on any byte difference, so hand edits to generated files are impossible to keep. Both tasks are reproducible: fixed resampling (bicubic via `RenderingHints`), no timestamps in PNG metadata (`javax.imageio` writes none by default).

Implementation notes (2026-10-06, M0b step 31): the committed silhouette parses with exactly the `M`/`L`/`C`/`Z` subset, resolving the Unverified point above (any other command fails the build loudly). The splash icon is committed as `app/src/main/res/drawable/ic_splash_neutrodyne.xml`, a generated `<bitmap>` alias of `@mipmap/ic_launcher_foreground` — 08's "the foreground without an icon background" — so the splash ships no duplicate raster and `windowSplashScreenAnimatedIcon` references the alias. The in-app mark is `core/designsystem/src/commonMain/composeResources/drawable/brand_mark.png` (08's `brand_mark.png`). `generateBrandAssets --preview-dir=<dir>` additionally renders the M0b review previews into an uncommitted directory (the adaptive foreground over navy under circle and squircle masks at 432 px, the tinted monochrome icon, the notification icon at 96 px, a contact sheet of the desktop sizes; rerun with `--rerun` while previews exist). The generator lives in `build-logic/convention/src/main/kotlin/BrandAssets*.kt` with the two tasks registered by `neutrodyne.quality` and wired into the root `check`; its pure parts (the SVG parser, the ICO/ICNS writer headers, the soft-alpha maths, end-to-end byte reproducibility) are unit-tested in build-logic's own JUnit tests.

---

## Module layout

Serves N11, R8.1. Delivered in M0a (every module as a stub), content by milestone per the "Content from" column of the canonical module list ([D13](../PLAN.md#3-key-decisions), [PLAN 5.1](../PLAN.md#51-module-graph)).

Every module except `:benchmark` (M6b) and `:feature:widgets` (v1.x) is created in M0a as a compiling stub: `build.gradle.kts`, the package directory, one `internal` placeholder declaration and one placeholder test (in `commonTest` for KMP modules), compiled for every target it declares. Packages and AGP namespaces follow `ch.lkmc.neutrodyne` + path, with `:` → `.` and `-` dropped (`:youtube:ytdlp-desktop` → `ch.lkmc.neutrodyne.youtube.ytdlpdesktop`); exceptions: `:app` → `ch.lkmc.neutrodyne`, `:desktopApp` → `ch.lkmc.neutrodyne.desktop`. `:youtube:ytdlp`'s code that runs in the `:ytx` process lives in the subpackage `ch.lkmc.neutrodyne.youtube.ytdlp.ytx`. Directories follow the path (`:sync:server` → `sync/server/`, `:core:network:okhttp` → `core/network/okhttp/`).

Kinds: **KMP** = `neutrodyne.kmp.*` with the targets `android` and `jvm("desktop")`; **common only** = KMP with no `androidMain`/`desktopMain` code (tiny `actual`s excepted where the table says so); **island** = `neutrodyne.jvm.island`; **Android** = Android-only module; **JVM** = desktop-only or server module. Project dependencies in *italics* are declared only in `androidMain` and `desktopMain`.

| Module | Plugins | Source sets and main contents | Project dependencies (main) | External dependencies | Content from |
|---|---|---|---|---|---|
| `:app` | `neutrodyne.android.application` (incl. `.metro`, baseline-profile consumer) | Android: `NeutrodyneApplication` (`AndroidAppGraph` in the main process, `YtxGraph` in `:ytx`), `AndroidAppGraph`, `YouTubeBindingsModule`, `MainActivity`, `StartupViewModel`, `ExternalImportActivity`, the Android `IntentRouter` adapter, ACRA, Auto Backup rules, manifest, build types, signing, ABI splits, `res/values*/strings.xml` with `app_name` per locale, the network security config; `src/debug/` (LeakCanary wiring, `DebugToolsInitializer`, `DebugHttpLogInterceptor`, debug-only screens); `src/benchmarkRelease/` (09's `BenchmarkSeedReceiver`, M10) | every feature; every contract, UI-core, infrastructure and shared-implementation module; `:playback:impl`; `:youtube:ytdlp` (absent with `-Pneutrodyne.youtubeEngine=false`); the islands arrive transitively | appcompat, activity-compose, core-ktx, core-splashscreen, androidx `navigation3-ui` (pin), `lifecycle-process`, work-runtime, coil-compose, kotlinx-coroutines-android, acra-mail, acra-dialog, profileinstaller; `debugImplementation`: leakcanary-android, compose `ui-tooling`, `ui-test-manifest`; `baselineProfile(project(":benchmark"))` from M11b; no `coreLibraryDesugaring` | M0a |
| `:desktopApp` | `neutrodyne.desktop.application` | JVM 25: `MainKt`, `DesktopAppGraph`, `DesktopYouTubeBindingsModule`, `NeutrodyneWindow`, `DesktopMenuBar`, `SingleInstanceLock`, `InstanceHandshake`, `DesktopOpenHandler`, `DesktopCrashReporter`, `SmokeMode`, the `BuildInfo` loader, `nativeDistributions` configuration, AOT training ([11 Desktop shell](11-desktop.md#desktop-shell)) | every feature; every contract, UI-core, infrastructure and shared-implementation module; `:playback:desktop`, `:playback:engine`, `:playback:native`, `:desktop:system`; `:youtube:ytdlp-desktop` (absent with the switch); the islands arrive transitively | the per-OS Compose desktop runtime (`compose.desktop.currentOs`), kotlinx-coroutines-swing, coil-compose, jna, jna-platform; `runtimeOnly(kxml2)` | M0a stub, M0b shell (11) |
| `:sync:server` | `neutrodyne.server.application` | JVM 21: `MainKt`, `ServerCli`, `ServerModule`, routes, `SqliteSyncStore`, … ([10 Server architecture](10-sync.md#server-architecture)) | `:sync:protocol`, `:feeds` | `server-ktor` bundle, kotlinx-serialization-json, sqlite-jdbc, bcprov-jdk18on, slf4j-api, `runtimeOnly(slf4j-simple)`; test: ktor-server-test-host | M0a stub, M0b skeleton, MS1 (10) |
| `:core:model` | `neutrodyne.kmp.library`, `kotlin.plugin.serialization` | common only: deeply immutable models, `BuildInfo`, `NetError`, `IpFamily`, `ExternalReason`, `SettingsFile`, `SettingKey`, `DesktopOs`, `DesktopArch`, `InstallKind` | — | kotlinx-serialization-json, kotlinx-collections-immutable, kotlinx-datetime | M0a; M11a: the update check's state types (`UpdateCheckState`, `UpdateInfo`, `UpdateApk`, `UpdateDesktopAsset`, `UpdateServerInfo`, `UpdateDisabledReason`, `UpdateCheckError`, `UpdateNotice`; package `ch.lkmc.neutrodyne.core.model.update`, [09 Update check](09-quality-and-release.md#update-check)) |
| `:core:common` | `.kmp.library`, `.metro` | common: `AppScope`/`YtxScope` (the Metro scope annotations, S8 2026-10-06), `Clock`, dispatchers, `@ApplicationScope` (Metro qualifier), `Outcome`, `suspendRunCatching`, `Log`, `Redactor`, `AppInitializer`, `NetworkMonitor`, `PlatformInfo`, `UserAgentProvider` (2026-10-05), `CredentialLookup`, `Origin`, `HttpClientKind`, `LocalNetworkAccess`; `expect` `Nfc`, `DateFormatter`, `StoragePaths`; `androidMain`/`desktopMain`: their `actual`s; `desktopMain`: `AppDirs`, `JobLane` and the ports `JobLanePoker`, `PowerMonitor`/`PowerEvent`, `DesktopNotifier`/`DesktopNotification`/`NotificationKind`, `LinuxDesktopPortal` (11, 2026-10-05: implemented by `:core:data` and `:desktop:system`, bound in `DesktopAppGraph`, so shared modules never depend on `:desktop:system`) | — | kotlinx-coroutines-core, kotlinx-datetime | M0a |
| `:core:domain` | `.kmp.library`, `.metro` | common only: repository and use-case interfaces, `SettingsRepository`, `SecretStore` (M1b), `AppUpdateChecker`, `UpdateNotices` (M11a), sync ports `PlaybackSyncPort`, `PrePlaySync`, `SyncIngestHook` (MS2, 10), `LibraryMerger` with `MergePolicy` (M3/MS2, 05) and `GroupChannelSync` (M2, 05) | `api`: `:core:{model, common}`, `:playback:api`, `:download:api`, `:youtube:api` | `api(paging-common)`, kotlinx-coroutines-core | M0a |
| `:core:navigation` | `.kmp.library`, `kotlin.plugin.serialization` | common only: every `*Key`, `TopLevelKey`, `AppNavigator`, `LocalAppNavigator`, `NdSceneMetadata`, `NavKeySerializers`, `IntentRouter`, `RouteInput`, `Route` | — | `api(navigation3-runtime)`, `api(cmp-runtime)` (for `staticCompositionLocalOf`; no Compose compiler plugin), kotlinx-serialization-json | M0a |
| `:core:database` | `.kmp.library`, `.metro`, `.room` | common: entities, DAOs, `NeutrodyneDatabase` with `@ConstructedBy(NeutrodyneDatabaseConstructor)`, `DatabaseOpener`, `FeedQueryBuilder`, `TableRebuild`, `SyncTriggers`; `androidMain`: `AndroidDatabaseFactory`; `desktopMain`: `DesktopDatabaseFactory` (`<data>/neutrodyne.db`) ([02](02-data-model.md#conventions)) | `:core:{model, common}` | (from `neutrodyne.room`), kotlinx-serialization-json | M1a |
| `:core:datastore` | `.kmp.library`, `.metro` | common: `SettingsStore`, `DeviceSettingsStore`, the store factory; the file paths come from `StoragePaths` | `:core:{model, common}` | datastore-preferences-core, okio | M0a |
| `:core:network` | `.kmp.library`, `.metro` | common: `NeutrodyneHttpClients` (Ktor factory), `NetErrorClassifier`; `androidMain`: `ConnectivityNetworkMonitor`, `OkHttpNeutrodyneHttpClients`, `PlatformNetErrorClassifier`; `desktopMain`: `DesktopNetworkMonitor` and the same two adapters ([Networking baseline](#networking-baseline)) | `:core:{model, common}`; *`:core:network:okhttp`* | `api(ktor-client-core)`, ktor-client-content-negotiation, ktor-serialization-kotlinx-json; platform: ktor-client-okhttp | M0a |
| `:core:network:okhttp` | `neutrodyne.jvm.island` | JVM 17: `CoreClients` and `NetworkClients` (the OkHttp family incl. `SYNC`), `UserAgentInterceptor`, `AuthInterceptor`, `IdentityEncodingInterceptor`, `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor`, `DnsFamilyHints`, `FamilyHintDns`, `pinnedToFamily`, `JvmNetErrors` | `:core:{model, common}` | `api(okhttp)` via BOM, okhttp-coroutines, okio | M0a |
| `:core:data` | `.kmp.library`, `.metro`, `kotlin.plugin.serialization` | common: repositories, ingestion, `FeedRefresher`, `FetchStateBatcher`, `FeedFetcher` on Ktor, update-check logic incl. `DesktopAssetSelector`; `androidMain`: `RefreshWorker`, `ImportFetchWorker`, `AutoSnapshotWorker`, `RestoreWorker`, `UpdateCheckWorker`, `UpdateNotifier`, `KeystoreCredentialStore`, `YouTubeAlertActionReceiver`; `desktopMain`: `DesktopJobRunner`, `DesktopRefreshLane`, `DesktopUpdateCheckLane`, `DesktopUpdateNotifier`, `DesktopSecretStore`, `DesktopMaintenanceLane` | `:core:{domain, model, common, database, datastore, network, artwork}`, `:feeds`, `:youtube:api`; *`:feeds:jvm`* | kotlinx-serialization-json, kotlinx-datetime, okio; `androidMain`: work-runtime, lifecycle-process; `desktopMain`: jna, jna-platform (DPAPI) | M1a (M0a stub binds `CredentialLookup.None`); M11a update check |
| `:core:artwork` | `.kmp.library`, `.metro` | common: `ArtworkStore` on Okio, Coil components, `NeutrodyneImageLoaderFactory`; `androidMain`: `ArtworkProvider`, `ArtworkSyncWorker`; `desktopMain`: `DesktopArtworkLane` | `:core:{model, common, database, network}`, `:youtube:api`; *`:core:network:okhttp`* (Coil's OkHttp fetcher on the IMAGE client) | coil-core, okio; platform: coil-network-okhttp; `androidMain`: work-runtime; M10: material-color-utilities | M1a (Coil), M4 (store) |
| `:core:designsystem` | `.kmp.compose` | common: `NeutrodyneTheme`, `BrandColors`, `Nd*` wrappers, `NdIcons`; `androidMain`: dynamic colour `actual`; `desktopMain`: scrollbar styles | `:core:model` | `cmp-material3`, `cmp-material3-navigationSuite`, graphics-shapes, coil-compose, kotlinx-collections-immutable; M10: material-color-utilities | M0a (M0b brand scheme) |
| `:core:ui` | `.kmp.compose` (`publicResClass = true`) | common: the public `Res`, `UiText`, `UserMessage`, the shared navigation host (`NavigationState`, `NeutrodyneNavHost`, overlay scene strategies, `NeutrodyneRoot` layout, [Navigation](#appnavigator-and-per-tab-back-stacks)), `PlatformActions` interfaces, `DownloadRequestHandler`, the "Continue on this device" card; `androidMain`/`desktopMain`: `PlatformActions` implementations (08) | `:core:{designsystem, model, common, navigation}`, `:download:api` | coil-compose, kotlinx-collections-immutable, navigation3-ui-jb, `cmp-material3-adaptive-navigation3`, the JetBrains lifecycle artifacts, metrox-viewmodel-compose, reorderable (M4) | M0a |
| `:core:testing` | `.kmp.library`, `.metro` | common: `Fake<Name>` for every `:core:domain`, `:*:api` and `:sync:api` interface, `TestClock`, `MainDispatcherRule`, `Goldens`, 10's `InMemorySyncServer`; `androidMain`: Android-only helpers | `:core:{domain, model, common}`, `:*:api` (incl. `:sync:api`), `:sync:protocol` | `api`: kotlin-test, kotlinx-coroutines-test, turbine, coil-test; `desktopMain`/`androidMain`: junit4, truth | M0a |
| `:feeds` | `.kmp.library`, `kotlin.plugin.serialization` | common only (one `actual` for NFC, 05): `ParsedFeed` models, `EpisodeKeys`, `UrlNormalizer`, `FeedDates`, `PodcastGuid`, `GroupNames`, OPML model and `OpmlWriter`, backup models, the `FeedParser`/`OpmlReader`/`ShowNotesSanitizer` interfaces, `ShowNotesDocument` ([03 Parser](03-feeds-and-discovery.md#parser)) | — | kotlinx-serialization-json, kotlinx-datetime | M1a (`GroupNames` M2) |
| `:feeds:jvm` | `.jvm.island` | JVM 17: `XmlPullFeedParser`, `XmlPullOpmlReader`, `JsoupShowNotesSanitizer` | `:feeds` | jsoup; `compileOnly` + `testImplementation(kxml2)` | M1a |
| `:playback:api` | `.kmp.library`, `.metro` | common only: `PlaybackController`, `PlaybackStateSource`, `PlaybackState`, `NowPlaying`, `PlayContextSpec`, `UnplayableReason` (no Media3 type) | `:core:{model, common}` | kotlinx-coroutines-core | M0a |
| `:playback:core` | `.kmp.library`, `.metro` | common: `QueueWindowPlanner`, `WindowDiff`, `PositionSaver`, `PlayedRule`, `PlayStarter`, `SleepTimerCore`, `ChapterIndex`, `EffectivePlaybackSettings` ([06 Shared playback core](06-playback.md#shared-playback-core)) | `:playback:api`, `:core:{domain, model, common}` | kotlinx-coroutines-core | M4 (M5: sleep timer, chapters) |
| `:playback:impl` | `neutrodyne.android.library` | Android: the Media3 service design (06), `QueueProjector` and session code adapting `:playback:core` | `:playback:{core, api}`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`, `:core:network:okhttp` | media3-exoplayer, -session, -datasource-okhttp, -common-ktx, -inspector (M5), kotlinx-coroutines-guava, lifecycle-process (`PlayerConnection`), kotlinx-serialization-json | M4 |
| `:playback:engine` | `neutrodyne.desktop.library` | JVM 25: `AudioEngine`, `FfAudioEngine`, `DesktopSourceResolver`, `SpanCache`, `HttpByteSource`, `FfDemuxer`, `FfDecoder`, `FfmpegLibrary`, `Sonic`, `SilenceSkipper`, … ([11 Desktop playback engine](11-desktop.md#desktop-playback-engine)) | `:playback:native`, `:playback:api`, `:core:network:okhttp`, `:core:{model, common}` | okio | MD0 (prototype), MD1 |
| `:playback:native` | `.desktop.library`, `neutrodyne.desktop.native` | JVM 25 + `src/native/` (C, C++/WinRT, Objective-C, CMake): `ndmedia`, `NdmediaLibrary`, `NdOutput`, `ffmpeg/build.sh`, `native-components.lock` | — | none on the classpath; native components per `native-components.lock` | MD0, MD1 |
| `:playback:desktop` | `.desktop.library` | JVM 25: `DesktopPlaybackController`, `DesktopQueueProjector`, `DesktopPlaybackModule` | `:playback:{core, api, engine}`, `:desktop:system`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, artwork, database, datastore}` | — | MD1 |
| `:desktop:system` | `.desktop.library` | JVM 25: `SystemMediaSession` and its three implementations, `IdleSleepInhibitor`, `AudioRouteMonitor`, `TrayController`, `LoginItemRegistrar`; `OsPowerMonitor`, `OsDesktopNotifier` and `DbusDesktopPortal` implementing the `:core:common` `desktopMain` ports `PowerMonitor`, `DesktopNotifier` and `LinuxDesktopPortal` (2026-10-05; [11 Runner contract](11-desktop.md#runner-contract)) | `:playback:native`, `:playback:api` (state types), `:core:{model, common}` | dbus-java-core, dbus-java-transport-native-unixsocket, jna, jna-platform | M0b (tray stub), MD2 |
| `:download:api` | `.kmp.library`, `.metro` | common only | `:core:{model, common}` | kotlinx-coroutines-core | M0a |
| `:download:impl` | `.kmp.library`, `.metro` | common: `DownloadEngine`, the transfer core on Ktor and Okio, `AutoDownloadPlanner`, cleanup; `androidMain`: `ManualDownloadJobService`, `DownloadLaneWorker`, notifications, `DownloadActionReceiver`, manifest entries; `desktopMain`: `DesktopDownloadLane`, `DesktopMoveLane` ([07](07-downloads.md#runners-and-scheduling)) | `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}` | ktor-client-core, okio, kotlinx-serialization-json; `androidMain`: work-runtime, lifecycle-process (07's `AppVisibility`) | M6 |
| `:youtube:api` | `.kmp.library`, `.metro` | common only: `YouTubeCapabilities`, `YouTubeCapabilitiesSource`, `YtRef`, `YouTubeIds`, `YouTubeUrlClassifier`, `YouTubeStreamResolver`, `YouTubeEngine`, … | `:core:{model, common}` | kotlinx-coroutines-core | M2 (capabilities), M3 (classifier), M4 (resolver contract), M8 (rest), M9a (engine contracts); see [YouTube bindings](#youtube-bindings) |
| `:youtube:impl` | `.kmp.library`, `.metro` | common: Layer A on Ktor (channel resolver, oEmbed, Atom helpers) and the external-only implementations (`StaticYouTubeCapabilitiesSource`, `ExternalOnlyYouTubeStreamResolver`, `AbsentYouTubeEngine`, …) | `:youtube:api`, `:core:{model, common, network}` | ktor-client-core, kotlinx-serialization-json | M2, M4, M8, M9a |
| `:youtube:engine` | `.jvm.island` | JVM 17: the host-independent engine logic — `YtxTransport`, `YtDlpClient`, `YtDlpEngine`, resolvers, mappers, `EngineStore`, `EngineUpdater`, the trust-chain verifiers, `EngineSelfTestRunner`, `EngineRollbackMonitor` ([04 Shared engine module](04-youtube.md#shared-engine-module)) | `:youtube:api`, `:core:{model, common}` | kotlinx-serialization-json, kotlinx-coroutines-core, okio | M9a (host-independent classes are written here from the start), MD3 (the stdio host's needs) |
| `:youtube:ytdlp` | `.android.library`, `com.chaquo.python` (the only module with it, [D72](../PLAN.md#3-key-decisions)); `buildFeatures.aidl = true` | Android: `YtxService`, `IYtxEngine`/`IYtxCallback`, `BinderYtxTransport`, `PyHttp`, `EngineUpdateWorker`, `TinkEd25519Verifier`, `AndroidEngineStorePaths`, Chaquopy packaging of the shared shim | `:youtube:engine`, `:youtube:api`, `:core:{model, common, datastore}`, `:core:network:okhttp` | Chaquopy runtime (via its plugin; CPython 3.14), work-runtime, okhttp-coroutines, kotlinx-serialization-json; M9b: tink-android, quickjs-kt-android (only if the JS provider ships) | M0a stub (Chaquopy hello-world `selftest` if [S7](#s7-chaquopy-under-agp-941) is go), M9a, M9b ([04 YouTube engine](04-youtube.md#youtube-engine)) |
| `:youtube:ytdlp-desktop` | `.desktop.library` | JVM 25: `YtxProcess`, `StdioYtxTransport`, `PythonRuntimeLocator`, `DesktopEngineStorePaths`, `DesktopEngineUpdateLane`, `QuickJsBridge` (only with the JS provider); the python-build-standalone bundle; `python-components.lock` ([11 Desktop YouTube engine host](11-desktop.md#desktop-youtube-engine-host)) | `:youtube:engine`, `:youtube:api`, `:core:{model, common, datastore}` | kotlinx-serialization-json; MD3 conditional: quickjs-kt-jvm | M0a stub, MD3 |
| `:sync:protocol` | `.kmp.library`, `kotlin.plugin.serialization` | common only (its JVM variant is used by `:sync:server`): `Hlc`, `HlcClock`, `NodeId`, `OrderKey`, `FieldKind`, `RecordMerger`, the DTOs, `PROTOCOL_VERSION`, `ConformanceVectors` ([10 Protocol](10-sync.md#protocol)) | — | kotlinx-serialization-json | M1a (`Hlc`, `OrderKey`), MS0 |
| `:sync:api` | `.kmp.library`, `.metro` | common only: `SyncController`, `SyncStatus`, `LinkFlow`, `MassChangePrompt`, `RemoteSessionOffer`, `SyncDisclosure`, … (10) | `:core:{model, common}` | kotlinx-coroutines-core | M0a stub, MS2 |
| `:sync:impl` | `.kmp.library`, `.metro`, `kotlin.plugin.serialization` | common: `SyncEngine`, `SyncClient`, `SyncEventsClient`, `SyncApplier`, `FirstLinkMerger`, `MassChangeGuard`, `SessionAdopter`, `SyncTokenStore`, `SyncScheduler`, …; `androidMain`: `WorkManagerSyncScheduler`, `SyncWorker`, `LocalNetworkPermissionGate`; `desktopMain`: `DesktopSyncLane` ([10 Client sync engine](10-sync.md#client-sync-engine)) | `:sync:{api, protocol}`, `:core:{domain, model, common, database, datastore, network}`, `:feeds` | ktor-client-core, ktor-client-content-negotiation, ktor-serialization-kotlinx-json, kotlinx-serialization-json; `androidMain`: work-runtime | MS2 (MS3: SSE, handoff) |
| `:feature:feeds` | `neutrodyne.kmp.feature` | common | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1a (All), M2 |
| `:feature:library` | feature | common | + `:playback:api`, `:download:api`, `:youtube:api` (group-tile actions: Play, Download all) | — | M1a |
| `:feature:groups` | feature | common | + `:youtube:api` (`YouTubeCapabilities` in Group settings) | reorderable | M2 |
| `:feature:podcast` | feature | common | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1a |
| `:feature:episode` | feature | common | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1a |
| `:feature:player` | feature | common | + `:playback:api`, `:download:api`, `:youtube:api` (Up next tab rows, download action, `RowCaps`) | none in v1.0: no Media3 type enters a feature ([06 UI boundary](06-playback.md#ui-boundary)); M14 adds an Android-only `PlayerSurface` through the qualifier pattern of [Components and scopes](#components-and-scopes) rule 4 | M4 |
| `:feature:queue` | feature | common | + `:playback:api`, `:download:api`, `:youtube:api` (row download buttons, `RowCaps`) | reorderable | M4 |
| `:feature:downloads` | feature | common | + `:download:api`, `:playback:api` (`playDownloads`), `:youtube:api` (`YouTubeHealth.retryNow`) | — | M6 |
| `:feature:discover` | feature | common | + `:youtube:api` | — | M1a (add by URL), M7 |
| `:feature:importexport` | feature | common | + `:playback:api` (pause before Replace), `:download:api` (re-download offer), `:youtube:api` (`YouTubeCapabilities`) | — | M3 |
| `:feature:settings` | feature | common; Settings › Desktop shown only when `PlatformInfo.kind` is the desktop | + `:youtube:api` (`YouTubeEngine` rows in Settings › YouTube, M9a); the update check through `:core:domain` (`AppUpdateChecker`, `UpdateNotices`: Settings › Updates, Install & updates help, M11a) | aboutlibraries-core | M0a |
| `:feature:sync` | feature | common: Settings › Sync, link flows, devices, mass-change dialog, restore-while-linked prompts ([08 Sync screens](08-ui-ux.md#sync-screens)) | + `:sync:api` | — | MS2 |
| `:benchmark` | `com.android.test`, `androidx.baselineprofile` (producer) | Android: out-of-process system tests (M6b), Macrobenchmarks against `benchmarkRelease` (M10), `BaselineProfileGenerator` (M11b) | `targetProjectPath = ":app"` | uiautomator (M6b), benchmark-macro-junit4 (M10) | M6b ([09 Out-of-process system tests](09-quality-and-release.md#out-of-process-system-tests), [09 Macrobenchmark and profiles](09-quality-and-release.md#macrobenchmark-and-profiles)) |

**`api` vs `implementation`:** a module exposes a dependency as `api` only when its types appear in that module's public signatures (`:core:domain` → `:core:model`, `paging-common`; `:core:network` → `ktor-client-core`; `:core:network:okhttp` → `okhttp`; `:core:database` → `room3-runtime`, `room3-paging`); everything else is `implementation`. Implementation classes in implementation modules are `internal`; only graph contributions, Android framework components and the public API are `public` (Unverified whether Metro aggregates `internal` contributions across modules; S8 decides whether contributed implementations may stay `internal`).

**External-library placement:** Room only through `:core:database`; DataStore only in `:core:datastore`; OkHttp clients are constructed only in `:core:network:okhttp`, and Ktor clients only through `:core:network`'s factory; Media3 player and session only in `:playback:impl`; FFmpeg and miniaudio only in `:playback:native` and `:playback:engine`; dbus-java only in `:desktop:system`; WorkManager workers only in the `androidMain` source sets of `:core:data` (including the update check's `UpdateCheckWorker`, M11a), `:core:artwork`, `:download:impl` and `:sync:impl`, and in `:youtube:ytdlp` (`EngineUpdateWorker`, M9b) (configuration in `:app`); Chaquopy, Python code packaging, AIDL and anything that runs in the `:ytx` process only in `:youtube:ytdlp`; python-build-standalone and `ProcessBuilder` only in `:youtube:ytdlp-desktop`; jsoup and kxml2 only in `:feeds:jvm` (kxml2 at run time also in `:desktopApp`); JNA only in `desktopMain` source sets and desktop-only modules; `PackageInstaller` nowhere (the apps install nothing, [D78](../PLAN.md#3-key-decisions)); images are rendered only through `:core:designsystem`/`:core:ui` composables ([08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)).

### Source sets and JVM islands

[D81](../PLAN.md#3-key-decisions). Delivered in M0a (structure and checks), applied by every later milestone.

**Targets.** Every shared module declares exactly two targets: `android` (`com.android.kotlin.multiplatform.library`) and `jvm("desktop")`. There is no iOS target and none is added in v1.x; the rules below only keep that door open ([PLAN 1.2](../PLAN.md#12-non-goals-for-v10)). `applyDefaultHierarchyTemplate()` produces the source sets `commonMain`, `androidMain`, `desktopMain` and their tests `commonTest`, `desktopTest` and, opt-in, `androidHostTest` (Robolectric) — the Android-KMP plugin creates host and device tests only on request and has no build types, so no shared module has debug-only code ([Android-KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)).

**What may live where:**

| Place | May contain | Must not contain |
|---|---|---|
| `commonMain` | All logic and UI: models, contracts, repositories, ingestion, rules, ViewModels, Compose Multiplatform screens, Compose resources; KMP libraries only (Ktor, Okio, kotlinx, Room, DataStore, Paging, Coil, Compose, Metro, Nav3, lifecycle) | `java.*`, `javax.*`, `android.*` (compiler plus [`checkBannedApis`](#checkbannedapis-rules) rule 1); a JVM island; a JVM-only library (OkHttp, jsoup, kxml2, JNA, Media3, WorkManager) |
| `androidMain` | Android frameworks behind `commonMain` interfaces: WorkManager workers, UIDT `JobService`s, `BroadcastReceiver`s, `ContentProvider`s, Android Keystore, `ConnectivityManager`, notification channels, dynamic colour; Android manifest entries of the module; uses of a JVM island | UI logic or rules that the desktop also needs |
| `desktopMain` | Desktop counterparts behind the same interfaces: `DesktopJobRunner` lanes, `AppDirs`, `DesktopSecretStore`, `DesktopNetworkMonitor`, file dialogs, scrollbar styles; uses of a JVM island | AWT or Swing types in a public API seen by `commonMain` |
| JVM island (`kotlin("jvm")`, bytecode 17) | JVM-only third-party code that both platforms run identically: `:feeds:jvm` (XmlPullParser parsers, jsoup sanitiser), `:core:network:okhttp` (the OkHttp client family, interceptors, LAN guard, DNS hints), `:youtube:engine` (host-independent engine logic and trust chain) | Android or AWT APIs; Java APIs newer than Android API 26 provides (Android runs the same bytecode; S8, 2026-10-06: **Lint's `NewApi` does not cover plain JVM islands** — neither `:app`'s `checkDependencies` lint nor the island's own `com.android.lint` lint reports an API-30 probe — so the islands' Android compatibility is enforced by their tests running in the instrumented suite and by review) |
| Platform-only module | Android: `:playback:impl` (Media3), `:youtube:ytdlp` (Chaquopy, AIDL, `:ytx`), `:benchmark`. Desktop: `:playback:engine`, `:playback:native`, `:playback:desktop`, `:desktop:system`, `:youtube:ytdlp-desktop`. Server: `:sync:server` | Code another platform needs |

**`expect`/`actual`** is used only for small platform shims ([D81](../PLAN.md#3-key-decisions)): `Nfc` (Unicode NFC normalisation; `java.text.Normalizer` on both targets) and `DateFormatter` (localised dates; `java.time.format` on both) in `:core:common`, `StoragePaths` in `:core:common` (the app's data, no-backup and cache roots: Android from `Context`, the desktop from `AppDirs`), the dynamic-colour scheme in `:core:designsystem` (Android 12+; the desktop returns none), `NeutrodyneDatabaseConstructor` (Room's generated `actual`s), and `:feeds`' own NFC helper (05). Everything else that differs per platform is an interface in `commonMain` bound by DI in the shells ([Dependency injection](#dependency-injection)).

**Why there is no shared JVM+Android source set.** Both shipping targets run JVM bytecode, but "Kotlin doesn't currently support sharing a source set for … JVM + Android targets" ([KMP hierarchy](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)): a hand-made intermediate source set compiles, but IDE analysis, metadata compilation and dependency resolution are unsupported and it would close the iOS door for its code ([D81](../PLAN.md#3-key-decisions), rejected alternative). JVM-only code therefore goes into islands.

**How islands are consumed.** An island is consumed as `api(project(":…"))` from an `androidMain` or `desktopMain` source set or as a plain dependency of a platform-only module, never from `commonMain` (rule 2 of [`checkBannedApis`](#checkbannedapis-rules)); `:core:network` exposes `:core:network:okhttp` as `api` because the island's Metro contributions reach a shell graph only when the island is on that shell's **compile** classpath — a transitive `implementation` edge drops the contribution hints and fails the graph with a missing binding (S8, 2026-10-06). The island implements an interface declared in `commonMain` of a module it may see (`:feeds:jvm` implements `:feeds`' `FeedParser`; `:youtube:engine` implements `:youtube:api`'s `YouTubeEngine`) or exposes a JVM-only type that only platform code touches (`NetworkClients`). Its bindings are Metro contributions to `AppScope` (and, for the network island, `YtxScope`), so they reach whichever shell graph has the island on its compile classpath. Android consumes the island's JAR like any Java library (bytecode 17, desugaring off); the desktop loads it from the app image. kxml2 is `compileOnly` in `:feeds:jvm` because Android supplies the platform `XmlPullParser`; `:desktopApp` adds it as `runtimeOnly`.

**iOS-readiness rules** (cheap now, expensive later): no `java.*`/`android.*` in any `commonMain`; only KMP libraries in `commonMain`; contracts and shared models common-only; every platform service behind an interface or one of the `expect`s above; Nav3 keys registered in `NavKeySerializers` ([Navigation](#contracts-in-corenavigation)); strings as Compose resources ([D83](../PLAN.md#3-key-decisions)); time through `kotlin.time` and kotlinx-datetime; no new JVM island without a `commonMain` interface in front of it. Not done now: iOS targets, a macOS runner for iOS builds, porting the islands (an iOS port would need xmlutil and Ksoup for `:feeds:jvm`, AVPlayer for playback, BGTaskScheduler for background work, and has no YouTube engine).

**Test source sets.** `commonTest` runs on the desktop JVM (`desktopTest` task) in CI's `unit` job; common tests cannot run as Android local tests ([Compose MP testing](https://kotlinlang.org/docs/multiplatform/compose-test.html)). `desktopTest` holds tests that need JVM-only libraries (Truth, TestParameterInjector, MockWebServer) or the desktop driver. `androidHostTest` (Robolectric) exists only in modules with `androidMain` code worth a host test; instrumented tests live in `:app` and `:benchmark` ([09 Test infrastructure](09-quality-and-release.md#test-infrastructure)).

---

## Dependency rules

Serves N11, N8. Delivered in M0a (enforced from the first commit; the desktop and server rules apply from the stubs on). Source: [PLAN 5.1](../PLAN.md#51-module-graph) rules 1–8. Rules 1–9 expand PLAN rules 1–4 module by module; rules 10–13 (marked ⊕) make explicit the edges the PLAN graph implies; rules 14–18 expand PLAN rules 5–8. The numbers of rules 7, 8, 10 and 11 keep their pre-revision meaning because other documents cite them.

| # | Rule |
|---|---|
| 1 | **Composition roots.** `:app` (Metro `AndroidAppGraph`), `:desktopApp` (`DesktopAppGraph`) and `:sync:server` (plain constructor wiring) may depend on anything their platform allows; nothing depends on them; no module has product flavors ([D2](../PLAN.md#3-key-decisions)). Only `:app` depends on `:youtube:ytdlp` and only `:desktopApp` on `:youtube:ytdlp-desktop` (plain `implementation`, both dropped by `-Pneutrodyne.youtubeEngine=false`). |
| 2 | `:feature:*` → `:core:{domain, model, common, designsystem, ui, navigation}`, `:playback:api`, `:download:api`, `:youtube:api`, `:sync:api`. Never feature → feature; never → `:core:{data, database, datastore, network, artwork}`, an implementation module, a JVM island or a platform-only module. Platform-only UI actions go through `:core:ui`'s `PlatformActions` ([D83](../PLAN.md#3-key-decisions)). |
| 3 | `:core:domain` → `:core:{model, common}`, `:playback:api`, `:download:api`, `:youtube:api`, `paging-common`. |
| 4 | `:core:data` → `:core:{domain, model, common, database, datastore, network, artwork}`, `:feeds`, `:youtube:api`; its `androidMain` and `desktopMain` also → `:feeds:jvm`. |
| 5 | `:core:artwork` → `:core:{model, common, database, network}`, `:youtube:api`; its platform source sets also → `:core:network:okhttp` (Coil's OkHttp fetcher, [D10](../PLAN.md#3-key-decisions)). |
| 6 | Shared implementations and the Android-only modules: `:playback:core` → `:playback:api`, `:core:{domain, model, common}`. `:playback:impl` → `:playback:{core, api}`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`, `:core:network:okhttp`. `:download:impl` → `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`. `:youtube:impl` → `:youtube:api`, `:core:{model, common, network}`. `:sync:impl` → `:sync:{api, protocol}`, `:core:{domain, model, common, database, datastore, network}`, `:feeds`. `:youtube:ytdlp` → `:youtube:{engine, api}`, `:core:{model, common, datastore}`, `:core:network:okhttp`. No implementation → `:core:data`, no implementation → another implementation, except `:core:artwork` (an infrastructure service any implementation may use) and `:playback:core` (the shared rule set of both platform players, [D84](../PLAN.md#3-key-decisions)). The update check has no module of its own ([D13](../PLAN.md#3-key-decisions)), so it adds no edge. YouTube Atom feeds are fetched and parsed by the generic refresh engine in `:core:data` ([03](03-feeds-and-discovery.md#refresh-scheduling)) using `:youtube:api` helpers; no YouTube module parses Atom. |
| 7 | `:core:designsystem` → `:core:model` only. `:core:ui` → `:core:{designsystem, model, common, navigation}`, `:download:api` (the `:core:navigation` edge, added 2026-10-05, lets the shared navigation host and root layout live in `:core:ui` for both shells, [AppNavigator and per-tab back stacks](#appnavigator-and-per-tab-back-stacks)). `:core:navigation` → nothing project-internal. |
| 8 | Common-only contracts and shared models: `:core:{model, common, domain}`, `:*:api`, `:feeds`, `:sync:protocol` have only `commonMain` code (the `actual`s of [Source sets and JVM islands](#source-sets-and-jvm-islands) excepted). `:feeds` and `:sync:protocol` depend on nothing project-internal ([D68](../PLAN.md#3-key-decisions)), so `:sync:server` can use them without the app's model. |
| 9 | `:core:testing` → `:core:{domain, model, common, database, navigation}`, `:*:api` (incl. `:sync:api`), `:sync:protocol` (`:core:database` and `:core:navigation` added 2026-10-05 for `TestDb` and `RecordingAppNavigator`, [09 Shared helpers](09-quality-and-release.md#shared-helpers)). It is test-only: only test configurations depend on it, and no main configuration of any module may reach it (`restricted`). |
| 10 ⊕ | `:playback:api`, `:download:api`, `:youtube:api`, `:sync:api` → `:core:{model, common}`. |
| 11 ⊕ | `:core:database`, `:core:datastore`, `:core:network` → `:core:{model, common}`; `:core:network`'s platform source sets also → `:core:network:okhttp`. |
| 12 ⊕ | `:core:model` and `:core:common` → nothing project-internal. |
| 13 ⊕ | Test edges (`commonTest`, `desktopTest`, `androidHostTest`, `testImplementation`, `androidTestImplementation`) are not asserted; every module's tests may use `:core:testing` (KMP, fakes in `commonMain`), which also hosts the database fixtures (`TestDb`) and `RecordingAppNavigator` because the `java-test-fixtures` plugin does not apply to KMP modules (`:core:database`'s own tests depend on `:core:testing`, a test-scope edge back onto the module's main code; Unverified for KMP until M1a) ([09 Shared helpers](09-quality-and-release.md#shared-helpers)); `:youtube:ytdlp` and `:youtube:ytdlp-desktop` need no Gradle test fixtures: `FakeYtxTransport` (04; replaces `FakeYtDlpClient`) and the Python `RecordingRH`/`ReplayRH` are their own test doubles (04). The [Gradle-side tasks](#gradle-side-policy-tasks) check runtime classpaths. |
| 14 | **JVM islands** (`:feeds:jvm`, `:core:network:okhttp`, `:youtube:engine`) → only `:core:{model, common}`, the contracts and the shared models (`:feeds:jvm` → `:feeds`; `:youtube:engine` → `:youtube:api`). They are used only from `androidMain`, `desktopMain` and platform-only modules, behind `commonMain` interfaces ([Source sets and JVM islands](#source-sets-and-jvm-islands)). |
| 15 | **Desktop-only modules** are used only by `:desktopApp` and by the desktop module that adapts them: `:playback:native` → nothing project-internal; `:desktop:system` → `:playback:native`, `:playback:api` (`NowPlaying` for `SystemMediaSession`), `:core:{model, common}`; shared modules reach desktop services only through the `:core:common` `desktopMain` ports `JobLanePoker`, `PowerMonitor`, `DesktopNotifier` and `LinuxDesktopPortal` ([11 Runner contract](11-desktop.md#runner-contract)), which `:desktop:system` and `:core:data` implement and `DesktopAppGraph` binds; `:playback:engine` → `:playback:native`, `:playback:api`, `:core:network:okhttp`, `:core:{model, common}`; `:playback:desktop` → `:playback:{core, api, engine}`, `:desktop:system`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, artwork, database, datastore}`; `:youtube:ytdlp-desktop` → `:youtube:{engine, api}`, `:core:{model, common, datastore}`. **Android-only modules** (`:playback:impl`, `:youtube:ytdlp`) are used only by `:app`; `:benchmark` targets `:app`. |
| 16 | **`:sync:server`** → `:sync:protocol`, `:feeds` only; never Android, desktop, UI or other app modules. |
| 17 | **Platform APIs** (checked per source set by [`checkBannedApis`](#checkbannedapis-rules), where the graph plugin cannot see): no `java.*`, `javax.*` or `android.*` in any `commonMain`; `:youtube:ytdlp` is the only module that applies Chaquopy, packages Python or runs code in `:ytx`; `:youtube:ytdlp-desktop` is the only module that starts a process; `:playback:native` and `:desktop:system` are the only modules with our own native code and FFM downcalls into it, and `:playback:engine` makes FFM downcalls into FFmpeg only; `java.awt.Desktop` only in desktop code of `:desktopApp`, `:desktop:system` and `:core:ui`. |
| 18 | **Licences** per [D3](../PLAN.md#3-key-decisions): Licensee on the three shells, both Python locks, `native-components.lock`, the runtime lock, and the APK, desktop-image and server-image scans ([Licensing and dependency policy](#licensing-and-dependency-policy)). |

Consequences implementers must design for:

- **Interfaces in `:core:domain`, bindings in implementations** ([D12](../PLAN.md#3-key-decisions)). An implementation module that needs another implementation's behaviour calls the `:core:domain` or `:*:api` interface; Metro supplies the implementation in the shell's graph.
- **Types crossing from `:feeds` to the UI** (for example the show-notes block model of [D27](../PLAN.md#3-key-decisions)): `:feeds` cannot see `:core:model`, and `:core:ui` cannot see `:feeds`, so `:core:data` maps `:feeds` output into a `:core:model` mirror (03: `ShowNotesDocument` → `ShowNotes`, [03 Show notes](03-feeds-and-discovery.md#show-notes)); rule 8 stands ([D68](../PLAN.md#3-key-decisions)).
- **JVM-only work stays behind an interface.** `FeedFetcher` (common) asks `:feeds`' `FeedParser`; the shell graph binds `:feeds:jvm`'s `XmlPullFeedParser`. Common code never names an OkHttp or jsoup type.
- **`:core:artwork`** is the one infrastructure service implementations may share (PLAN rule 4).

```mermaid
flowchart TB
  app[":app<br/>Android shell"]
  dapp[":desktopApp<br/>desktop shell"]
  srv[":sync:server"]
  feat[":feature:*"]
  ui[":core:ui"]
  ds[":core:designsystem"]
  nav[":core:navigation"]
  dom[":core:domain"]
  apis[":playback:api<br/>:download:api<br/>:youtube:api<br/>:sync:api"]
  data[":core:data"]
  impls[":download:impl<br/>:youtube:impl<br/>:sync:impl"]
  pc[":playback:core"]
  art[":core:artwork"]
  infra[":core:database<br/>:core:datastore<br/>:core:network"]
  shared[":feeds<br/>:sync:protocol"]
  isl["JVM islands<br/>:feeds:jvm<br/>:core:network:okhttp<br/>:youtube:engine"]
  andonly["Android-only<br/>:playback:impl<br/>:youtube:ytdlp"]
  deskonly["Desktop-only<br/>:playback:desktop, :playback:engine<br/>:playback:native, :desktop:system<br/>:youtube:ytdlp-desktop"]
  base[":core:model<br/>:core:common"]
  app --> feat & data & impls & pc & art & infra & andonly
  dapp --> feat & data & impls & pc & art & infra & deskonly
  srv --> shared
  feat --> ui & ds & nav & dom & apis & base
  ui --> ds & nav & base & apis
  ds --> base
  dom --> apis & base
  apis --> base
  data --> dom & infra & art & shared & apis
  data -. platform source sets .-> isl
  art -. platform source sets .-> isl
  impls --> dom & apis & infra & art & shared
  pc --> dom & apis
  art --> infra & apis
  infra --> base
  infra -. platform source sets .-> isl
  andonly --> pc & isl & apis & infra & art
  deskonly --> pc & isl & apis & infra & art
  isl --> base & shared & apis
```

(Dotted edges are declared only in `androidMain`/`desktopMain`. `ui` → `apis` means `:download:api` only; `data` → `apis` means `:youtube:api` only; `data` → `shared` means `:feeds` only; `impls` → `shared` means `:sync:impl` → `:feeds`, `:sync:protocol`; `infra` → `isl` means `:core:network` → `:core:network:okhttp`; `isl` → `shared` and `isl` → `apis` mean `:feeds:jvm` → `:feeds` and `:youtube:engine` → `:youtube:api`; the platform-only groups' detailed edges are rules 6 and 15; `:core:testing` and `:benchmark` are omitted. `app` → `:youtube:ytdlp` and `dapp` → `:youtube:ytdlp-desktop` are absent in the no-engine builds. The update check adds no node: `dom`, `base` and `data` carry it.)

### Module-graph assertion configuration

Applied by the convention plugins to `:app`, `:desktopApp` and `:sync:server` and, with the same rules, to `:core:testing` (the only main-code module reachable from no shell's main configurations, so rule 9 is otherwise unchecked); rules live in `build-logic/convention/src/main/kotlin/ModuleRules.kt` so they are reviewed like code.

```kotlin
moduleGraphAssert {
    maxHeight = 6
    // KMP modules declare edges per source set; from a shell only module-level edges are visible (S8, 2026-10-06).
    // Every main source set's four scopes — a compileOnly/runtimeOnly edge must not bypass the graph (2026-10-06).
    configurations += setOf("api", "implementation", "compileOnly", "runtimeOnly",
        "commonMainApi", "commonMainImplementation", "commonMainCompileOnly", "commonMainRuntimeOnly",
        "androidMainApi", "androidMainImplementation", "androidMainCompileOnly", "androidMainRuntimeOnly",
        "desktopMainApi", "desktopMainImplementation", "desktopMainCompileOnly", "desktopMainRuntimeOnly")
    allowed = arrayOf(
        ":app -> .*",
        ":desktopApp -> .*",
        ":sync:server -> :(sync:protocol|feeds)",
        ":feature:[a-z]+ -> :core:(domain|model|common|designsystem|ui|navigation)",
        ":feature:[a-z]+ -> :(playback|download|youtube|sync):api",
        ":core:domain -> :core:(model|common)", ":core:domain -> :(playback|download|youtube):api",
        ":core:data -> :core:(domain|model|common|database|datastore|network|artwork)", ":core:data -> :feeds(:jvm)?", ":core:data -> :youtube:api",
        ":core:artwork -> :core:(model|common|database|network|network:okhttp)", ":core:artwork -> :youtube:api",
        ":playback:core -> :playback:api", ":playback:core -> :core:(domain|model|common)",
        ":playback:impl -> :playback:(core|api)", ":playback:impl -> :(download|youtube):api",
        ":playback:impl -> :core:(domain|model|common|database|datastore|network|network:okhttp|artwork)",
        ":download:impl -> :(download|youtube):api", ":download:impl -> :core:(domain|model|common|database|datastore|network|artwork)",
        ":youtube:impl -> :youtube:api", ":youtube:impl -> :core:(model|common|network)",
        ":sync:impl -> :sync:(api|protocol)", ":sync:impl -> :core:(domain|model|common|database|datastore|network)", ":sync:impl -> :feeds",
        ":youtube:ytdlp -> :youtube:(engine|api)", ":youtube:ytdlp -> :core:(model|common|datastore|network:okhttp)",
        ":youtube:ytdlp-desktop -> :youtube:(engine|api)", ":youtube:ytdlp-desktop -> :core:(model|common|datastore)",
        ":youtube:engine -> :youtube:api", ":youtube:engine -> :core:(model|common)",
        ":feeds:jvm -> :feeds", ":core:network:okhttp -> :core:(model|common)",
        ":playback:engine -> :playback:(native|api)", ":playback:engine -> :core:(model|common|network:okhttp)",
        ":playback:desktop -> :playback:(core|api|engine)", ":playback:desktop -> :desktop:system",
        ":playback:desktop -> :(download|youtube):api", ":playback:desktop -> :core:(domain|model|common|artwork|database|datastore)",
        ":desktop:system -> :playback:(native|api)", ":desktop:system -> :core:(model|common)",
        ":core:designsystem -> :core:model", ":core:ui -> :core:(designsystem|model|common|navigation)", ":core:ui -> :download:api",
        ":core:testing -> :core:(domain|model|common|database|navigation)", ":core:testing -> :(playback|download|youtube|sync):api", ":core:testing -> :sync:protocol",
        ":(playback|download|youtube|sync):api -> :core:(model|common)",
        ":core:(database|datastore) -> :core:(model|common)", ":core:network -> :core:(model|common|network:okhttp)",
    )
    restricted = arrayOf(
        ":feature:.* -X> :feature:.*",
        ":(?!app).* -X> :(youtube:ytdlp|playback:impl)",
        ":(?!desktopApp).* -X> :(youtube:ytdlp-desktop|playback:desktop)",
        ":(?!desktopApp|playback:desktop).* -X> :(playback:engine|desktop:system)",
        ":(?!desktopApp|playback:engine|desktop:system).* -X> :playback:native",
        ":.* -X> :(app|desktopApp|sync:server)",
        ":.* -X> :core:testing",              // main configurations only; test edges are not asserted (rule 13)
        ":(playback|download|youtube|sync):(impl|ytdlp|ytdlp-desktop|desktop|engine) -X> :core:data",
        ":sync:server -X> :(core|feature|playback|download|youtube|desktop):.*",
        ":(feeds|sync:protocol) -X> :.*",
    )
}
```

Verified 2026-10-06 (M0a step 21): 2.9.1's DSL is exactly as sketched — the extension is `moduleGraphAssert` with `maxHeight`, `allowed`, `restricted` and `configurations` (plus `assertOnAnyBuild`, unused). The graph walks the named configurations' *declared* `ProjectDependency`s, so KMP source-set edges appear under their configuration names (`commonMainImplementation`, `androidMainImplementation`, …); listing them in `configurations` covers them. All four scopes of every main source set are listed (updated 2026-10-06: `*Api` and `*CompileOnly`/`*RuntimeOnly` added — a `runtimeOnly` project edge previously escaped the graph). Because the configuration name is all the plugin sees, it cannot tell which platform source set an edge came from: the islands-only-from-platform-code part of rule 14 is enforced by `verifyDependencyPolicy`'s declared-edge check (authoritative since 2026-10-06) with [`checkBannedApis`](#checkbannedapis-rules) rule 2's build-script scan as the second line, and `assertModuleGraph` checks the module-level edges. With no product flavors, the plugin sees every main edge, including rule 1's "only `:app` → `:youtube:ytdlp`, only `:desktopApp` → `:youtube:ytdlp-desktop`".

---

## Architecture patterns

Serves N1, N6, N11. Delivered in M0a (conventions, `:core:common`), M0b (the desktop start-up), refined as features land. Honours D12, D14, D35, D81.

### Layers and data flow

```
Compose Multiplatform screen ──events──▶ ViewModel ──calls──▶ :core:domain interfaces / use cases
      ▲                                     │                          │ (Metro binds, per shell graph)
      └──── StateFlow<UiState> ◀────────────┘           :core:data, *:impl, :playback:core, platform modules
                                                                        │
              Room (bundled SQLite) / DataStore / Ktor over the OkHttp island / Media3 or the desktop engine /
              WorkManager and UIDT jobs (Android) or DesktopJobRunner lanes (desktop)
```

1. **Room is the single source of truth** on every client ([D14](../PLAN.md#3-key-decisions)). Network results and pulled sync records are written to Room; UI observes Room-backed `Flow`s. Nothing is cached in memory as truth; in-memory structures (`LocalMediaIndex`, `ResolvedUrlCache`, `DownloadProgressSource`) are mirrors or ephemeral state owned by their documents. A sync server holds a replica, never the truth ([D93](../PLAN.md#3-key-decisions)).
2. **Deferrable or must-survive-process-death work runs behind common scheduler interfaces**: WorkManager or UIDT jobs on Android ([07](07-downloads.md#runners-and-scheduling)), `DesktopJobRunner` lanes on the desktop, which persist their state in Room and resume at the next start ([11 Background work](11-desktop.md#background-work)). Fire-and-forget work that may die with the process runs in `@ApplicationScope`. Never `GlobalScope`.
3. **Repositories** are interfaces in `:core:domain`, return `:core:model` types (never Room entities, Ktor or OkHttp types), expose `Flow<T>` for observation and `suspend` functions for one-shot reads and writes, and are main-safe.
4. **Use-case rule:** a use case class exists only when logic spans ≥ 2 repositories/controllers or is reused by ≥ 2 callers. Named `VerbNounUseCase` with `operator fun invoke`. A use case that needs only domain interfaces is a concrete `@Inject` class in `:core:domain`; one that needs a multi-table transaction (e.g. `SubscribeUseCase`) is an interface in `:core:domain` implemented in `:core:data`.
5. **Platform code adapts, never decides.** Rules that must behave the same on a phone and a laptop (queue windows, the played rule, position guards, refresh due-times, auto-download planning, sync merges) live in `commonMain`; `androidMain`, `desktopMain` and the platform-only modules only adapt them to a framework ([D84](../PLAN.md#3-key-decisions), [Source sets and JVM islands](#source-sets-and-jvm-islands)).

### ViewModels and UI state

- ViewModels are KMP (`androidx.lifecycle.ViewModel` through JetBrains' lifecycle 2.11.0) and live in `commonMain` of their feature. One ViewModel per Nav entry, scoped to the entry ([Navigation](#viewmodels-per-entry)); arguments arrive through Metro's assisted injection of the whole key ([Feature entry installers](#feature-entry-installers)). The single exception is `PlayerViewModel` (`:feature:player`), which is scoped to the activity (Android) or the window (desktop) because `PlayerSheet` lives outside `NavDisplay` ([D56](../PLAN.md#3-key-decisions), [08 Player sheet](08-ui-ux.md#player-sheet)); the shell (`MainActivity`, `NeutrodyneWindow`) obtains it with `metroViewModel()` outside any entry and hands `PlayerSheet` to `NeutrodyneRoot` as its `player` slot ([Root contract](#appnavigator-and-per-tab-back-stacks)).
- Exposes `val uiState: StateFlow<XUiState>` built with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`; events are plain functions `fun onX(...)`; no public `MutableStateFlow`. `viewModelScope` runs on `Dispatchers.Main.immediate`: Android's main looper, the Swing event dispatch thread on the desktop (`kotlinx-coroutines-swing`, which Compose desktop does not add by itself).
- No `Context`, `Resources`, Android UI types or AWT/Swing types in ViewModels (`SavedStateHandle` allowed; `commonMain` cannot see the others anyway). User-visible text is a `UiText` (Compose resource + args, or plural), resolved in composition.
- Paged lists are a **separate property** `val items: Flow<PagingData<T>>` (built with `flatMapLatest` over inputs, then `.cachedIn(viewModelScope)`), never inside `UiState`; UI collects with `collectAsLazyPagingItems()` and `itemKey { it.id }` (`paging-compose` is KMP).
- Screens collect with `collectAsStateWithLifecycle()` only (`collectAsState()` is banned in `feature/`); on the desktop the window's lifecycle drives it (minimised → `STOPPED`; Unverified exact mapping, S9).

```kotlin
// Screen-level state shape (each feature defines its own XUiState in this form)
sealed interface PodcastUiState {
    data object Loading : PodcastUiState
    data class Ready(
        val header: PodcastHeader,                          // :core:model types or feature-local immutable classes
        val groups: ImmutableList<GroupChip>,
        val messages: ImmutableList<UserMessage> = persistentListOf(),
        val pendingNavigation: NavKey? = null,              // one-shot navigation as state, acknowledged by the UI
    ) : PodcastUiState
    data class Failed(val error: UiText, val retryable: Boolean) : PodcastUiState
}
data class UserMessage(val id: Long, val text: UiText, val action: UiText? = null)
// ViewModel API: fun onMessageShown(id: Long); fun onNavigationHandled()

// :core:ui — resource-free text for ViewModels, resolved in composition (Compose resources, D83)
sealed interface UiText {
    data class Res(val id: StringResource, val args: List<Any> = emptyList()) : UiText          // args: String/Int/Long or nested UiText
    data class Plural(val id: PluralStringResource, val count: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText                                                    // user content only (titles, names), never app copy
}
@Composable fun UiText.asString(): String                                                         // stringResource / pluralStringResource
suspend fun UiText.resolve(): String                                                              // getString / getPluralString, outside composition
```

`UiText.resolve()` exists for notifications, workers and desktop lanes, which run outside composition and use Compose resources' suspend `getString()` ([resources usage](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)); Android-only labels (manifest, notification-channel names, Android Auto titles) stay Android `res/` strings in `:app` or the declaring module's `androidMain` ([D83](../PLAN.md#3-key-decisions)).

**One-shot events are state with acknowledgement** (snackbars as `messages`, navigation after an async result as `pendingNavigation`), never `Channel`/`SharedFlow`: state survives configuration change and cannot be lost while the collector is stopped. Navigation that follows a direct user gesture is performed by the composable itself via [`LocalAppNavigator`](#appnavigator-and-per-tab-back-stacks); ViewModels never hold the navigator.

#### Model and state rules

- `:core:model` types are deeply immutable: `val` only, collection fields never mutated after construction, no platform types. This is what makes the `compose-stability.conf` entry valid.
- `UiState` uses `kotlinx.collections.immutable` (`ImmutableList`, `persistentListOf`).

### Errors

```kotlin
// :core:common
sealed interface Outcome<out T, out E> {
    data class Success<out T>(val value: T) : Outcome<T, Nothing>
    data class Failure<out E>(val error: E) : Outcome<Nothing, E>
}
inline fun <T, E, R> Outcome<T, E>.map(f: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(f(value)); is Outcome.Failure -> this
}
fun <T> Outcome<T, *>.getOrNull(): T? = (this as? Outcome.Success)?.value

/** runCatching that never swallows cancellation. */
suspend inline fun <T> suspendRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
```

1. Expected failures (network, parse, storage, invalid input) are values: each area defines a sealed error type (e.g. `AddPodcastError`, owned by 03) and returns `Outcome<T, ThatError>`.
2. Exceptions are for programming errors only (`check`, `require`, `error`); they crash, and ACRA (Android) or `DesktopCrashReporter` (desktop) records them ([09 Crash reporting and diagnostics](09-quality-and-release.md#crash-reporting-and-diagnostics), [11 Desktop diagnostics and crash files](11-desktop.md#desktop-diagnostics-and-crash-files)).
3. Network exceptions — OkHttp's and `java.io`'s on Media3 and Coil paths, Ktor's and the underlying `java.io` ones on Ktor paths — are caught at the data-source boundary and converted with [`NetErrorClassifier`](#network-error-taxonomy); `catch (e: Exception)` without rethrowing `CancellationException` is a review blocker (`runCatching` in suspend code is banned in favour of `suspendRunCatching`).
4. `@ApplicationScope` carries a `CoroutineExceptionHandler` that logs at ERROR and, in debug builds only (`BuildInfo.debug`, [Debug build type](#debug-build-type)), rethrows on the main thread to crash fast. Release builds never crash on a logged error.

### Coroutines and threading

```kotlin
// :core:common (Metro qualifiers; provided by the shell graphs)
@Qualifier annotation class Dispatcher(val dispatcher: NeutrodyneDispatchers)
enum class NeutrodyneDispatchers { IO, Default }
@Qualifier annotation class ApplicationScope

interface Clock {
    fun now(): Long                // epoch milliseconds UTC (wall clock; may jump)
    fun elapsedRealtime(): Long    // monotonic milliseconds (timers, durations); since boot on Android, since an arbitrary origin on the desktop
}
```

| Work | Runs on | Rule |
|---|---|---|
| Compose, ViewModel state | Main (`viewModelScope` = `Dispatchers.Main.immediate`: Android main looper; desktop Swing EDT via `kotlinx-coroutines-swing`) | Never block; repositories are main-safe |
| Room DAO calls | Room's query context, `setQueryCoroutineContext(Dispatchers.IO)` on both platforms ([02](02-data-model.md#conventions)) | `suspend`/`Flow` DAOs only |
| HTTP | Ktor calls are suspending and cancellable on the OkHttp engine; direct OkHttp users (Media3, Coil, the desktop engine's `HttpByteSource`, `PyHttp`) use `okhttp-coroutines` `executeAsync()` or their library's threads; body streaming inside `withContext(IO)` | One client family ([Networking](#networking-baseline)) |
| CPU-bound work (JSON decode, hashing, colour extraction, diffing in memory, sync merges) | `@Dispatcher(Default)` | Parse-from-file stays on IO (blocking reads); owning docs set parallelism (`limitedParallelism`) |
| Media3 player and session (Android) | the player's application looper, which Neutrodyne makes the main looper by building player and session on the main thread; since 1.11 `MediaSession` getters throw off that looper | [06 UI boundary](06-playback.md#ui-boundary) |
| Desktop audio engine, OS media sessions, engine child I/O | dedicated threads owned by 11 (`nd-playback`, `nd-engine`, the miniaudio device thread, D-Bus workers, stdio readers) | [11 Scope](11-desktop.md#scope); no FFM upcall on the audio thread ([D87](../PLAN.md#3-key-decisions)) |
| `ResolvingDataSource.Resolver`, `ContentProvider.openFile` (Android) | Media3 loader thread / binder thread | Blocking allowed; must use synchronous in-memory lookups; `runBlocking` permitted only here, with a timeout |
| Workers (Android) and desktop lanes | `CoroutineWorker.doWork()` (`Dispatchers.Default`); `JobLane.run()` in `@ApplicationScope` on `Dispatchers.Default`; both switch to IO for blocking I/O | Resumable, idempotent; Android soft deadlines per owning doc; lanes have none ([11 Background work](11-desktop.md#background-work)) |
| `BroadcastReceiver` (Android) | `goAsync()` + launch in `@ApplicationScope`, `finish()` within 10 s | Never start playback from a receiver except the media-button path ([D43](../PLAN.md#3-key-decisions)) |

Time: production code never calls `System.currentTimeMillis()`, `System.nanoTime()` or `SystemClock` directly; it injects `Clock` (`DeviceClock` in `:app`, `DesktopClock` in `:desktopApp`, `TestClock` in `:core:testing`) or uses `kotlin.time`'s monotonic `TimeSource` for durations inside one process. Wire formats use a root locale; dates in storage are epoch ms UTC.

### Logging and redaction

```kotlin
// :core:common
object Log {
    fun install(vararg sinks: LogSink)
    fun d(tag: String, msg: () -> String)
    fun i(tag: String, msg: () -> String)
    fun w(tag: String, t: Throwable? = null, msg: () -> String)
    fun e(tag: String, t: Throwable? = null, msg: () -> String)
}
fun interface LogSink { fun log(level: LogLevel, tag: String, message: String, t: Throwable?) }
object Redactor {
    fun url(raw: String): String          // redact one URL
    fun text(s: String): String           // find every http(s)/feed-like URL in free text and redact it
}
```

Every message passes through `Redactor.text` inside `Log` before reaching a sink, so a forgotten `url.redacted()` is still safe. **`Redactor.url` algorithm** (pure Kotlin in `commonMain`: a small RFC 3986 splitter with a lenient fallback regex; no `java.net.URI`):

1. If unparsable, return `"<unparsable url, N chars>"`.
2. Replace user-info with `***@` (`https://user:pass@host/` → `https://***@host/`).
3. Keep scheme, host and port verbatim (needed for diagnostics).
4. For each path segment: keep it if it is shorter than 15 characters or contains no digit; otherwise replace with `…` + last 2 characters (`/rss/a8F3kq09ZpLm2xQ` → `/rss/…xQ`). Token-shaped segments (Supercast, Patreon) are thereby masked while `/feed/podcast` survives.
5. Keep query parameter **names**, replace every value with `…` (`?auth=abc&id=7` → `?auth=…&id=…`).
6. Drop the fragment.

**Sinks.** Android: release builds (and `benchmarkRelease`) install `LogcatSink(WARN)`, debug builds `LogcatSink(DEBUG)`. Desktop: `RollingFileSink` (`:desktopApp`) writes to the logs directory of `AppDirs` at INFO (DEBUG when `BuildInfo.debug`), with the rotation and size limits of [11 Desktop diagnostics and crash files](11-desktop.md#desktop-diagnostics-and-crash-files), and `ConsoleSink` only under `:desktopApp:run`. M11b adds a 500-entry in-memory `RingBufferLogSink` for the diagnostics screen on both apps ([09](09-quality-and-release.md#crash-reporting-and-diagnostics)). Never log response bodies, `Authorization`/`Cookie` values, credentials, sync tokens, link or invite codes, or API keys. No `HttpLoggingInterceptor`; Android debug builds get `DebugHttpLogInterceptor` (method, redacted URL, status, duration) from `app/src/debug/` through the island's `@DebugInterceptors` multibinding ([Debug build type](#debug-build-type)), which is empty in every other build and on the desktop. The server has its own logging rules (slf4j-simple, no tokens, URLs or payloads): [10 Security](10-sync.md#security).

### Application start-up

```kotlin
// :core:common
interface AppInitializer {
    val order: Int                  // band, see below; equal orders allowed
    suspend fun run()               // idempotent; runs once per process in @ApplicationScope after the graph exists
}
```

The **bands** below are shared by both apps; the Android process model and the Android sequence follow, then the desktop sequence (detail: [11 Desktop shell](11-desktop.md#desktop-shell)).

**Processes on Android** ([D73](../PLAN.md#3-key-decisions)): the app runs in up to three processes, and `NeutrodyneApplication` decides per process what to start.

| Process | Name (release) | Started by | Runs |
|---|---|---|---|
| main | `ch.lkmc.neutrodyne` | launcher, notifications, services, receivers, jobs, WorkManager | everything below: ACRA, logging, the `AndroidAppGraph`, initializers, database, DataStore, WorkManager, playback, downloads, sync, the update check |
| `:ytx` | `ch.lkmc.neutrodyne:ytx` | `YtDlpClient` binding `YtxService` (`android:process=":ytx"`; from M0a while S7 is go, answering only `ping` and `selftest` and bound only by the smoke test; the engine methods and `YtDlpClient` from M9a; 04 owns its lifecycle) | `Log` only, then the service from the small `YtxGraph`: one CPython interpreter, `PyHttp`. **No** `AndroidAppGraph`, no `AppInitializer`, no Room database, no DataStore (DataStore is single-process; the main process passes locale, User-Agent and IP family in each call), no WorkManager, no ACRA (engine crashes are recorded by the main process as engine health, [D62](../PLAN.md#3-key-decisions)). Content providers declared without `android:process` (`ArtworkProvider`, `FileProvider`, `androidx.startup`, ProfileInstaller's) are instantiated only in the main process |
| `:acra` | `ch.lkmc.neutrodyne:acra` | ACRA's sender service after a crash (09) | ACRA's dialog and mail sender only |

Debug builds' processes carry the `ch.lkmc.neutrodyne.debug` prefix (`ch.lkmc.neutrodyne.debug:ytx`, `…debug:acra`); `benchmarkRelease` and `nonMinifiedRelease` use the release names. `ProcessRole` (`:app`) classifies the current process once, before any graph is created: `Application.getProcessName()` on API 28+, the first NUL-terminated token of `/proc/self/cmdline` on API 26–27; a name ending in `:ytx` is `YTX`, one ending in `:acra` is `ACRA` (the process `ACRA.isACRASenderServiceProcess()` reports; matching the name avoids calling ACRA before it is installed), anything else is `MAIN`.

`NeutrodyneApplication` (main process) runs every `AppInitializer` of the graph's `Set<AppInitializer>` multibinding sequentially, sorted by `order` and then by fully qualified class name (deterministic ties), on `@Dispatcher(Default)`; each is wrapped in `suspendRunCatching` and a failure is logged without stopping the rest. The shared implementation is `runInitializers(initializers)` in `:core:common` — a plain suspend function both shells invoke on `@Dispatcher(Default)` (2026-10-06). Initializers receive database-backed dependencies lazily ([Dependency injection](#components-and-scopes) rule 7), because the whole set is constructed before the first one runs. The desktop runs the same set from `DesktopAppGraph` with the same bands. Bands:

| Band | Meaning | Hard rule |
|---|---|---|
| 0–99 | platform (channels, caches, OS registrations) | must not touch the database, DAOs or repositories: the database opens at 100 and initializers run sequentially, so a blocking `requireDatabase()` here would wait for an open that never starts |
| 100–199 | data (open, restore, in-memory mirrors, secrets) | 100 is the database open; everything else ≥ 101 may use the database |
| 200–299 | scheduling: WorkManager enqueues (Android), `DesktopJobRunner.start()` (desktop) | enqueue or start only; no network |
| 300+ | warm-ups and housekeeping | nothing on the cold-start path waits for them |

Registrations known today (each owning document defines its initializer; this table is the index; desktop-only initializers are 11's and listed in [11 Desktop shell](11-desktop.md#desktop-shell)):

| Order | Initializer | Module | Owner | From |
|---|---|---|---|---|
| 0 | `DebugToolsInitializer`, **Android debug builds only** (compiled from `app/src/debug/`, absent from every release APK): StrictMode VM policy and, on the main thread (`withContext(Dispatchers.Main)`), the thread policy, both with death penalties; LeakCanary configuration, with heap dumps off while the system property `neutrodyne.instrumentedTest` is set ([09 Gradle Managed Devices](09-quality-and-release.md#gradle-managed-devices)). The main thread's policy is therefore installed shortly after `Application.onCreate`, not before it (acceptable for a development aid) | `:app` (`app/src/debug/`) | 01 | M0a |
| 10 | Static notification channels and the `grp_new_episodes` group (Android): `playback`, `alerts` (06 `PlaybackChannels`), `downloads`, `download_errors` (07), `new_episodes`, `import_backup` (03, 05), `updates` (09, "App updates", LOW; declared by the update check in `:core:data`); posting code also calls the idempotent `ensureChannels()` | `:playback:impl`, `:download:impl`, `:core:data` (`androidMain`) | 06, 07, 03, 05, 09 | M2–M6, M11a (`updates`) |
| 20 | Mirror `privacy.crash_reports` into ACRA's `acra` SharedPreferences (`acra.enable`) and on every change (Android, [09 Settings](09-quality-and-release.md#settings)) | `:app` | 09 | M0a |
| 100 | `DatabaseOpener.awaitOpen()` on IO: opens the database, runs migrations or recovery, fires Room `onCreate` on a fresh install ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)) | `:core:database` | 02 | M1a |
| 110 | `FirstLaunchRestoreInitializer` (Android: fresh DB + `files/backup/auto-snapshot.zip`) | `:core:data` (`androidMain`) | 05 | M3 |
| 120 | `SecretStore.awaitLoaded()` (Android `KeystoreCredentialStore`, desktop `DesktopSecretStore`: decrypt credentials and the sync token into memory) | `:core:data` | 03, 10 | M1b |
| 130 | `LocalMediaIndex` initial load | `:download:impl` | 07 | M6a |
| 140 | Per-group `new_episodes_{groupUuid}` channel sync (Android; reads groups, so it cannot run at 10) | `:core:data` (`androidMain`) | 05 | M2 |
| 150 | `YtDlpEngine` capability load: `youtube.engine_enabled` and the start-failure count from DataStore, the engine store's `active.json`; publishes `YouTubeCapabilitiesSource` and `EngineStatus` (starts no engine process) | `:youtube:engine` (bound by `:youtube:ytdlp` and `:youtube:ytdlp-desktop`) | 04, 11 | M9a, MD3 |
| 200 | Android: unique periodic work `refresh-periodic` (03), `backup-auto-snapshot` (05; the same initializer starts 05's library watcher on `BackupDao.observeLibraryShape()`), `download-cleanup` (07), `db-maintenance` (02), `engine-update` (04; only with the engine bundled and its policy not Off), `app-update-check` (09; only while `updates.check_enabled` is on and never in debug builds; the first enqueue carries a 24 h initial delay so the first-run card comes first), `sync-periodic` (10; only while linked), all `UPDATE`. Desktop: `DesktopJobRunner.start()` with the lanes of [11 Background work](11-desktop.md#background-work) | owning modules | 03, 05, 07, 02, 04, 09, 10, 11 | M1a, M3, M6, M9b, M11a, MS2 |
| 210 | `download-reconcile` one-time `KEEP` (07); `engine-prepare` one-time `KEEP` with a 30 s initial delay, only with the engine bundled and its bundled version not yet compiled for this app version (04) | `:download:impl`, `:youtube:ytdlp` | 07, 04 | M6a, M9a |
| 220 | `RefreshForegroundObserver` added to `ProcessLifecycleOwner` on the main thread (Android, [03 Triggers](03-feeds-and-discovery.md#triggers)) | `:core:data` (`androidMain`) | 03 | M1a |
| 300 | `PlaybackPrefs` warm-up and `PlayerConnection` registration (06), `ExportFilesCleaner` (05), one `artwork-sync` request per process (08); in the emergency no-engine builds only, `AbsentYouTubeEngine`'s one-time removal of leftover engine files (04, [Emergency build without the engine](#emergency-build-without-the-engine)) | owning modules; `app/src/noYouTubeEngine/`, `desktopApp/src/noYouTubeEngine/` | 06, 05, 08, 04 | M3, M4, M9a |
| 310 | `:download:impl` collectors on `@ApplicationScope` and, on Android, its `ProcessLifecycleOwner` observer ([07 Start-up hooks](07-downloads.md#start-up-hooks)) | `:download:impl` | 07 | M6a |

```mermaid
sequenceDiagram
  participant Z as System
  participant A as NeutrodyneApplication
  participant P as ArtworkProvider
  participant G as AndroidAppGraph (Metro)
  participant S as ApplicationScope
  participant M as MainActivity
  Z->>A: attachBaseContext
  A->>A: ProcessRole, then install ACRA if ACRA_MAILTO is set, the role is not YTX and neutrodyne.instrumentedTest is not set
  Z->>P: ContentProvider.onCreate (main process only, no graph access here)
  Z->>A: onCreate
  alt ACRA sender process
    A-->>Z: return immediately
  else ytx process (YouTube engine)
    A->>A: Log.install, create YtxGraph, then return (no initializers, database, DataStore, WorkManager or ACRA)
  else main process
    A->>A: Log.install
    A->>G: create the graph (lazily, on first access)
    A->>S: launch initializers in order
    S->>S: 0 debug tools (debug builds only), 10 channels, 100 database open, 110 to 150 data, 200 to 220 work, 300 warm-ups
  end
  Z->>M: onCreate (launcher, notification or deep link)
  M->>M: graph.inject(this), installSplashScreen, keep until device_settings loaded and database open, at most 400 ms
  M->>M: enableEdgeToEdge, route intent, setContent
  M->>M: StartupGate shown until database open, then NavDisplay
```

```kotlin
class NeutrodyneApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory, GraphHolder {
    private lateinit var role: ProcessRole
    // Created on first access, so a ContentProvider.openFile or an early WorkManager call before onCreate still works.
    override val graph: AndroidAppGraph by lazy {
        check(role == ProcessRole.MAIN) { "AndroidAppGraph exists only in the main process (D73)" }
        createGraphFactory<AndroidAppGraph.Factory>().create(application = this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        role = ProcessRole.current(this)                                   // getProcessName() on 28+, /proc/self/cmdline on 26–27
        if (role != ProcessRole.YTX && BuildConfig.ACRA_MAILTO.isNotEmpty() &&
            System.getProperty("neutrodyne.instrumentedTest") == null) installAcra(this)   // config owned by 09; never in :ytx (D62)
        // the property is set only by :app's NeutrodyneTestRunner (09 Gradle Managed Devices): no ACRA in instrumented tests
    }
    override fun onCreate() {
        super.onCreate()
        if (role == ProcessRole.ACRA) return                               // :acra process: no graph, no WorkManager, no session
        Log.install(LogcatSink(if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN))
        if (role == ProcessRole.YTX) {                                     // :ytx: YtxService alone, no initializers (D73)
            ytxGraph = createGraphFactory<YtxGraph.Factory>().create(application = this); return
        }
        graph.appScope.launch { runInitializers(graph.initializers) }      // debug builds: DebugToolsInitializer (0) sets StrictMode
    }
    lateinit var ytxGraph: YtxGraph; private set
    override val workManagerConfiguration: Configuration get() = Configuration.Builder()
        .setWorkerFactory(graph.workerFactory)                             // MetroWorkerFactory over the multibound worker map
        .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
        .build()
    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoaderFactory.create(context)
}
```

`BuildConfig.DEBUG` is read in `:app` only, to build `BuildInfo.debug` and the two logging levels above; every other module reads `BuildInfo.debug`.

**Splash and start-up gate.** `StartupViewModel` (`:app`; the desktop window's equivalent is 11's) exposes `StartupState(deviceSettingsLoaded, settingsLoaded, database: Pending | Ready | Recovered(cause) | Failed(reason: DatabaseOpenException.Reason))`, fed by the first `DeviceSettingsStore` emission (the persisted tab and group selection needed for the first frame), the first `settings` emission (theme, dynamic colour) and `DatabaseOpener.awaitOpen()` ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)).

1. `installSplashScreen().setKeepOnScreenCondition { elapsed < 1_000 ms && (!state.deviceSettingsLoaded || !state.settingsLoaded || (state.database == Pending && elapsed < 400 ms)) }`: the system splash covers the normal case (opening an up-to-date database takes milliseconds), stays at most 400 ms for the database (cold-start budget N5, [09 Performance budgets](09-quality-and-release.md#performance-budgets)) and at most 1 s in total; if `device_settings` or `settings` has not emitted by then, the first frame uses the keys' defaults (waiting for `settings` avoids a light→dark theme flash) (DataStore corruption is already handled by the corruption handler, so this only guards a stalled disk).
2. The activity's content renders `StartupGate` (visuals: [08 Banners and the startup gate](08-ui-ux.md#banners-and-the-startup-gate)) **instead of the whole `NeutrodyneRoot`** (scaffold, `NavDisplay` and `PlayerSheet`) while `database == Pending`, so **no ViewModel — feature or the activity-scoped `PlayerViewModel` — and therefore no repository is constructed before the database is open** (02's `requireDatabase()` throws on the main thread before that). A long migration shows "Updating your library…" there. `Recovered(cause)` opens the gate and shows 02's recovery message once; `Failed(reason)` keeps the gate with 08's error variant for that reason; "Try again" calls `StartupViewModel.retry()`, which sets `Pending` and runs `awaitOpen()` again (02 does not cache a failed result; an activity restart would keep the retained ViewModel's failed state).
3. A [route](#intent-routing) that arrives while the gate is shown is applied to `NavigationState` immediately (it is plain saveable state); its entries compose, and their ViewModels are created, only after the gate opens.
4. Nothing in start-up waits for the network or a snapshot restore; restore progress is shown by 05's flow after the gate opens.
5. Before M1 (no database) `database` starts as `Ready`.

**Framework components constructed on the main thread before the gate (Android).** The start-up gate protects UI only. Services, receivers and job services (`NeutrodynePlaybackService`, `ManualDownloadJobService`, `DownloadActionReceiver`, `YouTubeAlertActionReceiver`, `SnapshotNowReceiver`) can be created by the system right after `Application.onCreate` — for example the resumption card after a reboot — while the database is still opening, and member injection (`graph.inject(this)`) runs on the main thread, where 02's `requireDatabase()` throws. Rule: such classes, and every class they inject eagerly (for example the `MediaLibrarySession` callback), receive anything that reaches `NeutrodyneDatabase` (DAOs, repositories, `EpisodeResolver`, controllers) as `Provider<…>` (Metro has no `Lazy`, [Dependency injection](#components-and-scopes) rule 1) and dereference it only inside a coroutine on IO after `DatabaseOpener.awaitOpen()`; constructors of repositories and DAOs never touch the database. Session and player creation in `Service.onCreate` therefore needs no database. 06, 07, 04, 05 and 10 apply this rule to their components (05's `SnapshotNowReceiver` receives `BackupRepository` lazily; the update check has no framework component: its work runs in `UpdateCheckWorker`); the [Testing](#testing) start-up test enforces it. `YtxService` is the stricter case: it runs in `:ytx`, is injected from `YtxGraph` and never reaches the database at all ([process model](#application-start-up)).

**Process model (Android):** the main process, `:ytx` and ACRA's `:acra` ([Processes](#application-start-up) table above). Rules for `:ytx`: only `:youtube:ytdlp` code (and the `:youtube:engine` classes it hosts) runs there (`YtxService` and the `…youtube.ytdlp.ytx` subpackage); `YtxGraph` binds nothing database-, DataStore- or credential-backed (it reaches the island's credential-free `YOUTUBE` client, `BuildInfo` and `Clock` only, [Networking baseline](#one-client-family), [Components and scopes](#components-and-scopes)); `:ytx` dies with the engine (idle stop, kill on hang, Python crash) without affecting playback in the main process ([04 YouTube engine](04-youtube.md#youtube-engine)). `YtxProcessStartTest` ([Testing](#testing)) enforces these rules. No component is `directBootAware`; media-button events before first unlock are ignored by the platform, which is acceptable.

**Desktop start-up** ([D85](../PLAN.md#3-key-decisions); sequence, handshake and failure modes owned by [11 Desktop shell](11-desktop.md#desktop-shell)). One JVM process per user: `MainKt.main` → `AppDirs.resolve()` (no I/O) → `SingleInstanceLock` (a second launch hands its arguments to the running instance and exits before AWT starts) → directories, file logging, `DesktopCrashReporter` → the database open on IO (`DesktopDatabaseFactory`, `<data>/neutrodyne.db`, bundled driver) while `createGraphFactory<DesktopAppGraph.Factory>()` builds the graph and bands 0–199 run → the window (`NeutrodyneWindow` on the EDT, gated like Android's `StartupGate` until the database is open) → band 200 starts `DesktopJobRunner`, band 300 restores the last session paused. Room has no multi-instance invalidation off Android, which is why the lock comes before the database ([Room KMP](https://developer.android.com/kotlin/multiplatform/room)). **Smoke mode** (`-Dneutrodyne.smoke=true`, the only test entry point in a desktop image): CI starts the packaged app with it under Xvfb or on the runner's desktop session; it uses a temporary `AppDirs` root, opens the database, renders the five destinations, checks that the native libraries load, writes a result file and exits 0 — never touching the user's data, the network or an OS registration (11).

### DataStore files and typed setting keys

Owns the conventions of [D35](../PLAN.md#3-key-decisions); each document owns its keys in its Settings table; sync classification per [D93](../PLAN.md#3-key-decisions) and [10 What syncs](10-sync.md#what-syncs).

| File | Android path | Desktop path | Backed up | Content |
|---|---|---|---|---|
| `settings` | `filesDir/datastore/settings.preferences_pb` | `<config>/settings.preferences_pb` ([11 Desktop shell](11-desktop.md#desktop-shell) `AppDirs`) | Android: yes (Auto Backup include rule); both: backup ZIP `settings.json` | portable preferences; keys with `synced = true` also travel through sync |
| `device_settings` | `filesDir/datastore/device_settings.preferences_pb` | `<config>/device_settings.preferences_pb` | never | SAF grants, volume UUIDs, prompt flags, selected tab/group, onboarding flags, `sync.*` device keys, `desktop.*` keys |

```kotlin
// :core:model (package ch.lkmc.neutrodyne.core.model.settings)
enum class SettingsFile { PORTABLE, DEVICE }            // PORTABLE -> "settings", DEVICE -> "device_settings"
sealed class SettingKey<T : Any>(val name: String, val default: T, val file: SettingsFile, val synced: Boolean) {
    class Bool(name: String, default: Boolean, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) : SettingKey<Boolean>(name, default, file, synced)
    class Int32(name: String, default: Int, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) : SettingKey<Int>(name, default, file, synced)
    class Int64(name: String, default: Long, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) : SettingKey<Long>(name, default, file, synced)
    class Float32(name: String, default: Float, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) : SettingKey<Float>(name, default, file, synced)
    class Text(name: String, default: String, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) : SettingKey<String>(name, default, file, synced)
    class TextSet(name: String, default: Set<String>, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) : SettingKey<Set<String>>(name, default, file, synced)
    class Choice<E : Enum<E>>(name: String, default: E, val values: List<E>, file: SettingsFile = SettingsFile.PORTABLE, synced: Boolean = false) :
        SettingKey<E>(name, default, file, synced)        // stored as E.name; unknown stored name -> default
}
// Each area declares its keys as an object in this same package (:core:model, so features, :core:data and impl modules
// all see them), e.g. `object PlaybackSettingKeys { val SKIP_BACK_MS = SettingKey.Int64("playback.skip_back_ms", 10_000, synced = true); val ALL = listOf(...) }`,
// and AllSettingKeys.list concatenates every area's ALL (one registry; used by 05's backup whitelist, 10's settings capture
// and the uniqueness test).

// :core:domain
interface SettingsRepository {
    fun <T : Any> observe(key: SettingKey<T>): Flow<T>
    suspend fun <T : Any> get(key: SettingKey<T>): T
    suspend fun <T : Any> set(key: SettingKey<T>, value: T): Outcome<Unit, SettingsError>
    suspend fun reset(key: SettingKey<*>)
    fun observePortableSnapshot(): Flow<Map<String, Any>>   // backup export (05)
}
sealed interface SettingsError {
    data object WriteFailed : SettingsError                          // IOException from DataStore (disk full, I/O error); logged at WARN
    data class OutOfRange(val key: String) : SettingsError           // value rejected by the key's own validator (e.g. Choice not in values)
}
```

Rules:

1. Key names match `^(appearance|feeds|discover|groups|playback|downloads|youtube|updates|backup|privacy|diagnostics|ui|sync|desktop)\.[a-z0-9_]+$` (`updates.*`: 09's update-check keys, M11a; `sync.*`: 10's keys, MS2; `desktop.*`: 11's keys, M0b); `ui.*` and `desktop.*` keys must be `DEVICE`; `synced = true` is allowed only on `PORTABLE` keys, never on `ui.*`, `desktop.*`, `updates.*`, `downloads.*` or `sync.*` keys (the synced and device-local lists: [10 What syncs](10-sync.md#what-syncs)); `sync.server_url` and `sync.username` are `PORTABLE` and not synced; `sync.device_name`, `sync.sync_playback_settings` and `sync.share_feed_passwords` are `DEVICE` (10 owns them); `desktop.close_behaviour`, `desktop.start_at_login`, `desktop.downloads_dir`, `desktop.window_bounds` and `desktop.language` are `DEVICE` (11 owns them); the desktop reads the same `updates.*` keys as Android (09). A unit test in `commonTest` iterates `AllSettingKeys.list` and asserts the pattern, uniqueness, the `DEVICE` prefixes and the `synced` rule.
2. Keys are never renamed or re-typed; a replacement key gets a new name and a `DataMigration` in the store copies the value once. Turning `synced` on for an existing key is allowed (the next push carries the current value with a fresh clock, 10); turning it off stops capture only.
3. Exactly one `DataStore` per file per process: singletons in `:core:datastore` qualified `@SettingsDataStore(SettingsFile.PORTABLE|DEVICE)`, created with `PreferenceDataStoreFactory.createWithPath(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = appScope + IO, produceFile = { Path(storagePaths.dataStoreFile(name)) })` ([DataStore KMP](https://developer.android.com/kotlin/multiplatform/datastore)) — `StoragePaths.dataStoreFile` returns an absolute `String` path (Okio is a `:core:datastore` dependency, not `:core:common`'s; 2026-10-06), and `name` is `SettingsFile.storeName`. Corruption resets that file to defaults and logs WARN. DataStore is single-process: on Android only the main process opens either file (`:ytx` never injects a store and receives the values it needs with each call, [D73](../PLAN.md#3-key-decisions)); on the desktop the single-instance lock guarantees one process ([D85](../PLAN.md#3-key-decisions)).
4. `SettingsStore` (portable) and `DeviceSettingsStore` (device) wrap the two files; `SettingsRepository`'s implementation in `:core:data` routes by `key.file`. Implementation modules may inject the stores directly. Writes of `synced` keys are captured for sync by 10's `SettingsCapture` through `SettingsRepository`, never by DataStore observers ([10 Client sync engine](10-sync.md#client-sync-engine)).
5. Secrets never go to DataStore; they go through `SecretStore` (`:core:domain`): `KeystoreCredentialStore` (Android: the `credential` table encrypted with an Android Keystore key, [03](03-feeds-and-discovery.md#feed-moves-auth-and-paging)) and `DesktopSecretStore` (DPAPI on Windows, a `0600` file elsewhere, [PO-44](../PLAN.md#48-further-product-owner-decisions)); the sync token uses the origin `sync:<host>` (10). Fresh-install detection uses Room's `onCreate` callback, never a DataStore flag ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)).

**M0a step 12 delivery notes (2026-10-06).** The store factory is `SettingsDataStoreFactory` (`:core:datastore`, the `@Inject` class behind the two qualified `DataStore` providers). The two stores implement a public `SettingStore` interface — added beyond the two names above — so `:core:data`'s `DataStoreSettingsRepository` routes by `key.file` over one type and implementation modules inject either store directly; each store rejects keys declaring the other file, and reads never throw (absent, corrupt, wrong-typed or unknown-`Choice` values read the key's default). The `SettingsRepository` implementation itself (`DataStoreSettingsRepository`: `OutOfRange` from the key's own `Choice` values, `WriteFailed` around `suspendRunCatching` with a WARN log) moved up from M1a into M0a step 12; 10's `SettingsSyncPort` still arrives with MS2. `FakeSettingsRepository` and the shared `SettingsRepositoryContract` (run against both the fake and the DataStore implementation) are in `:core:testing` per 09's inventory.

**Sync write ordering (review 2026-10-06, MS2).** A `SettingsSyncPort` in `:core:domain`, implemented beside `SettingsRepository` in `:core:data`, supplies `localSyncedChanges`, `applyRemote` and registration of 10's suspending local-intent recorder. `set()` and `reset()` of a synced key share a per-key mutex with remote apply. While linked with settings sync on, record the validated literal value and clock in Room before the DataStore write; if recording fails, DataStore stays unchanged. A DataStore failure after that durable intent leaves it pending for recovery. The stream only wakes scheduling; it is not the durability boundary. At startup 10 completes pending remote and local intents before hash reconciliation ([10 Settings capture](10-sync.md#settings-capture), [10 Durable apply effects](10-sync.md#durable-apply-effects)). Stores in `:core:datastore` remain independent of Room and sync implementations; the repository and port coordinate them through domain contracts. Unlinked writes have no recorder or sync-table cost.

---

## Dependency injection

Serves N11. Delivered in M0a (graph skeletons; [S8](#s8-metro-across-kmp-modules) first), extended per milestone. Honours [D8](../PLAN.md#3-key-decisions), [D82](../PLAN.md#3-key-decisions). Metro 1.4.5 is a compile-time DI compiler plugin with Dagger's model — graphs, `@Binds`/`@Provides`, multibindings, assisted injection — and Anvil-style aggregation (`@ContributesTo`, `@ContributesBinding`, `@ContributesIntoSet`, `@ContributesIntoMap`) on every Kotlin target ([Metro](https://zacsweers.github.io/metro/latest/), [dependency graphs](https://zacsweers.github.io/metro/latest/dependency-graphs/), [aggregation](https://zacsweers.github.io/metro/latest/aggregation/)). If S8 fails, Koin 4.2.2 with its compiler plugin replaces it and D82 is amended. **S8 ran 2026-10-06 and is go**; every "Unverified (S8)" marker this chapter carried is resolved below, and the `neutrodyne.metro` convention plugin sets `metro { generateContributionProviders.set(true) }` so implementation classes can stay `internal` (S8 question 10).

### Components and scopes

There is one application scope, `AppScope`, and one graph per process: `AndroidAppGraph` (`:app`, Android main process), `YtxGraph` (`:app`, Android `:ytx` process, `@DependencyGraph(YtxScope::class)`: it aggregates only `YtxScope` contributions — `:youtube:ytdlp`'s `ytx` subpackage, the `:youtube:engine` classes the Binder side needs, and the island's `CoreClients` (credential-free core, `YOUTUBE` client), which the island contributes to both scopes — so no database, DataStore or credential binding exists in `:ytx` at all), `DesktopAppGraph` (`:desktopApp`, [11 Desktop shell](11-desktop.md#desktop-shell)). The server uses plain constructor wiring ([D82](../PLAN.md#3-key-decisions)). Singletons are `@SingleIn(AppScope::class)`; unscoped bindings are created per injection. `AppScope` and `YtxScope` are `@Scope` annotation classes declared in `:core:common`'s `commonMain` (S8, 2026-10-06): every feature, island and both shells must see them, and the island cannot see `:app`. One class cannot be `@SingleIn` two scopes (`[Metro/IncompatiblyScopedBindings]`); an implementation both scopes need — the island's `CoreClients` — is unscoped and bound once per scope through contributed `@Provides @SingleIn(…)` binding containers (per-process singletons, S8 question 6; the providers construct `CoreClients` from its dependencies, because an explicit `@Provides` shadows the `@Inject` constructor and a `CoreClients` parameter would resolve to the provider itself — `[Metro/DependencyCycle]`, found by S12 2026-10-06).

```kotlin
// :app
@DependencyGraph(AppScope::class, bindingContainers = [YouTubeBindingsModule::class])   // the YouTube bindings are included explicitly
interface AndroidAppGraph : ArtworkProviderGraph, ViewModelGraph {   // ArtworkProviderGraph (:core:artwork androidMain), ViewModelGraph (metrox)
    val appScope: CoroutineScope                                       // @ApplicationScope
    val initializers: Set<AppInitializer>
    val workerFactory: WorkerFactory
    val imageLoaderFactory: NeutrodyneImageLoaderFactory
    fun inject(target: MainActivity)                                   // member injection: framework-instantiated classes of :app
    fun inject(target: ExternalImportActivity)                         // (rule 1); library modules contribute injector interfaces
    fun inject(target: SnapshotNowReceiver)
    @DependencyGraph.Factory fun interface Factory { fun create(@Provides application: Application): AndroidAppGraph }
}
// :desktopApp (11 lists its contents)
@DependencyGraph(AppScope::class, bindingContainers = [DesktopYouTubeBindingsModule::class])
interface DesktopAppGraph : ViewModelGraph {
    val appScope: CoroutineScope; val initializers: Set<AppInitializer>; val jobRunner: DesktopJobRunner
    @DependencyGraph.Factory fun interface Factory { fun create(@Provides dirs: AppDirs, @Provides buildInfo: BuildInfo): DesktopAppGraph }
}
```

`inject` functions are declared in the graph interface for each framework class; Android framework components that live in library modules (`:playback:impl`, `:download:impl`, `:core:data`'s `androidMain`, `:youtube:ytdlp`) declare a contributed injector interface (`@ContributesTo(AppScope::class) interface PlaybackServiceInjector { fun inject(target: NeutrodynePlaybackService) }`) and call it through `(applicationContext as GraphHolder).graph as PlaybackServiceInjector`; `GraphHolder` (`:core:common` `androidMain`) is the one cast every component uses. S8 (2026-10-06) confirmed both spellings: member-injection functions on contributed interfaces merge into the graph as supertypes exactly as written, and `@DependencyGraph(…, bindingContainers = [X::class])` is the correct parameter name.

| Binding | Declared in | Graph | Scope |
|---|---|---|---|
| `@Dispatcher(IO)`, `@Dispatcher(Default)`, `@ApplicationScope CoroutineScope` (`SupervisorJob() + Default + handler`), `Clock` → `DeviceClock` / `DesktopClock`, `BuildInfo`, `PlatformInfo`, `StoragePaths` | `:app` `CoreBindings`, `:desktopApp` `DesktopCoreBindings` | both | `@SingleIn(AppScope)` |
| `CoreClients` (credential-free core, `YOUTUBE`, `SYNC`), `NetworkClients` (the rest of the OkHttp family), `UserAgentInterceptor`, `AuthInterceptor`, `DnsFamilyHints`, `LocalNetworkGuard` | `:core:network:okhttp` (contributed) | both; `YtxGraph`: `CoreClients` and its inputs only (contributed to `YtxScope` too) | `@SingleIn(AppScope)` |
| `NeutrodyneHttpClients`, `NetErrorClassifier`, `NetworkMonitor` → `ConnectivityNetworkMonitor` / `DesktopNetworkMonitor` | `:core:network` (`androidMain`/`desktopMain` contributions) | both | `@SingleIn(AppScope)` |
| `CredentialLookup`, `SecretStore` | M0a: `:core:data` stub binds `CredentialLookup.None`; M1b: `KeystoreCredentialStore` (Android), `DesktopSecretStore` (desktop) | both | `@SingleIn(AppScope)` |
| `LocalNetworkAccess` | `:core:common` (`@Inject` class) | both | `@SingleIn(AppScope)` |
| `SQLiteDriver` | `:core:database` `SqliteDriverBindings` (`BundledSQLiteDriver()`) | both | `@SingleIn(AppScope)` |
| `DatabaseOpener`, `NeutrodyneDatabase` (provided as `opener.requireDatabase()`), DAOs | `:core:database` (`AndroidDatabaseFactory`, `DesktopDatabaseFactory`; [02](02-data-model.md#error-handling-and-recovery)) | both | `@SingleIn(AppScope)`; DAO providers unscoped (Room caches them) |
| `@SettingsDataStore(...) DataStore<Preferences>`, `SettingsStore`, `DeviceSettingsStore` | `:core:datastore` | both | `@SingleIn(AppScope)` |
| `:core:domain` repository and use-case interfaces | `@ContributesBinding` on the implementing class (`:core:data` for most; `:playback:impl` and `:playback:desktop` for `QueueRepository`, `ChapterRepository`, 06) | both | `@SingleIn(AppScope)` |
| `PlaybackController`, `PlaybackStateSource` | `:playback:impl` (`PlaybackControllerImpl`, `PlayerConnection`) / `:playback:desktop` (`DesktopPlaybackController`) | Android / desktop | `@SingleIn(AppScope)` (connection lifecycle: [06 UI boundary](06-playback.md#ui-boundary)) |
| `DownloadController`, `LocalMediaIndex`, `DownloadProgressSource` | `:download:impl` | both | `@SingleIn(AppScope)` |
| `ArtworkStore`, `NeutrodyneImageLoaderFactory` | `:core:artwork` | both | `@SingleIn(AppScope)` |
| `SyncController`, `SyncScheduler` and the rest of 10's client | `:sync:impl` | both | `@SingleIn(AppScope)` |
| `YouTubeUrlClassifier` (pure `@Inject` class), `YouTubeChannelResolver` | `:youtube:api` / `:youtube:impl` | both | `@SingleIn(AppScope)` |
| `YouTubeCapabilitiesSource`, `YouTubeEngine`, `YouTubeStreamResolver`, `YouTubeEnricher`, `YouTubeChannelSearch`, `ExtractorChannelLookup` | `:app`'s `YouTubeBindingsModule`, `:desktopApp`'s `DesktopYouTubeBindingsModule` only ([YouTube bindings](#youtube-bindings)) | each shell | `@SingleIn(AppScope)` |
| `:youtube:engine` internals (`YtDlpClient`, `EngineStore`, …) and the host classes of `:youtube:ytdlp` / `:youtube:ytdlp-desktop` | contributed by those modules (never binding `:youtube:api` interfaces) | Android main and `YtxGraph` (Binder side) / desktop | `@SingleIn(AppScope)` (per process) |
| `AppUpdateChecker`, `UpdateNotices` (interfaces in `:core:domain`) | `:core:data` (M11a), bound in every build; the checker reports `Disabled(DEV_BUILD)` while `BuildInfo.debug` (09) | both | `@SingleIn(AppScope)` |
| `@DebugInterceptors Set<Interceptor>` | `:core:network:okhttp` declares it with `@Multibinds` (empty); only `app/src/debug/` contributes (`DebugHttpLogInterceptor`) | Android | unscoped elements |
| `Set<AppInitializer>` | each owning module, `@ContributesIntoSet(AppScope::class)` | both | unscoped elements |
| `Set<EntryProviderInstaller>` | each feature's contributed binding container ([Feature entry installers](#feature-entry-installers)) | both | unscoped |
| `Set<JobLane>` | desktop lanes, `@ContributesIntoSet(AppScope::class)` in `desktopMain` and desktop modules ([11 Background work](11-desktop.md#background-work)) | desktop | unscoped elements |
| ViewModels | features, `@ContributesIntoMap(AppScope::class)` with `@ViewModelKey`, or an assisted factory with `@ManualViewModelAssistedFactoryKey` (metrox-viewmodel) | both | per Nav entry ([decorator](#viewmodels-per-entry)) |
| Worker factories | `@ContributesIntoMap(AppScope::class)` with a `@WorkerKey(WorkerClass::class)` map key over `(Context, WorkerParameters) -> ListenableWorker`; `MetroWorkerFactory` (`:app`) reads the map | Android | unscoped |

Rules:

1. **Injection sites:** constructor injection (`@Inject` on the class or constructor; Metro also treats `@Contributes*` classes as injectable) everywhere except framework-instantiated Android classes — Activities, Services, Receivers, `JobService`s — which use member injection (`@Inject lateinit var x: Provider<X>` plus `graph.inject(this)` in `onCreate`/`onReceive`) because `metrox-android`'s `AppComponentFactory` needs minSdk 28 and ours is 26 ([MetroX Android](https://zacsweers.github.io/metro/latest/metrox-android/)). Metro has no `Lazy` — `dagger.Lazy` needs the banned dagger interop runtime — so deferred dependencies use `dev.zacsweers.metro.Provider<T>`, dereferenced with `provider()` (S8, 2026-10-06). `ContentProvider`s look the graph up lazily in `openFile`/`query`, never in `onCreate` (it runs before `Application.onCreate`). Workers are created by `MetroWorkerFactory`. The desktop has no framework-instantiated classes: everything hangs off `DesktopAppGraph`.
2. **`NeutrodyneApplication` creates the graph lazily and injects nothing into itself** ([Application start-up](#application-start-up)), so the `:acra` process constructs nothing and `:ytx` builds only `YtxGraph`.
3. **Annotations in common code are Metro's** (`@Inject`, `@Qualifier`, `@SingleIn`, `@Provides`, `@Binds`, `@Contributes*`), which are KMP; `javax.inject` is JVM-only and is not used anywhere. JVM islands use the same annotations.
4. **Cross-module objects of external types** are bound by the implementation module under a qualifier annotation declared in its `:*:api` module. v1.0 has none: 06 keeps every Media3 type out of features ([06 UI boundary](06-playback.md#ui-boundary)); M14 uses this pattern to hand the session `Player` to an Android-only `PlayerSurface` ([06 Video](06-playback.md#video); the qualifier then lives in `:playback:impl`, [D84](../PLAN.md#3-key-decisions)).
5. **Empty multibindings are declared** with `@Multibinds` (`Set<AppInitializer>` in `:core:common`'s contributed declarations, `Set<JobLane>` in `:core:common`'s `desktopMain`, `@DebugInterceptors Set<Interceptor>` in the island), so each graph compiles before any module contributes.
6. **`:youtube:api` interfaces are bound only in the shells' YouTube binding containers** (`YouTubeBindingsModule`, `DesktopYouTubeBindingsModule`), which the graphs include explicitly instead of through aggregation, so exactly one file per shell decides what YouTube can do ([YouTube bindings](#youtube-bindings)). `:youtube:engine`, `:youtube:ytdlp` and `:youtube:ytdlp-desktop` contribute their internal wiring only; Metro's duplicate-binding error catches a module that binds a `:youtube:api` interface itself.
7. **Database-backed dependencies of framework components are lazy** (`Provider<…>`, dereferenced on IO after `DatabaseOpener.awaitOpen()`), per [Application start-up](#application-start-up). The same applies to **every** `AppInitializer` on both platforms: `graph.initializers` constructs the whole set before initializer 100 opens the database, so an eager DAO or repository in any initializer's constructor would block on `requireDatabase()` before the open has started.
8. **Multibound function types** (`EntryProviderInstaller`, the worker factory functions) are ordinary Kotlin types to Metro with two syntax rules S8 confirmed (2026-10-06): the function type needs at least one parameter (receivers count — `EntryProviderScope<NavKey>.() -> Unit` is fine; zero-parameter types like `() -> T` are intrinsic provider types and cannot be bindings), and no class can implement a receiver function type, so every element is a `@Provides @IntoSet`/`@IntoMap` lambda in a binding container. The `@JvmSuppressWildcards` workaround Dagger needed does not apply.
9. **Contributed declarations and visibility** (S8, 2026-10-06): with `generateContributionProviders` on, `internal` `@ContributesBinding`/`@ContributesIntoSet` classes aggregate across modules — implementation classes stay `internal`. `@ContributesIntoMap` classes (the ViewModels) and contributed binding containers must be `public`; an `internal` container contributes nothing and fails silently at runtime.
10. **Platform bindings come from platform source sets.** A shared module contributes its `androidMain` and `desktopMain` implementations with `@ContributesBinding(AppScope::class)` in those source sets; each shell's graph sees only its own platform's contributions because it compiles against that platform's variant.
11. **Graph tests** ([Testing](#testing)): Metro reports missing and duplicate bindings at compile time; one runtime test per shell checks that every canonical `NavKey` has exactly one installer entry, every initializer order lies in a band and (desktop) every lane is bound.

### YouTube bindings

Every `:youtube:api` interface is bound in exactly one binding container per shell ([D2](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions), [D90](../PLAN.md#3-key-decisions)): `YouTubeBindingsModule` in `:app` and `DesktopYouTubeBindingsModule` in `:desktopApp`. There are no flavors: what a device can do with YouTube is a **runtime capability** read from `YouTubeCapabilitiesSource` (shape, reasons and every consumer: [04 Capability matrix](04-youtube.md#capability-matrix)). Until the engine milestone (M9a on Android, MD3 on the desktop) each container lives in its shell's `src/main/`; from then on it exists twice and the [engine switch](#emergency-build-without-the-engine) adds exactly one directory to the shell's Kotlin sources:

| Source directory | Compiled when | Binds |
|---|---|---|
| `app/src/main/kotlin/ch/lkmc/neutrodyne/youtube/` | M2–M8 (before M9a) | external-only implementations, reason `NOT_YET_AVAILABLE` |
| `app/src/youtubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/` | `neutrodyne.youtubeEngine=true` (default), from M9a | engine-backed implementations from `:youtube:engine` hosted by `:youtube:ytdlp` |
| `app/src/noYouTubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/` | `-Pneutrodyne.youtubeEngine=false`, from M9a | external-only implementations, reason `NOT_IN_THIS_APK` |
| `desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/youtube/` | M2–MD3 (before MD3) | external-only implementations, reason `NOT_YET_AVAILABLE` with desktop wording (08; no new enum value) |
| `desktopApp/src/youtubeEngine/kotlin/ch/lkmc/neutrodyne/desktop/youtube/` | default, from MD3 | engine-backed implementations from `:youtube:engine` hosted by `:youtube:ytdlp-desktop` |
| `desktopApp/src/noYouTubeEngine/kotlin/ch/lkmc/neutrodyne/desktop/youtube/` | `-Pneutrodyne.youtubeEngine=false`, from MD3 | external-only implementations, reason `NOT_IN_THIS_APK` (desktop wording, 08) |

```kotlin
// app/src/youtubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/YouTubeBindingsModule.kt   (default build, from M9a;
// desktopApp/src/youtubeEngine/... has the same shape with the stdio host's classes).
// S8 (2026-10-06): @Binds is the extension-receiver property, not Dagger's parameter form, and a
// binding container a graph includes by name must be public (internal containers vanish silently).
@BindingContainer
interface YouTubeBindingsModule {
    @Binds val YtDlpEngine.capabilities: YouTubeCapabilitiesSource   // :youtube:engine; @SingleIn(AppScope), so both
    @Binds val YtDlpEngine.engine: YouTubeEngine                     // bindings share one instance
    @Binds val YtDlpStreamResolver.streamResolver: YouTubeStreamResolver
    @Binds val YtDlpEnricher.enricher: YouTubeEnricher
    @Binds val YtDlpChannelSearch.channelSearch: YouTubeChannelSearch
    @Binds val YtDlpChannelLookup.extractorLookup: ExtractorChannelLookup
}

// app/src/noYouTubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/YouTubeBindingsModule.kt   (-Pneutrodyne.youtubeEngine=false;
// before M9a the same file, minus the YouTubeEngine binding and with reason NOT_YET_AVAILABLE, is app/src/main's)
@BindingContainer
interface YouTubeBindingsModule {
    @Binds val ExternalOnlyYouTubeStreamResolver.streamResolver: YouTubeStreamResolver   // :youtube:impl
    @Binds val NoOpYouTubeEnricher.enricher: YouTubeEnricher
    @Binds val UnsupportedYouTubeChannelSearch.channelSearch: YouTubeChannelSearch
    @Binds val NoExtractorChannelLookup.extractorLookup: ExtractorChannelLookup
    @Binds val AbsentYouTubeEngine.engine: YouTubeEngine                 // status NOT_IN_THIS_APK; prewarm is a no-op
    companion object {
        @Provides @SingleIn(AppScope::class) fun capabilities(): YouTubeCapabilitiesSource =
            StaticYouTubeCapabilitiesSource(ExternalReason.NOT_IN_THIS_APK)  // all five capabilities false
    }
}
```

In the default builds the engine-backed bindings serve **every** APK, every desktop image and every state: `YtDlpEngine` computes capabilities at runtime from `BuildInfo.youTubeEngineBundled` (false on the `armeabi-v7a` APK and in any 32-bit process; true in every default desktop image), `youtube.engine_enabled` and the start-failure count, and reports `NOT_IN_THIS_APK`, `DISABLED_BY_USER` or `ENGINE_FAILED` accordingly; each engine-backed implementation then answers exactly like its external-only counterpart ([04 Capability matrix](04-youtube.md#capability-matrix)). Feature code never reads the ABI, the platform, `BuildConfig` or the build switch — only capabilities. `StaticYouTubeCapabilitiesSource` and `AbsentYouTubeEngine` are the external-only `YouTubeCapabilitiesSource` and `YouTubeEngine` of `:youtube:impl` (names proposed here; 04 owns the classes).

A binding must exist from the milestone of its **first consumer** on both shells, or the graph fails to compile:

| From | Added to both shells' YouTube bindings | First consumer |
|---|---|---|
| M2 | `YouTubeCapabilitiesSource` → `StaticYouTubeCapabilitiesSource(NOT_YET_AVAILABLE)` | 05 `EffectiveSettingsResolver` (auto-download capability) |
| M4 | `YouTubeStreamResolver` → `ExternalOnlyYouTubeStreamResolver` (this one class lands in `:youtube:impl` ahead of the rest of the module) | 06 `EpisodeResolver` YouTube branch (the desktop's `DesktopSourceResolver` from MD1a) |
| M8 | `YouTubeEnricher` → `NoOpYouTubeEnricher`, `YouTubeChannelSearch` → `UnsupportedYouTubeChannelSearch`, `ExtractorChannelLookup` → `NoExtractorChannelLookup` (YouTube texts are ordinary Compose resources in 08's modules, no binding) | 03 YouTube source adapter, 04 channel resolver, 08 YouTube rows and Downloads texts |
| M9a (Android) / MD3 (desktop) | the container moves into the two directories above: engine-backed bindings (`YtDlpEngine` as `YouTubeCapabilitiesSource` and `YouTubeEngine`, `YtDlpStreamResolver`, `YtDlpEnricher`, `YtDlpChannelSearch`, `YtDlpChannelLookup`) and the no-engine set with `AbsentYouTubeEngine` and reason `NOT_IN_THIS_APK` | 06 `QueueProjector` and 11 `DesktopQueueProjector` pre-warm, 08 Settings › YouTube engine rows |

The `:youtube:api` interfaces and `YouTubeCapabilitiesSource` therefore land early (M2/M4), as compiling contracts.

### Test overrides

- `:core:testing` provides hand-written fakes for every `:core:domain`, `:*:api` and `:sync:api` interface ([09 Test infrastructure](09-quality-and-release.md#test-infrastructure)); fakes are used by direct construction in unit tests, so most tests need no graph at all.
- Graph-level replacement uses Metro's test mechanisms: a dynamic graph created from the production graph with test binding containers (`createDynamicGraph<AndroidAppGraph>(FakeBindings)`), or `replaces`/`excludes` on test contributions ([dependency graphs](https://zacsweers.github.io/metro/latest/dependency-graphs/), [aggregation](https://zacsweers.github.io/metro/latest/aggregation/)). They live in `:app/src/test`, `:app/src/androidTest` and `:desktopApp/src/test`, because `:core:testing` cannot reference implementation modules (rule 9). S8 (2026-10-06) verified the dynamic form end to end: one `@BindingContainer object` replaces exactly one binding while the rest fall through to the real contributions. Constraint: `createGraph`/`createDynamicGraph` require a graph without a `@DependencyGraph.Factory`; graphs with factories are built through `createGraphFactory<T.Factory>()`. Instrumented tests combine it with member injection by calling `graph.inject(target)` on the dynamic graph like production code does.
- Robolectric graph tests in `:app/src/test` replace `SqliteDriverBindings` with `TestSqliteDriverBindings` (provides `AndroidSQLiteDriver()`), because the bundled driver's Android `.so` files target device ABIs, not the host JVM ([S4](#s4-robolectric-with-androidsqlitedriver)). Instrumented tests keep `BundledSQLiteDriver`; desktop tests use the bundled driver's host natives.
- DAO and migration tests in `:core:database` build the database directly with the driver under test (`desktopTest` with the bundled driver; GMD runs with both drivers); they do not use a graph.

---

## Navigation

Serves R2.4, R5.7, R8.9, N7. Delivered in M0a (mechanics on both shells; [S5](#s5-nav3-12-api-names-and-scenes) and [S9](#s9-compose-multiplatform-ui-stack)), screens per milestone, desktop link and file sources in M0b and MD2. Honours [D7](../PLAN.md#3-key-decisions), [D54](../PLAN.md#3-key-decisions), [D56](../PLAN.md#3-key-decisions), [D85](../PLAN.md#3-key-decisions). This section is mechanics only; which key opens where, re-tap behaviour, pane roles, Escape as back on the desktop and predictive-back ordering with `PlayerSheet` are owned by [08 Navigation](08-ui-ux.md#navigation). Common code compiles against JetBrains' `navigation3-ui` 1.1.2 API; Android runs androidx 1.2.0 ([Version table](#version-table)), so the code below uses only 1.1 API.

### Contracts in `:core:navigation`

```kotlin
// :core:navigation (commonMain) — every key is @Serializable, implements NavKey, carries IDs and strings only
@Serializable sealed interface TopLevelKey : NavKey
@Serializable data object FeedsKey : TopLevelKey
@Serializable data object LibraryKey : TopLevelKey
@Serializable data object UpNextKey : TopLevelKey
@Serializable data object DownloadsKey : TopLevelKey
@Serializable data object DiscoverKey : TopLevelKey
@Serializable data class PodcastKey(val podcastId: Long) : NavKey
// ... all keys of the canonical key table, created in M0a so cross-feature navigation compiles from day one
// M11a (08's signatures): InstallHelpKey(val section: String = ""), VerificationNoticeKey (data object)
// MS2: the Sync keys of 08 Sync screens

/** One registry of every NavKey: non-Android targets cannot serialize keys by reflection. */
object NavKeySerializers {
    val module: SerializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(FeedsKey::class); subclass(LibraryKey::class); subclass(PodcastKey::class) // ... every key
        }
    }
    val savedStateConfiguration = SavedStateConfiguration { serializersModule = module }
}

typealias EntryProviderInstaller = EntryProviderScope<NavKey>.() -> Unit

interface AppNavigator {
    fun push(key: NavKey)                          // onto the selected tab's stack (sheets and dialogs too)
    fun selectTab(key: TopLevelKey)
    fun pop(): Boolean                             // false when nothing was popped
    fun resetTab(key: TopLevelKey)                 // stack back to its root
    fun open(tab: TopLevelKey, stack: List<NavKey>) // deep links: select tab, replace its stack above the root
    fun pushDetail(key: NavKey)                    // 08: replaces a same-class top entry on ≥ 2 panes, else push
}
val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator not provided") }
// 08 adds SettingsHomeKey, PaneLayout, LocalPaneLayout and LocalNavTab here (08 Navigation); :core:ui's host provides them.

object NdSceneMetadata {                           // overlay metadata understood by the host's scene strategies
    fun bottomSheet(): Map<String, Any> = mapOf(KEY_OVERLAY to "sheet")
    fun dialog(): Map<String, Any> = mapOf(KEY_OVERLAY to "dialog")
    const val KEY_OVERLAY = "nd.overlay"
}
// Nav3 1.1 added a typed metadata DSL (NavMetadataKey, e.g. DialogKey for its DialogSceneStrategy). If S5 or S9 show that
// the strategies read only typed keys, NdSceneMetadata returns that type instead; call sites stay unchanged.
```

`rememberNavBackStack(NavKeySerializers.savedStateConfiguration, root)` is the overload every back stack uses, on both platforms: the desktop JVM could serialize keys by reflection, but non-JVM targets cannot, and declaring the module now keeps the iOS door open and avoids a later migration ([CMP Navigation 3](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html)). A `commonTest` test enumerates every `NavKey` subclass (from the canonical key table's list in the test) and asserts that the module serializes and restores each one. Verified 2026-10-06 (step 11): `SavedStateConfiguration` and its `serializersModule` builder property live in `androidx.savedstate.serialization` (savedstate 1.4.0, pulled in by `navigation3-runtime` 1.2.0), and `NavKey` and `EntryProviderScope` are in `androidx.navigation3.runtime` — the section's shapes match the artifacts, no deviation. The registration test (`NavKeySerializersTest`) additionally asserts, via `SerializersModule.dumpTo`, that the registered set equals the canonical key list exactly, so a key missing from the module fails the build.

`:core:navigation` applies no Compose compiler plugin; it declares `api` dependencies on `navigation3-runtime` (`NavKey`, `EntryProviderScope`) and on the Compose Multiplatform runtime (for `staticCompositionLocalOf`).

### Feature entry installers

```kotlin
// :feature:podcast (commonMain)
@BindingContainer @ContributesTo(AppScope::class)
internal object PodcastNavigation {
    @Provides @IntoSet fun entries(): EntryProviderInstaller = {
        entry<PodcastKey>(metadata = ListDetailSceneStrategy.detailPane()) { key -> PodcastRoute(key) }
        entry<PodcastSettingsKey> { key -> PodcastSettingsRoute(key) }
    }
}

@AssistedInject
internal class PodcastViewModel(
    @Assisted val key: PodcastKey,
    private val podcasts: PodcastRepository,
) : ViewModel() {
    // S8 (2026-10-06): the manual assisted key takes no argument (the key class is inferred); the
    // factory may be a fun interface; everything is Metro's own @Assisted/@AssistedFactory, matched
    // by parameter name.
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(key: PodcastKey): PodcastViewModel
    }
}

@Composable internal fun PodcastRoute(
    key: PodcastKey,
    vm: PodcastViewModel = assistedMetroViewModel<PodcastViewModel, PodcastViewModel.Factory> { create(key) },
) { /* collectAsStateWithLifecycle, LocalAppNavigator.current for gesture navigation */ }

// Every graph extending ViewModelGraph needs exactly one contributed factory binding (S8, 2026-10-06;
// metrox-viewmodel's documented shape; the three maps are declared by ViewModelGraph itself):
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class NeutrodyneViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders:
        Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
```

ViewModels without arguments use `@ViewModelKey` bare on the class (the implicit class key; S8, 2026-10-06) with `@ContributesIntoMap(AppScope::class)` and `metroViewModel()`. The shell provides `LocalMetroViewModelFactory` at the root ([MetroX ViewModel](https://zacsweers.github.io/metro/latest/metrox-viewmodel/), [MetroX ViewModel Compose](https://zacsweers.github.io/metro/latest/metrox-viewmodel-compose/)); the factory comes from a contributed `MetroViewModelFactory` subclass every graph extending `ViewModelGraph` needs, and both `metroViewModel()` and `assistedMetroViewModel` live in `dev.zacsweers.metrox.viewmodel` (there is no `.compose` package). The per-entry decorator is `rememberViewModelStoreNavEntryDecorator()` passed as `entryDecorators` to `NavDisplay` (S8; the shapes above are the working 1.4.5 API).

Metadata conventions: list/detail/extra panes use `ListDetailSceneStrategy.listPane()/detailPane()/extraPane()` from `adaptive-navigation3` (the pane role per key is in [08 Information architecture](08-ui-ux.md#information-architecture)); sheet keys (`AddPodcastKey`, `AddToGroupsKey`, `AllGroupsKey`, `SleepTimerKey`, `SpeedKey`) use `NdSceneMetadata.bottomSheet()`; dialog keys (`ExportKey`) use `NdSceneMetadata.dialog()`. Every key has exactly one installer; the [graph test](#testing) of each shell fails on a key with zero or two entries.

### AppNavigator and per-tab back stacks

The navigation host is shared by both shells and lives in `:core:ui`'s `commonMain` ([Dependency rules](#dependency-rules) rule 7): `NavigationState` implements `AppNavigator` (including 08's `pushDetail`, which reads `LocalPaneLayout`'s partition count through a state the root updates): one `NavBackStack<NavKey>` per `TopLevelKey`, each created with `rememberNavBackStack(NavKeySerializers.savedStateConfiguration, root)` inside `rememberNavigationState()` (saveable across process death on Android and across recomposition on the desktop), plus the selected tab in `rememberSaveable`. It is provided to the tree with `CompositionLocalProvider(LocalAppNavigator provides state)`. `NeutrodyneRoot` (08's layout: navigation suite, `NavDisplay`, player sheet or side panel) is also in `:core:ui` and receives everything feature- or domain-specific from its shell: the installer set from the graph, the player surface of `:feature:player` as a composable slot, and the root state (start-up gate, update badge, first-run cards, the "Continue on this device" offer) from the shell's start-up ViewModel. `MainActivity` (`setContent`) and 11's `NeutrodyneWindow` each call it.

**Root contract (2026-10-05).** `:core:ui` may not see `:sync:api`, `:core:domain`, `:playback:api` or `:feature:player` (rule 7), so `NeutrodyneRoot` takes only `:core:ui` and `:core:model` types; the shells map the domain flows into them:

```kotlin
// :core:ui commonMain
@Immutable data class RootUiState(
    val startup: StartupGateState,                 // gate visuals (08)
    val settingsBadge: Boolean,                    // AppUpdateChecker.state has an update (08 Settings gear badge)
    val notice: RootNotice?,                       // from UpdateNotices.pending: FirstRunChoice | VerificationEnforcement
    val heldChanges: HeldChangesBanner?,           // from SyncController.heldChanges (device name, counts, id)
    val remoteSession: RemoteSessionCard?,         // from SyncController.remoteSession, null while something plays here
)
class RootActions(
    val retryStartup: () -> Unit, val dismissNotice: (RootNotice) -> Unit,   // "Review" pushes SyncHeldChangesKey(id) itself
    val continueHere: () -> Unit, val dismissRemoteSession: () -> Unit, val playbackKey: (PlaybackKey) -> Boolean,
)
class RootSlots(val player: @Composable (PlayerSlotState) -> Unit, val userMessages: Flow<UserMessage>)

@Composable fun NeutrodyneRoot(state: RootUiState, actions: RootActions, slots: RootSlots, installers: Set<EntryInstaller>)
```

`StartupViewModel` (`:app`) and `DesktopStartupViewModel` (`:desktopApp`) build `RootUiState` from `DatabaseOpener`, `AppUpdateChecker`, `UpdateNotices`, `SyncController` and `PlaybackStateSource`, wire `RootActions` to `StartupViewModel.retry()` (or its desktop twin), `UpdateNotices.dismiss`, `SyncController.dismissRemoteSession()`, `PlaybackController.play()` and 08's key dispatch, and merge `SyncController.notices` into `userMessages`; `MainActivity` and `NeutrodyneWindow` obtain `PlayerViewModel` with `metroViewModel()` and pass `PlayerSheet` as the `player` slot. A shared `:core:shell` module (a D13 change) remains the alternative if the two mappings drift (Open questions 19).

```kotlin
// :core:ui (commonMain) — sketch; exact Nav3 names are Spike S5/S9 outputs
@Composable fun NeutrodyneNavHost(state: NavigationState, installers: Set<EntryProviderInstaller>) {
    val provider = entryProvider { installers.forEach { install -> install() } }
    // Decorate EVERY tab's stack on every composition, in the fixed TopLevelKey order, each with its own decorator
    // instances: a stack that is not decorated in a composition loses its saveable state and ViewModelStores, and a
    // remember call inside a list of varying length breaks positional memoization (nav3-recipes "multiple back stacks").
    val decoratedByTab: Map<TopLevelKey, List<NavEntry<NavKey>>> = state.tabs.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = state.stack(tab),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    rememberTabLocalNavEntryDecorator(tab),        // provides 08's LocalNavTab = tab to every entry
                ),
                entryProvider = provider,
            )
        }
    }
    val entries = state.visibleTabs().flatMap { decoratedByTab.getValue(it) }
    SharedTransitionLayout {
        NavDisplay(
            entries = entries,
            onBack = { state.pop() },
            sceneStrategies = listOf(rememberNdBottomSheetSceneStrategy(), rememberNdDialogSceneStrategy(), rememberListDetailSceneStrategy()),
            sharedTransitionScope = this,
        )
    }
}
```

- `visibleTabs()` = the start tab (`FeedsKey`), then the selected tab if different. Popping a non-start tab's root therefore returns to Feeds; back from Feeds' root leaves the app on Android and does nothing on the desktop (Escape never closes the window; defaults confirmed by [08 Navigation](08-ui-ux.md#navigation)).
- Scene strategy order: overlay strategies first (they produce overlay scenes over the underlying scene), then `ListDetailSceneStrategy` (with 08's pane directive `ndPaneLayout`), then Nav3's single-pane default.
- `NdBottomSheetSceneStrategy` and `NdDialogSceneStrategy` live in `:core:ui`. Nav3 ships a `DialogSceneStrategy` since 1.1 ([Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3)); no bottom-sheet strategy ships, so ours follows the nav3-recipes bottom-sheet recipe (a copied file keeps its Apache-2.0 header, [Licensing](#copied-code-and-contributions)). Both **must render through the window-based `NdModalBottomSheet`/`NdDialog`** of `:core:designsystem`, never an in-layout sheet, so a sheet opened from the expanded `PlayerSheet` (speed, sleep timer) draws above it ([08 Sheets and dialogs](08-ui-ux.md#sheets-and-dialogs)); on the desktop these are Compose popups, and S9 checks their z-order above the expanded `PlayerSheet`. `NdDialogSceneStrategy` wraps Nav3's strategy only if it lets us supply that composable, otherwise it is ours.
- Nav3 1.2's deep-link API (`DeepLinkRequest`, `UriDeepLinkMatcher`) is not used: it is Android-only in common code's 1.1 API, and Neutrodyne's routes choose a tab and a whole stack and carry security rules ([Intent routing](#intent-routing)).
- `PlayerSheet` is not a key ([D56](../PLAN.md#3-key-decisions)); it sits beside `NavDisplay` in the root scaffold and owns its back handling ([08 Player sheet](08-ui-ux.md#player-sheet)).
- Back: `NavDisplay` handles entries; custom surfaces use `NavigationBackHandler` (Nav3, since 1.1) or `PredictiveBackHandler`; on Android predictive back drives them, on the desktop Escape does (08); `onBackPressed` overrides are banned ([`checkBannedApis`](#checkbannedapis-rules)).

#### ViewModels per entry

`rememberViewModelStoreNavEntryDecorator()` (lifecycle 2.11, multiplatform) scopes each entry's ViewModels to that entry; without it `metroViewModel()` scopes to the activity or window and two `PodcastKey`s would share one ViewModel. The decorator clears an entry's store when the entry leaves its back stack for good.

### Intent routing

`IntentRouter` (`:core:navigation`, `commonMain`) turns a platform-neutral `RouteInput` into a `Route`; the shell applies the route to `NavigationState`. **Android:** `MainActivity` (`launchMode="singleTop"`) converts every incoming intent to a `RouteInput` (action, data URI, `EXTRA_TEXT`, explicit-intent flag) in `onCreate` only when `savedInstanceState == null`, and in every `onNewIntent`. **Desktop:** `DesktopOpenHandler` (`:desktopApp`, 11) converts first-launch arguments, the arguments a second launch hands over, macOS open-URL and open-file events and files dropped on the window into the same `RouteInput`s ([11 Desktop shell](11-desktop.md#desktop-shell)); desktop notification clicks use the internal `neutrodyne://open/…` routes.

```kotlin
// :core:navigation
data class RouteInput(val action: Action, val uri: String? = null, val text: String? = null, val internal: Boolean = false) {
    enum class Action { LAUNCH, VIEW, SEND, OPEN_FILE }   // OPEN_FILE: a file the OS or the user handed the desktop app
}
sealed interface Route {
    data object None : Route
    data class Navigate(val tab: TopLevelKey, val stack: List<NavKey>) : Route   // AppNavigator.open
    data class Push(val key: NavKey) : Route                                   // onto the current tab (sheets)
    data class SelectFeed(val groupUuid: String?) : Route                      // Feeds tab + persisted pager selection (08)
    data object ExpandPlayer : Route
}
```

| Incoming | Exported? | Route |
|---|---|---|
| `MAIN`/`LAUNCHER`; desktop start without arguments | yes | `None` |
| `VIEW` `feed:`, `pcast:`, `podcast:`, `itpc:`, `https://podcasts.apple.com/…`, `neutrodyne://subscribe?url=…` (Android filters: [03 Deep links and share targets](03-feeds-and-discovery.md#deep-links-and-share-targets); desktop URL schemes `neutrodyne:`, `feed:`, `podcast:`, `pcast:`, `itpc:` registered per OS by 11) | yes | `Push(AddPodcastKey(input = uri))` |
| `SEND` `text/plain` (Android); text or a URL dropped on the desktop window | yes | `Push(AddPodcastKey(input = text))` |
| `OPEN_FILE` `.opml`, `.xml`, backup `.zip` (desktop: arguments, macOS open-file events, drag and drop) | yes | none directly: `DesktopOpenHandler` copies the file into an import session like 05's `ExternalImportActivity` and then routes `…/open/import/{sessionId}` internally ([05 Receiving files](05-groups-opml-backup.md#receiving-files)) |
| `neutrodyne://open/episode/{id}` | no (explicit intents; desktop: internal only) | `Push(EpisodeKey(id))` |
| `…/open/podcast/{id}` | no | `Navigate(LibraryKey, [PodcastKey(id)])` |
| `…/open/group/{groupUuid}` | no | `SelectFeed(groupUuid)` |
| `…/open/downloads` | no | `Navigate(DownloadsKey, [])` |
| `…/open/player` | no | `ExpandPlayer` |
| `…/open/import/{sessionId}` | no | `Navigate(LibraryKey, [ImportKey(sessionId)])` |
| `…/open/settings/{page}` (e.g. `…/open/settings/updates` → `SettingsPage.UPDATES`, M11a) | no | `Push(SettingsKey(SettingsPage.valueOf(page.uppercase())))` |
| `…/open/settings/sync` (MS2; Settings › Sync is `:feature:sync`, which `:feature:settings` cannot reach, so `SettingsPage` has no `SYNC`) | no | `Push(SyncSettingsKey)` |
| `…/open/settings/updates/release` (M11a; the update notification's "Open on GitHub" action on both platforms) | no | `Push(SettingsKey(SettingsPage.UPDATES, openRelease = true))`: Settings › Updates opens and, once, if its state is `Available`, opens the validated release page like its own button ([09 Update card and links](09-quality-and-release.md#update-card-and-links)) |
| `…/open/help/install` (M11a) | no | `Push(InstallHelpKey())` (all sections collapsed) |
| `…/open/diagnostics` | no | `Push(DiagnosticsKey)` |
| anything else, malformed IDs, unknown pages | — | `None` (logged at WARN, redacted) |

Target tabs above are defaults; [08 Navigation](08-ui-ux.md#navigation) owns them. Security rules: `MainActivity` is exported and any desktop program can open a `neutrodyne:` link or launch the app with arguments, so **routes only navigate** — they never subscribe, play, delete or write without a confirming user action on the destination screen. `internal = false` inputs can never reach the `neutrodyne://open/…` routes (on Android those arrive only as explicit intents from our own `PendingIntent`s; on the desktop only from our own notifications and the import hand-off), so a foreign program cannot jump into Settings or open a release page. The one route that may also leave the app is `…/open/settings/updates/release`: it can open only the release page Settings › Updates already shows (`info.releaseUrl`, validated by 09, never a URL taken from the input); without an `Available` state it only navigates. The router reads at most 4 KB of text, lowercases the scheme before matching (intent-filter scheme matching is case-sensitive), ignores unknown extras and arguments, and never trusts `EXTRA_REFERRER`. Android notification `PendingIntent`s target `MainActivity` explicitly with `FLAG_IMMUTABLE`; our code creates no mutable `PendingIntent`. The verification notice (`VerificationNoticeKey`, Android) is not a route: the root pushes it when `UpdateNotices` (`:core:domain`) has `VERIFICATION_ENFORCEMENT` pending, and the first-run update-check card is 08's root content ([08 Navigation](08-ui-ux.md#navigation)); the update notification opens `…/open/settings/updates`, and its "Open on GitHub" action `…/open/settings/updates/release`, so the browser hand-off and its failure handling happen in the app. The update card's links ("Open release on GitHub", "Download APK for this device" or "Download for this computer") leave the app through `:core:ui`'s `ExternalUrlOpener` (`ACTION_VIEW` with `ActivityNotFoundException` handled on Android, `java.awt.Desktop.browse` or the OS fallback on the desktop; on failure the app offers to copy the link, [09 Update check](09-quality-and-release.md#update-check)); nothing comes back into the app from them. On Android, OPML/backup files arrive at `ExternalImportActivity`, which copies the payload and then routes to `ImportKey(sessionId)` through an explicit intent ([05 Receiving files](05-groups-opml-backup.md#receiving-files)).

---

## Build variants and ABIs

Serves R3.5–R3.7, N5, N7, N8, N12. Delivered in M0a (build types `release` and `debug`, the `neutrodynePublic` signing config on the committed keystore, R8 configuration and the baseline-profile plugin wiring per [S19](#s19-release-build-with-r8-and-baseline-profiles), ABI splits and Chaquopy per [S7](#s7-chaquopy-under-agp-941); CI builds all three published APKs from the first commit), M6b (`:benchmark`), M9a (engine switch with its binding directories; the desktop's in MD3), M10 (Macrobenchmarks on `benchmarkRelease`), M11b (committed baseline and startup profiles). Honours [D2](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [D72](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), [PO-35](../PLAN.md#48-further-product-owner-decisions) (re-resolved 2026-10-05).

One Android product, **no product flavors**: GitHub Releases is the only channel ([PO-2](../PLAN.md#po-2-distribution-channels)), so nothing differs per channel, and every YouTube difference is a runtime capability ([YouTube bindings](#youtube-bindings)). The owner meant "no key management", not debuggable builds ([PO-35](../PLAN.md#48-further-product-owner-decisions), [D96](../PLAN.md#3-key-decisions)): **every published APK is an optimised, non-debuggable `release` build** signed with the committed public keystore; `debug` is for local development only, installs beside the published app and is never published. Developer behaviour keys to the `debug` build type through `BuildInfo.debug` ([Debug build type](#debug-build-type)). The desktop has no build types: one published, unminified configuration per OS and architecture, built by jpackage ([11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)); the server is one fat JAR ([10 Deployment](10-sync.md#deployment)).

| Build | `applicationId` | Code shrinking | Debuggable | Signed with | Used for |
|---|---|---|---|---|---|
| `release` — **published** | `ch.lkmc.neutrodyne` (frozen, [D61](../PLAN.md#3-key-decisions)) | R8 full mode: shrinking, optimisation and resource shrinking, **obfuscation off** ([Release build and baseline profiles](#release-build-and-baseline-profiles)) | no | `neutrodynePublic` ([Signing config](#signing-config)) | every GitHub release and milestone tester build; ACRA on ([D62](../PLAN.md#3-key-decisions)), the update check on ([09 Update check](09-quality-and-release.md#update-check)); baseline and startup profiles from M11b; the nightly `release-build-smoke` and the release smoke journeys through `:benchmark` (09) |
| `debug` — local | `ch.lkmc.neutrodyne.debug` (`applicationIdSuffix ".debug"`, `versionNameSuffix "-debug"`) | none | yes (AGP's default for `debug`, [build variants](https://developer.android.com/build/build-variants)) | `neutrodynePublic` | development, unit tests (the tested build type) and the instrumented suite; LeakCanary, StrictMode penalties, verbose logging, pseudo-locales, debug-only screens; ACRA off; update check `Disabled(DEV_BUILD)`; installs beside the published app. **Never published** ([Debug build type](#debug-build-type)) |
| `benchmarkRelease` (created by `androidx.baselineprofile`) | `ch.lkmc.neutrodyne` | as `release` | no; profileable | `neutrodynePublic` | N5's Android performance budgets PB1–PB5 (Macrobenchmark refuses debuggable targets, [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)); never published |
| `nonMinifiedRelease` (created by `androidx.baselineprofile`) | `ch.lkmc.neutrodyne` | none | no; profileable | `neutrodynePublic` | baseline and startup profile generation only; never published |

Published APKs are split per ABI with AGP's ABI splits ([D77](../PLAN.md#3-key-decisions), [configure APK splits](https://developer.android.com/build/configure-apk-splits)); AGP names the outputs `app-{abi}-release.apk` (Unverified: AGP 9 output names), and the release workflow renames them to the asset names (09):

| Release asset | ABI | YouTube engine | Budget (N5) |
|---|---|---|---|
| `neutrodyne-{v}-arm64-v8a.apk` | `arm64-v8a` | bundled | < 40 MB (PB12) |
| `neutrodyne-{v}-x86_64.apk` | `x86_64` | bundled | < 40 MB (PB12) |
| `neutrodyne-{v}-armeabi-v7a.apk` | `armeabi-v7a` | not bundled: Chaquopy publishes no 32-bit runtime for Python ≥ 3.12 ([Chaquopy docs](https://chaquo.com/chaquopy/doc/current/android.html)); YouTube runs in external mode (`NOT_IN_THIS_APK`) | < 30 MB (PB13) |

The budgets are for the published release APKs and are Unverified estimates; [S7](#s7-chaquopy-under-agp-941) and [S19](#s19-release-build-with-r8-and-baseline-profiles) measure them in M0a, and a miss goes to the PO for new budgets (PLAN M0 AC1). There is **no universal APK** (`isUniversalApk = false`: it would carry two engines). All three APKs share one `versionCode` ([D63](../PLAN.md#3-key-decisions)); the update card links the entry for `Build.SUPPORTED_ABIS[0]` and Obtainium filters by ABI (09). An arm64 device that installed the `armeabi-v7a` APK runs it as a 32-bit process, so it too is in external mode; 08's "Get the 64-bit version" hint covers it. The R8 mapping of each release is attached as `neutrodyne-{v}-r8-mapping.zip` ([D79](../PLAN.md#3-key-decisions)).

```kotlin
// :app/build.gradle.kts (what neutrodyne.android.application sets, plus app-specific lines)
val youtubeEngine = providers.gradleProperty("neutrodyne.youtubeEngine").orElse("true").get().toBoolean()
android {
    namespace = "ch.lkmc.neutrodyne"
    testBuildType = "debug"                      // unit and instrumented tests; release is checked by smoke journeys (09)
    defaultConfig {
        applicationId = "ch.lkmc.neutrodyne"
        targetSdk = 37
        versionCode = providers.gradleProperty("neutrodyne.versionCode").get().toInt()
        versionName = providers.gradleProperty("neutrodyne.versionName").get()
        fun prop(name: String) = providers.gradleProperty(name).orElse("").get()
        buildConfigField("String", "REPO_URL", "\"${prop("neutrodyne.repoUrl")}\"")
        buildConfigField("String", "ENGINE_MANIFEST_URL", "\"${prop("neutrodyne.engineManifestUrl")}\"")
        buildConfigField("boolean", "YOUTUBE_ENGINE", youtubeEngine.toString())
        buildConfigField("String", "ACRA_MAILTO", "\"${prop("neutrodyne.acraMailto")}\"")
        buildConfigField("String", "PODCASTINDEX_KEY", "\"${prop("neutrodyne.podcastIndexKey")}\"")
        buildConfigField("String", "PODCASTINDEX_SECRET", "\"${prop("neutrodyne.podcastIndexSecret")}\"")
    }
    buildFeatures { buildConfig = true; compose = true }
    signingConfigs {
        create("neutrodynePublic") {             // committed and public on purpose (D61, Signing config)
            storeFile = rootProject.file("signing/neutrodyne-public.keystore")
            storeType = "pkcs12"
            storePassword = "neutrodyne"
            keyAlias = "neutrodyne"
            keyPassword = "neutrodyne"
            enableV1Signing = false              // minSdk 26: v2 + v3 only
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    splits {
        abi {                                    // AGP 9.4.1 names (S7, 2026-10-06): unchanged from AGP 8
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    // From M9a: exactly one binding directory joins main (YouTube bindings). Unverified DSL under built-in Kotlin.
    sourceSets.getByName("main").kotlin.srcDir(if (youtubeEngine) "src/youtubeEngine/kotlin" else "src/noYouTubeEngine/kotlin")
    buildTypes {
        release {                                // THE PUBLISHED BUILD TYPE (D96): not debuggable (AGP default)
            optimization { enable = true }       // AGP 9.3+ DSL: R8 code + resource optimization; includes the platform default
                                                 // keep rules equivalent to proguard-android-optimize.txt. No proguardFiles(...) calls:
                                                 // keep rules come from the keepRules source set (Release build and baseline profiles)
            signingConfig = signingConfigs.getByName("neutrodynePublic")
        }
        debug {                                  // local development only; never published (Debug build type)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isPseudoLocalesEnabled = true        // 09 Localisation
            buildConfigField("String", "ACRA_MAILTO", "\"\"")   // ACRA off (D62)
            signingConfig = signingConfigs.getByName("neutrodynePublic")   // never AGP's per-machine ~/.android/debug.keystore
        }
        // benchmarkRelease and nonMinifiedRelease are created by androidx.baselineprofile from release; the convention plugin
        // sets signingConfig = neutrodynePublic on them too (Unverified whether they inherit it, S19)
    }
    packaging { jniLibs { useLegacyPackaging = false } }   // S7 measured (2026-10-06): legacy compresses the .so files
                                                          // (−9.8 MB on arm64-v8a, −9.6 MB on x86_64, −2.6 MB on
                                                          // armeabi-v7a) at the cost of extraction on install, and is
                                                          // required by fallback A2's exec'd launcher — not needed for
                                                          // PB12/PB13, so default packaging stands
}
dependencies {
    if (youtubeEngine) implementation(project(":youtube:ytdlp"))
    implementation(libs.androidx.profileinstaller)          // installs the baseline profile outside Google Play
    debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // from M11b: baselineProfile(project(":benchmark"))
}
```

`initWith`, `matchingFallbacks` and a signing config per build type are documented in [build variants](https://developer.android.com/build/build-variants); the plugin-created build types match library modules' `release` variant on their own (Android-only libraries have `debug` and `release`; KMP library modules have one variant). Unverified: that the exact DSL above compiles unchanged under AGP 9.4.1 (M0a step 17 records deviations).

```kotlin
// youtube/ytdlp/build.gradle.kts (M0a stub with S7's outcome; content: 04)
plugins { alias(libs.plugins.neutrodyne.android.library); id("com.chaquo.python") }
android {
    buildFeatures { aidl = true }                                          // IYtxEngine, IYtxCallback (04 Binder API)
    defaultConfig { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }  // Python ≥ 3.12 is 64-bit only. Unverified under
}                                                                          // the app's ABI splits: S7 measures what each split gets
chaquopy {
    defaultConfig {
        version = "3.14"                 // fallback "3.13" (S7)
        // buildPython("python3.14")     // only if auto-detection fails; must match the app's minor version
        pip { }                          // empty: no pip packages in v1 (no yt-dlp extras, so never mutagen); a package needs a lockfile entry
    }
}
// Python sources: the shared shim (youtube/engine/python/neutrodyne_ytx/, Unlicense, with the Chaquopy host adapter
// host_chaquopy.py) packaged as Chaquopy sources, .pyc compiled at build time. Vendored yt-dlp and bundled.json packaged as
// assets; unpacking and on-device .pyc: 04.
```

- **`BuildConfig` never contains timestamps or git data** (the report-only nightly reproducibility check, [09 Reproducible builds](09-quality-and-release.md#reproducible-builds)). Secrets are empty strings unless supplied by `-P`; PO-3 default B means `PODCASTINDEX_*` stay empty in every build until Podcast Index grants written permission, after which `release.yml` passes them only to the `assembleRelease` run and the desktop packaging jobs of a published release ([D26](../PLAN.md#3-key-decisions)); PR, nightly, `benchmarkRelease` and `debug` builds never carry them.
- **`BuildInfo`** (`:core:model`) is how every non-shell module reads build facts on both platforms: `versionName`, `versionCode`, `debug` (Android: `BuildConfig.DEBUG`; desktop: the `DEV` install kind of `:desktopApp:run` and tests), `platform` (`ANDROID`, `DESKTOP`), `repoUrl`, `updateManifestUrl` (`$repoUrl/releases/latest/download/neutrodyne-update.json`), `engineManifestUrl`, `youTubeEngineBundled` (Android: `BuildConfig.YOUTUBE_ENGINE && Process.is64Bit()`; desktop: the switch), `apkAbi` (Android only, runtime: the first entry of `Build.SUPPORTED_64_BIT_ABIS` if `Process.is64Bit()`, else of `Build.SUPPORTED_32_BIT_ABIS` — the ABI of the installed APK), `desktop` (desktop only: `os`, `arch`, `installKind`, `runtime`, written by the packaging task into a resource that `:desktopApp`'s loader reads, [11 Desktop shell](11-desktop.md#desktop-shell)), `shippedLocales` (generated from `app/policy/locales.txt` for 08's language pickers, [09 Shipped locales and per-app language](09-quality-and-release.md#shipped-locales-and-per-app-language)), `podcastIndexKey`, `podcastIndexSecret`. Its `toString()` omits the two secrets. `:app` builds it from `BuildConfig` and `Build`; `:desktopApp` from its resource; there is no `devTools` field (retired 2026-10-05), no `releasesAtomUrl` (no beta channel, [PO-33](../PLAN.md#48-further-product-owner-decisions)), no `distribution` field and no per-build licence statement ([About statements](#about-statements)).
- **Source sets of `:app`:** `main`; from M9a `app/src/youtubeEngine/kotlin` or `app/src/noYouTubeEngine/kotlin` joins `main` ([YouTube bindings](#youtube-bindings)); `app/src/debug/` (LeakCanary wiring, `DebugToolsInitializer`, `DebugHttpLogInterceptor`, debug-only screens); `app/src/release/generated/baselineProfiles/` (committed profiles, M11b); `app/src/benchmarkRelease/` (09's `BenchmarkSeedReceiver`, M10; never in `release`). No other module has build-type source sets ([`checkBannedApis`](#checkbannedapis-rules) rule 9). Feature code never branches on the build; it reads `YouTubeCapabilitiesSource` or `BuildInfo`.
- **Tests:** unit tests run on `debug` only (AGP 9 creates unit tests only for the tested build type); the instrumented suite runs on `debug` APKs (`ch.lkmc.neutrodyne.debug`); the release APKs are exercised by `:benchmark`'s smoke journeys and the YouTube smoke test (E7) nightly and before each release (09, PLAN M11 AC7).

### Release build and baseline profiles

[D96](../PLAN.md#3-key-decisions). Delivered in M0a (R8 configuration, keep rules, plugin wiring checked by S19), M10 (Macrobenchmarks), M11b (committed profiles).

**R8.** `release` runs R8 in full mode with code optimisation and resource shrinking through AGP 9's `optimization { enable = true }` ([shrink code](https://developer.android.com/build/shrink-code)); R8 is BSD-3-Clause ([R8 licence](https://r8.googlesource.com/r8/+/refs/heads/main/LICENSE)), unlike ProGuard, which is banned ([D3](../PLAN.md#3-key-decisions)). **Obfuscation is off** (`-dontobfuscate` in `app/src/main/keepRules/app.keep`, allowed only in the app: AGP 9 forbids global options in library consumer rules), so ACRA stack traces stay readable without a mapping server; R8 still writes `mapping.txt` for inlined and outlined frames, and 09's release job attaches it as `neutrodyne-{v}-r8-mapping.zip`.

**Keep rules** (AGP 9.3+ `keepRules` source set, files ending in `.keep`). `app/src/main/keepRules/app.keep` holds `-dontobfuscate` and app-wide keeps; kotlinx.coroutines keeps for byte-identical rebuilds are added only if 09's report-only `repro` job shows they matter. Android-only libraries ship consumer rules as `consumer-rules.pro` via `consumerProguardFiles`; KMP modules publish them from `src/androidMain/consumer-rules.pro` (Unverified DSL of the Android-KMP plugin, S19). With `android.r8.strictFullModeForKeepRules=true`, `-keep class A` no longer keeps constructors: write `-keep class A { <init>(...); }` explicitly. What needs rules, each verified by S19's minified build and the release smoke journeys:

| Reached by | Rule |
|---|---|
| Python through Chaquopy's Java interop (R8 cannot see it) | `youtube/ytdlp/consumer-rules.pro`: `-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.PyHttp { public <init>(...); public *; }` and its request/response holder classes, the same for `…ytx.QuickJsEngine` when the JS provider ships, and `-keep class ch.lkmc.neutrodyne.youtube.ytdlp.YtxTestHooks { *; }` (04's test hook, inert until an instrumentation test arms it; 09 owns how the release smoke test arms it). S7 recorded (2026-10-06): Chaquopy ships no AAR and `chaquopy_java` carries no ProGuard rules, so these consumer rules are the only source; S19's release run re-checks them |
| kotlinx-serialization | the library's bundled rules; our `@Serializable` classes are reached through generated serializers, and `NavKeySerializers` registers every polymorphic key explicitly, so no class is looked up by name (S19 checks a restored back stack on the release APK) |
| Metro, Room 3 KMP (`@ConstructedBy`), Compose resources' generated `Res` | no reflection, no rules expected (S19 confirms); Compose resources are packaged under `composeResources/` and must survive resource shrinking (Unverified, S19 checks a localised string on the release APK) |
| Media3, OkHttp, Ktor, Coil, ACRA, WorkManager, the bundled SQLite driver's JNI | the libraries' own consumer rules |

**Baseline and startup profiles.** `:benchmark` (`com.android.test` with `androidx.baselineprofile` 1.5.0 as producer) generates them with `BaselineProfileRule` journeys against `nonMinifiedRelease` (cold start to Feeds, scroll the cover grid, open a podcast, open the player; 09 owns the journeys, [09 Macrobenchmark and profiles](09-quality-and-release.md#macrobenchmark-and-profiles)), with `includeInStartupProfile = true` for the start-up journey so R8 can lay out the startup DEX ([Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)). `:app` applies the plugin as consumer with `baselineProfile { saveInSrc = true; automaticGenerationDuringBuild = false }`, so the profiles are committed under `app/src/release/generated/baselineProfiles/` and regenerated by a maintainer (`./gradlew :app:generateBaselineProfile` on a GMD) when start-up code changes and before each MINOR release from M11b — never inside `release.yml`, which has no emulator. The app-level profile covers library code, so KMP and Android library modules need no plugin. Outside Google Play no cloud profiles exist: ProfileInstaller (`:app` dependency) writes the bundled profile on first start and ART compiles it during background dexopt (Unverified how soon after a sideloaded install or update; the release smoke journey records it). Until M11b the release build has no profile and N5 is measured without one. Unverified: the plugin 1.5.0 under AGP 9.4.1 with KMP library modules in the graph, and the AGP 9 names of its DSL properties — [S19](#s19-release-build-with-r8-and-baseline-profiles).

**Release-only checks.** 09's `check-apk.sh --published` asserts on every published APK: no `application-debuggable`, no `testOnly`, no LeakCanary (`leakcanary`, `shark`), no `ui-tooling` `PreviewActivity`, no ui-test-manifest activity, no class from `app/src/debug/` or `app/src/benchmarkRelease/`, and the committed keystore's certificate ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)); `verifyManifestPermissions` checks the merged `release` manifest; the nightly `release-build-smoke` installs the release APK on the API 36 GMD.

### Signing config

Every build of `:app` — `release`, `debug`, `benchmarkRelease` and `nonMinifiedRelease`, on CI and on every developer machine — is signed by the `neutrodynePublic` signing config with the keystore committed at `signing/neutrodyne-public.keystore` (PKCS12, alias `neutrodyne`, store and key password `neutrodyne`; [D61](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), [09 Committed keystore](09-quality-and-release.md#committed-keystore)).

- **One certificate everywhere.** A release build from any machine installs over any other with `adb install -r` and keeps the app's data (PLAN M0 AC8). AGP's default debug signing would use a generic, per-machine debug keystore ([build variants](https://developer.android.com/build/build-variants)), so `debug` names `neutrodynePublic` too; it installs beside the release build anyway because of its `.debug` application ID.
- **Schemes:** APK Signature Scheme v2 + v3, v1 off (minSdk 26).
- **No secret, no environment variable, no unsigned build.** Gradle reads no signing input except the committed file; the nightly `repro` job's rebuilds are signed with it too ([09 Reproducible builds](09-quality-and-release.md#reproducible-builds)).
- **Expected certificate.** 09's certificate script computes the certificate SHA-256 from the committed keystore; `release.yml` compares every APK's signer with it.
- **Repository files** (M0a, [M0 scaffold checklist](#m0-scaffold-checklist) step 1): the keystore, generated once with the `keytool` command in [09 Committed keystore](09-quality-and-release.md#committed-keystore); `signing/README.md` (public on purpose; download Neutrodyne only from the GitHub release page; forks must change the application ID or the key); `.gitattributes` `signing/*.keystore binary`; `.gitignore`'s `*.jks` rule does not match the file. Unverified: whether GitHub push protection flags a committed PKCS12 file; if it does, the push is completed with the documented reason.
- **Costs** are the owner's trade-offs in PLAN ([D61](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions), risks P9 and P10): the key is public, so the certificate proves nothing about who built an APK, anyone can sign an APK that installs over Neutrodyne as an update, and a later switch to a private key means one reinstall for every user. The YouTube engine manifest's Ed25519 key is unrelated: it is a secret of the `engine-approval` environment, not an APK key ([04 Engine updates](04-youtube.md#engine-updates)). The desktop builds carry no publisher signature at all; macOS gets jpackage's ad-hoc signature ([D80](../PLAN.md#3-key-decisions), [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)).

### Debug build type

`debug` is the local development build ([D2](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions)); it replaced the dev-tools switch (`-Pneutrodyne.devTools`, `BuildInfo.devTools`, `app/src/devTools/`, the `.dev` application ID and the nightly `dev-tools-build`, all retired 2026-10-05). Nothing in it reaches a published APK.

| What | How |
|---|---|
| Application ID `ch.lkmc.neutrodyne.debug`, version name `…-debug` | `debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }`; installs beside the published app (same key, different app); processes `ch.lkmc.neutrodyne.debug:ytx` and `…debug:acra` |
| `BuildInfo.debug = true` | the only build fact code may branch on for developer behaviour (from `BuildConfig.DEBUG`, read in `:app` only) |
| LeakCanary | `debugImplementation(libs.leakcanary.android)` in `:app` |
| `app/src/debug/kotlin` | `DebugToolsInitializer` ([initializer](#application-start-up) order 0: StrictMode thread and VM policies with death penalties, LeakCanary configuration), `DebugHttpLogInterceptor` (contributed to the island's `@DebugInterceptors` set), debug-only screens (none planned yet) |
| Network security config | the shared file's `<debug-overrides>` (user CAs for proxy debugging) applies only while `android:debuggable` is true, so only here ([Network security config](#network-security-config)) |
| Compose tooling | `debugImplementation` of `ui-tooling` (Android Studio previews of `:app`) and `ui-test-manifest` (instrumented Compose tests); previews of shared composables use the multiplatform preview artifact (S9) |
| Logging | `LogcatSink(DEBUG)` instead of `WARN`; WorkManager logs at `INFO` instead of `ERROR` ([Logging and redaction](#logging-and-redaction)) |
| `@ApplicationScope` exception handler | rethrows on the main thread (crash fast, [Errors](#errors)) |
| Crash reporting | `ACRA_MAILTO` forced to `""`, so ACRA is not installed ([D62](../PLAN.md#3-key-decisions)) |
| Pseudo-locales | `isPseudoLocalesEnabled` on `:app`'s `debug` ([09 Localisation](09-quality-and-release.md#localisation)) |
| Update check | `Disabled(DEV_BUILD)`; no `app-update-check` work, no first-run card, verification notice or notification ([09 Update check](09-quality-and-release.md#update-check)) |
| Policy tasks | `verifyDependencyPolicy` checks `releaseRuntimeClasspath`, where none of the above may appear; `verifyManifestPermissions` checks the `release` manifest |

**Implementation notes (M0a.1, 2026-10-06).** `DebugToolsInitializer`'s StrictMode applies the death penalty to network access on the main thread and to leaked closables, SQLite objects, registrations and activities; disk reads and writes on the main thread are only logged, because platform and library code on the start-up path (the splash screen, ProfileInstaller, LeakCanary itself) touches the disk there and would crash every debug run and instrumented test. `WorkerKey`, the `WorkerCreator` function type and the empty worker-map declaration live in `:core:common`'s `androidMain` (with `work-runtime` as its `api` dependency), because every module that owns a worker depends on `:core:common`; `MetroWorkerFactory` stays in `:app`.

The desktop has no build types: `:desktopApp:run` sets the `DEV` install kind, so `BuildInfo.debug` is true there and enables verbose file logging and the console sink only; packaged images are never `DEV` (11).

**Local device runs.** `connectedDebugAndroidTest` and Android Studio's Run install `ch.lkmc.neutrodyne.debug` and never touch an installed Neutrodyne. `benchmarkRelease` and `nonMinifiedRelease` carry the published application ID and key: `:benchmark`'s connected tasks install over the Neutrodyne on the attached phone, 09's orchestrator `clearPackageData` runs `pm clear` after each test ([AndroidX Test runner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)) and the connected test task uninstalls the app after the run ([Gradle forum](https://discuss.gradle.org/t/how-can-i-run-espresso-tests-without-uninstalling-apk-after/15492); Unverified for AGP 9.4) — the library, downloads and settings are gone. Rule: Macrobenchmarks, profile generation and the release smoke journeys run only on an emulator or a device whose Neutrodyne holds nothing worth keeping (back it up first, Settings › Backup). `CONTRIBUTING.md`'s testing section (09) and the release checklist's reference-device items state the rule.

### Emergency build without the engine

Risk L1: if a legal demand forces YouTube extraction out of the apps, releases without the engine must ship the same day ([04 Licensing and legal](04-youtube.md#licensing-and-legal)). It is a Gradle switch for both apps, not a flavor and not a patch:

- `./gradlew assembleRelease -Pneutrodyne.youtubeEngine=false` and, on each desktop runner, `:desktopApp:packageDistributionForCurrentOS -Pneutrodyne.youtubeEngine=false` (for a tagged release, the release branch commits `neutrodyne.youtubeEngine=false` so the tag reproduces it). `:app` then drops `:youtube:ytdlp` — no Chaquopy, CPython, yt-dlp, `YtxService` or engine assets in any APK — compiles `app/src/noYouTubeEngine/` and sets `BuildConfig.YOUTUBE_ENGINE = false`; `:desktopApp` drops `:youtube:ytdlp-desktop` and with it the python-build-standalone bundle and the vendored yt-dlp, compiles `desktopApp/src/noYouTubeEngine/` and writes `youtubeEngine = false` into its build-info resource.
- Behaviour: every APK and desktop image reports `ExternalReason.NOT_IN_THIS_APK` (desktop wording by 08); subscriptions and Layer A stay; YouTube episodes become external episodes; queued YouTube downloads end `FAILED(UNSUPPORTED_STREAM)` and completed files keep Delete and Share or Show in folder ([04 Engine absent or disabled](04-youtube.md#engine-absent-or-disabled)); engine updates stop because nothing schedules `engine-update` or runs the desktop engine-update lane; each shell's `noYouTubeEngine` directory contributes an `AppInitializer` (order 300) through which `AbsentYouTubeEngine` deletes the engine store once if it exists (Android `noBackupFilesDir/ytdlp/` and `cacheDir/yt-dlp/`, desktop `<data>/ytdlp/` and `<cache>/engine-cache/`; idempotent, on IO, in the housekeeping band; a later build with the engine re-extracts its bundled version).
- The engine's manual Licences entries live in their own AboutLibraries config subdirectory that each shell includes only when the switch is on (Unverified mechanism for AboutLibraries 15.x; checked in M9a; fallback: keep the entries and mark them "not included in this build").
- It cannot rot: 09's nightly `no-engine-build` job (blocking) assembles both and runs each shell's graph test with the switch off; `verifyManifestPermissions` passes unchanged because `:youtube:ytdlp` declares no permission.
- The `armeabi-v7a` APK of a normal build is not this build: it contains the engine's Kotlin code but no Python runtime, and reaches the same reason at runtime.

**Desktop and server outputs.** Desktop installers, their formats, jlink modules, JVM flags, the AOT cache, ad-hoc signing, the macOS `0.x` ZIP and the frozen MSI `upgradeUuid` are owned by [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception); this document owns only the convention plugins that build them. The server's fat JAR (`:sync:server:fatJar`), its image and deployment files are owned by [10 Deployment](10-sync.md#deployment).

---

## Networking baseline

Serves N3, N6, N7, N9, N13. Delivered in M0a (island, Ktor factory, base clients, monitors; [S12](#s12-ktor-fetch-pipeline)), M1a (feed client), M1b (auth), M4 (media), M6 (download), M7 (API), M9a (YouTube engine in `:ytx`), M9b (engine updates), M11a (update check), MS2 (`SYNC` client, LAN permission gate). Honours [D10](../PLAN.md#3-key-decisions), [D28](../PLAN.md#3-key-decisions), [D74](../PLAN.md#3-key-decisions), PO-13 default.

### One client family

OkHttp 5.5.0 is the only transport on both JVM targets ([D10](../PLAN.md#3-key-decisions)). All HTTP goes through one client family built in the JVM island `:core:network:okhttp` (`NetworkClients`): a credential-free core client and the base client derived from it, from which purpose-specific clients are derived with `newBuilder()` so they share the dispatcher, connection pool and interceptors. Common code never sees OkHttp: it uses **Ktor client 3.6.0** through `:core:network`'s `NeutrodyneHttpClients`, whose `HttpClient`s run on the OkHttp engine with the island's derived client passed as `preconfigured` ([Ktor client engines](https://ktor.io/docs/client-engines.html)), so Ktor callers share the same pool, DNS chain and interceptors. Libraries that need OkHttp itself use the island's clients directly: Media3's `OkHttpDataSource` (MEDIA, Android), Coil's `OkHttpNetworkFetcherFactory` (IMAGE, both platforms, wired in `:core:artwork`'s platform source sets), the desktop engine's `HttpByteSource` (MEDIA, [11 Desktop playback engine](11-desktop.md#desktop-playback-engine)) and, in `:ytx`, 04's `PyHttp`, which executes every request of yt-dlp's `NeutrodyneOkHttpRH` on the YOUTUBE client ([D74](../PLAN.md#3-key-decisions), [04 Networking bridge](04-youtube.md#networking-bridge)); Python's own OpenSSL never carries Android network traffic. The desktop engine child does its own HTTP with a bundled CA file ([D90](../PLAN.md#3-key-decisions), [11 Desktop YouTube engine host](11-desktop.md#desktop-youtube-engine-host)). **No OkHttp `Cache` anywhere** ([D10](../PLAN.md#3-key-decisions)); feeds keep their own validators ([03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)), Coil its own disk cache, Media3 its `SimpleCache`, the desktop engine its `SpanCache`, search an in-memory LRU. Constructing `OkHttpClient()` or `OkHttpClient.Builder()` outside the island, or a Ktor `HttpClient(` outside `:core:network`, is banned ([`checkBannedApis`](#checkbannedapis-rules) rule 8).

```kotlin
// :core:common (commonMain) — visible to the island, to :core:network and to callers
enum class HttpClientKind { FEED, API, IMAGE, MEDIA, DOWNLOAD, YOUTUBE, SYNC }

// :core:network:okhttp (JVM island)
/** Credential-free clients; contributed to AppScope and to YtxScope (the :ytx graph builds nothing else). */
// Unscoped (DI q6, 2026-10-06): each scope binds one instance through a contributed
// @Provides @SingleIn(…) container that constructs it — an explicit @Provides for CoreClients
// shadows the @Inject constructor, so a CoreClients provider parameter would self-cycle.
class CoreClients @Inject constructor(
    ua: UserAgentInterceptor, lanGuard: LocalNetworkGuard, hints: DnsFamilyHints,
    @DebugInterceptors debugInterceptors: Set<Interceptor>,          // empty except in Android debug builds (app/src/debug/)
) {
    private val dispatcher = Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 8 }
    private val pool = ConnectionPool(10, 5, TimeUnit.MINUTES)
    /** The only OkHttpClient.Builder() in the code base; every client shares dispatcher, pool, DNS chain and interceptors. */
    private fun coreBuilder(mode: LocalNetworkGuard.Mode) = OkHttpClient.Builder()
        .dispatcher(dispatcher).connectionPool(pool)
        .dns(lanGuard.dns(FamilyHintDns(Dns.SYSTEM, hints), mode))   // outermost: LAN guard (Android API 37+); inner: 04's IP-family hints
        .addInterceptor(lanGuard.interceptor(mode))                   // first application interceptor: IP-literal and .local hosts
        .addInterceptor(ua)                                           // application interceptor: once per call, kept on redirects
        .apply { debugInterceptors.forEach(::addInterceptor) }
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        // followRedirects/followSslRedirects/retryOnConnectionFailure: OkHttp defaults (true)
    val core: OkHttpClient = coreBuilder(LocalNetworkGuard.Mode.STRICT).build()
    val youtube: OkHttpClient = core.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
    // Guard mode SYNC: passes local addresses only while LocalNetworkAccess.syncAllowed (10's LocalNetworkPermissionGate);
    // no AuthInterceptor: SyncClient adds the device token per request (10)
    val sync: OkHttpClient = coreBuilder(LocalNetworkGuard.Mode.SYNC).callTimeout(60, TimeUnit.SECONDS).build()
}

@SingleIn(AppScope::class)
class NetworkClients @Inject constructor(private val coreClients: CoreClients, auth: AuthInterceptor) {
    private val base = coreClients.core.newBuilder().addNetworkInterceptor(auth).build()   // re-evaluated on every redirect hop
    val feed = base.newBuilder().followRedirects(false).followSslRedirects(false)          // 03 follows the chain itself (Ktor, S12)
        .callTimeout(120, TimeUnit.SECONDS).build()
    val api = base.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    val image = base.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    val media = base.newBuilder().addInterceptor(IdentityEncodingInterceptor).build()
    val download = base.newBuilder().readTimeout(60, TimeUnit.SECONDS).addInterceptor(IdentityEncodingInterceptor).build()
    operator fun get(kind: HttpClientKind): OkHttpClient = when (kind) {
        FEED -> feed; API -> api; IMAGE -> image; MEDIA -> media; DOWNLOAD -> download
        YOUTUBE -> coreClients.youtube; SYNC -> coreClients.sync }
}
/** :ytx per-call IP-family pinning (D74): same pool and dispatcher; every host resolves to [family]'s records only
 *  (all records when it has none). Cached per family. */
fun OkHttpClient.pinnedToFamily(family: IpFamily): OkHttpClient

// :core:network (commonMain)
interface NeutrodyneHttpClients { fun client(kind: HttpClientKind): HttpClient }   // one HttpClient per kind, app-lifetime
// androidMain and desktopMain (the same few lines, wiring the island):
//   HttpClient(OkHttp) { engine { preconfigured = networkClients[kind] }; expectSuccess = false
//                        followRedirects = kind != FEED
//                        if (kind == DOWNLOAD) install(HttpRedirect) { allowHttpsDowngrade = true }   // 07's chain (D28), never with credentials
//                        install(ContentNegotiation) { json(NeutrodyneJson) }
//                        install(UserAgent) { agent = userAgent.value } }   // UserAgentProvider (:core:common)
```

Ktor's own timeouts (`HttpTimeout`) are not installed: the OkHttp clients carry them. `expectSuccess = false` because each area's response-code table owns HTTP statuses ([03](03-feeds-and-discovery.md#fetch-pipeline), [07](07-downloads.md#transfer-core), [10 Protocol](10-sync.md#protocol)).

| Kind | Consumer (owner) | Timeouts (connect / read / call) | Extras |
|---|---|---|---|
| FEED | `FeedFetcher` (03, common, on Ktor) | 15 s / 30 s / 120 s | redirects off in OkHttp and Ktor: 03 follows the chain itself and records 301/308 hops; 32 MB cap and streaming SHA-256 in 03 (Okio `HashingSink`); no `Accept-Encoding` override (OkHttp gzip) |
| API | Apple, fyyd, Podcast Index search (03); small JSON calls such as oEmbed (04) and Podcasting 2.0 chapters JSON (06); the approved engine manifest, its signature and yt-dlp's `SHA2-256SUMS`/`.sig` (04, M9b; MD3 on the desktop); the update check's single GET of `neutrodyne-update.json` (09 `GitHubUpdateSource`, M11a; `releases/latest/download` only, never `api.github.com`; the APK or installer itself is downloaded by the user's browser, never by the app) | 15 s / 30 s (inherited) / 8 s | the 8 s call timeout caps the whole call and equals the canonical per-provider timeout |
| IMAGE | Coil `OkHttpNetworkFetcherFactory` (08) | 15 s / 20 s / 60 s | Coil disk cache only |
| MEDIA | Media3 `OkHttpDataSource.Factory` (06, Android); `HttpByteSource` (11, desktop) | 15 s / 30 s / none | `Accept-Encoding: identity` (byte-exact ranges, `SimpleCache` and `SpanCache` keys) |
| DOWNLOAD | the transfer core of 07 (common, on Ktor); the `yt-dlp` engine file, ≤ 10 MB (04, M9b; MD3); never an app APK or installer (the apps download no updates, [D78](../PLAN.md#3-key-decisions)) | 15 s / 60 s / none | `Accept-Encoding: identity`; 07's Ktor transfer core follows `https → http` hops without credentials (`HttpRedirect.allowHttpsDowngrade = true`, D28, like 03's feed chain; 07 asserts it in tests); `OkHttpEngineHttp` (04) calls this OkHttp client directly and keeps 04's HTTPS-only rule, redirects included (its derived client sets `followSslRedirects(false)`) |
| YOUTUBE | `PyHttp` in `:ytx` (04, M9a): every request yt-dlp makes on Android (InnerTube, watch page) | 15 s / 30 s / 60 s | derived from the credential-free core (no `AuthInterceptor`); per call `pinnedToFamily(family)` with the family the main process passes ([D74](../PLAN.md#3-key-decisions)); cancellation of a call cancels its OkHttp `Call`s (04) |
| SYNC | `SyncClient` and `SyncEventsClient` (10, MS2) | 15 s / 30 s / 60 s; the SSE call derives a client with no read and no call timeout (the heartbeat bounds it, 10) | derived from the credential-free core: no `AuthInterceptor` (the device token is added per request by `SyncClient`, never by a shared interceptor); the only client that may pass the Android LAN guard, and only while `LocalNetworkAccess.syncAllowed` is true ([LAN guard](#interceptors)) |

`IdentityEncodingInterceptor` is an application interceptor that sets `Accept-Encoding: identity`, so OkHttp's bridge neither adds gzip nor decompresses. Dispatcher note: Ktor's OkHttp engine, `executeAsync()`, Media3's `OkHttpDataSource` and Coil all go through the dispatcher's async queue; 64 global / 8 per host leaves headroom above the per-area semaphores (feeds 6/2, downloads 3/2, YouTube 1) owned by 03 and 07. S12 verified (2026-10-06): Ktor 3.6.0's OkHttp engine rebuilds the `preconfigured` client with `newBuilder()`, so pool, dispatcher, DNS chain and interceptors are shared, and redirect, cancellation and streaming semantics survive — the whole 03 matrix passed in `desktopTest`.

### Interceptors

All interceptors and the DNS chain live in the island and run identically on Android and the desktop, except the LAN guard, which is active only on Android API 37+.

**User-Agent on Ktor calls (2026-10-05).** Ktor's engine layer adds `User-Agent: ktor-client` to every request that has none before OkHttp's interceptors run (`mergeHeaders`: `if (missingAgent && needUserAgent()) block(HttpHeaders.UserAgent, KTOR_DEFAULT_USER_AGENT)`, [Utils.kt](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-core/common/src/io/ktor/client/engine/Utils.kt)), so the interceptor below would never see an empty header on feeds, downloads, directory search or sync. `NeutrodyneHttpClients` therefore installs Ktor's `UserAgent` plugin, which sets the header only when the request has none ([UserAgent.kt](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/UserAgent.kt)), with the string of `UserAgentProvider` (`:core:common`, built once from `PlatformInfo` and the version name), the same provider the interceptor uses; `KtorUserAgentTest` asserts that a feed request through `NeutrodyneHttpClients` reaches MockWebServer with the Neutrodyne User-Agent and never `ktor-client`.

**`UserAgentInterceptor`** sets `User-Agent: Neutrodyne/<versionName> (<platform>; +<REPO_URL>)` (from `UserAgentProvider`) **only when the request has none** (OkHttp-only callers: Media3, Coil, `HttpByteSource`, `PyHttp`), where `<platform>` comes from `PlatformInfo` (`Android 17`; `Windows 11; x64`; `macOS 15.1; arm64`; `Linux; x64`), so the client-specific User-Agents that yt-dlp sets per request (passed through `PyHttp` unchanged) and any 04-mandated UA survive. Non-ASCII characters are replaced with `?` (OkHttp rejects non-ASCII header values). Some hosts reject generic UAs, so the UA is never empty or the OkHttp default.

**`AuthInterceptor`** (Basic auth for private feeds and their same-origin enclosures, [03](03-feeds-and-discovery.md#feed-moves-auth-and-paging)):

```kotlin
// :core:common (commonMain): the lookup contract, implemented by 03's SecretStore implementations
data class Origin(val scheme: String, val host: String, val port: Int)   // lowercase scheme/host, explicit port
fun interface CredentialLookup {                       // in-memory, non-blocking; returns "Basic …" or null
    fun basicAuthorization(origin: Origin): String?
    suspend fun awaitLoaded() {}                       // SecretStore: suspends until rows are decrypted into memory
    companion object { val None = CredentialLookup { null } }   // M0a binding until the SecretStore implementations (M1b)
}

// :core:network:okhttp
fun Origin.Companion.of(url: HttpUrl) = Origin(url.scheme, url.host.lowercase(), url.port)
internal class AuthInterceptor @Inject constructor(private val lookup: CredentialLookup) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val value = lookup.basicAuthorization(Origin.of(request.url)) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", value).build())
    }
}
```

Contract: credentials are attached **only when the hop's origin (scheme, host, port) equals the stored credential's origin**; because it is a network interceptor and OkHttp builds redirect follow-ups from the pre-network request, every hop is re-evaluated and an `https → http` or cross-host redirect never carries credentials (with FEED's redirects off, each hop of 03's manual chain is a new call and is evaluated the same way). The `SecretStore` implementations (03: `KeystoreCredentialStore` on Android, `DesktopSecretStore` on the desktop) keep the decrypted lookup map in memory and implement `CredentialLookup` (initializer 120 loads it); the sync token's `sync:<host>` origin never matches an HTTP origin, so it is never sent by this interceptor (10). Callers that must not send an unauthenticated first request — `FeedFetcher` (03) and the transfer core in `:download:impl` (07), which cannot see the store — call `awaitLoaded()` first. `credential.origin` uses the same `scheme://host:port` normalisation ([02 credential](02-data-model.md#credential)).

**LAN guard (`LocalNetworkGuardDns` + `LocalNetworkGuardInterceptor`, Android only).** On devices with `Build.VERSION.SDK_INT >= 37` the app (targetSdk 37) cannot reach LAN hosts without `ACCESS_LOCAL_NETWORK`, and TCP connections to them "typically result in a timeout error" rather than a clear failure ([Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)). LAN *feeds* stay unsupported on Android in v1.0 ([D28](../PLAN.md#3-key-decisions)), so the guard fails fast instead, with `LocalNetworkUnsupportedException` (a subclass of `UnknownHostException`):

- `LocalNetworkGuardDns` wraps the resolver chain and throws when **every** resolved address is local — IPv4 10/8, 172.16/12, 192.168/16, 169.254/16; IPv6 `fc00::/7`, `fe80::/10` — and the host is not loopback. Mixed public/private answers pass.
- `LocalNetworkGuardInterceptor` (first application interceptor) throws for a request whose host is an IP literal in those ranges or ends in `.local` (mDNS). It exists because OkHttp does not call `Dns` for IP-literal hosts (`RouteSelector` returns the parsed address directly, [source](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RouteSelector.kt)). Known gap: a redirect hop to an IP-literal LAN host is not seen by application interceptors and ends as an ordinary connect timeout (`NetError.Timeout`).
- **The sync exception** ([D28](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions)): the SYNC client's guard elements consult `LocalNetworkAccess.syncAllowed` (`:core:common`, a `StateFlow<Boolean>` with one writer); only 10's `LocalNetworkPermissionGate` (`:sync:impl` `androidMain`) sets it — true below API 37 or while `ACCESS_LOCAL_NETWORK` is granted for a sync server that sync setup classified as local ([10 Client sync engine](10-sync.md#client-sync-engine)). Every other client keeps the guard, so LAN feeds still show `LocalNetworkUnsupported` (PLAN MS2 AC5).
- Below API 37 both are pass-throughs (LAN feeds keep working there). Loopback (`127.0.0.0/8`, `::1`) always passes. **On the desktop the guard is always a pass-through**: there is no platform LAN rule, so LAN feeds and a LAN sync server work (macOS may show its Local Network prompt, [D28](../PLAN.md#3-key-decisions), [10 Security](10-sync.md#security)).
- Android's [local network definition](https://developer.android.com/privacy-and-security/local-network-definition) (checked 2026-10-05) also counts 100.64/10, multicast, broadcast and IPv6 addresses on directly-connected routes, and excludes VPN and cellular interfaces; a LAN DNS server on port 53 is exempt. The feed guard keeps its static ranges as a conservative approximation (a miss ends as an ordinary connect timeout); the sync gate classifies precisely with the active network's interfaces and routes ([10 Local-network gate](10-sync.md#local-network-gate)).

**`DnsFamilyHints` / `FamilyHintDns`** (requested by [04 IP-family matching](04-youtube.md#ip-family-matching)): `DnsFamilyHints` (`@SingleIn(AppScope)`, `:core:network:okhttp`) holds `hostSuffix → IpFamily?` pairs in memory (`set(hostSuffix: String, family: IpFamily?)`, `null` clears). `FamilyHintDns` returns only the A (`V4`) or only the AAAA (`V6`) records for a host equal to or ending in `.` + a hinted suffix, and all records when that family has none or no hint exists. `IpFamily` is 04's enum; it is declared in `:core:model` (not `:youtube:api`) so that the island can read it under rule 14. All derived clients inherit the chain, so MEDIA and DOWNLOAD requests to `googlevideo.com` follow the hint. Both processes build the chain ([D74](../PLAN.md#3-key-decisions)): in the main process 04's resolver sets the `googlevideo.com` hint from the `ip=` of each resolved URL; in `:ytx`, whose `DnsFamilyHints` stays empty, `PyHttp` pins each call with `pinnedToFamily(family)` instead, because two concurrent calls may ask for different families. Fallback when the bridge is unavailable (A2 host): yt-dlp's own urllib handler with `source_address` forcing the family (04).

### Network error taxonomy

```kotlin
// :core:model — what other documents store and show (podcast.lastErrorKind, download.lastError, sync problems, UI strings)
sealed interface NetError {
    data object Offline : NetError                     // no validated network at failure time
    data object Timeout : NetError
    data object DnsFailure : NetError
    data object ConnectionFailed : NetError            // refused, reset, unreachable
    data object LocalNetworkUnsupported : NetError     // Android 17 LAN host, v1 policy (D28)
    data class Tls(val kind: TlsKind) : NetError
    data object Cancelled : NetError
    data class Other(val type: String) : NetError      // exception class simple name, for diagnostics
}
enum class TlsKind { UNTRUSTED_CERTIFICATE, CERTIFICATE_TRANSPARENCY, HANDSHAKE }

// :core:network (commonMain); implementations in androidMain/desktopMain delegate to the island's JvmNetErrors
interface NetErrorClassifier { fun classify(e: Throwable): NetError }   // CancellationException is rethrown, never classified
```

The classification is JVM logic (it inspects `java.net` and `javax.net.ssl` types), so it lives once in the island as `JvmNetErrors.classify(e, connected)`; `:core:network`'s `androidMain` and `desktopMain` each bind a three-line `NetErrorClassifier` that passes `NetworkMonitor.status.value.isConnected`. The cause chain is inspected, so a Ktor exception wrapping an OkHttp or `java.io` cause classifies like the cause.

| Exception (cause chain inspected) | `NetError` |
|---|---|
| `LocalNetworkUnsupportedException` | `LocalNetworkUnsupported` |
| `UnknownHostException` while `NetworkMonitor.status.isConnected == false` | `Offline` |
| other `UnknownHostException` | `DnsFailure` |
| `SocketTimeoutException`, `InterruptedIOException("timeout")`; Ktor `HttpRequestTimeoutException`, `ConnectTimeoutException`, `SocketTimeoutException` (`io.ktor.client.network.sockets`) | `Timeout` |
| `ConnectException`, `NoRouteToHostException`, `SocketException` | `ConnectionFailed` (or `Offline` if disconnected) |
| `SSLHandshakeException` whose chain mentions "Certificate Transparency" (case-insensitive) | `Tls(CERTIFICATE_TRANSPARENCY)` — Unverified failure text |
| `SSLHandshakeException` with `CertPathValidatorException` / "Trust anchor" | `Tls(UNTRUSTED_CERTIFICATE)` |
| other `SSLException` | `Tls(HANDSHAKE)` |
| `IOException("Canceled")` from a cancelled call | `Cancelled` |
| anything else | `Other(simpleName)` |

HTTP status handling is not part of this taxonomy; each area's response-code table owns it ([03](03-feeds-and-discovery.md#fetch-pipeline), [07](07-downloads.md#transfer-core), [10 Protocol](10-sync.md#protocol)). Unverified: Ktor 3.6.0's exact timeout exception types with the OkHttp engine (S12 maps whatever the engine throws).

### NetworkMonitor

```kotlin
// :core:common (interface, so features and domain can observe it)
data class NetworkStatus(val isConnected: Boolean, val isValidated: Boolean, val isMetered: Boolean, val isVpn: Boolean)
interface NetworkMonitor { val status: StateFlow<NetworkStatus> }
```

**Android:** `ConnectivityNetworkMonitor` (`:core:network` `androidMain`) registers one `registerDefaultNetworkCallback` for the process, maps `NetworkCapabilities` (`isMetered = !(NOT_METERED || TEMPORARILY_NOT_METERED on API 30+)`, `isValidated = NET_CAPABILITY_VALIDATED`, `isVpn = TRANSPORT_VPN`), seeds the initial value synchronously from `activeNetwork`, and shares with `stateIn(appScope, SharingStarted.Eagerly, initial)`. Eagerly, not `WhileSubscribed`: `NetErrorClassifier`, 03's validator rule and 07's claim conditions read `status.value` without collecting, and a `WhileSubscribed` flow would hand them a stale value. One process-lifetime callback costs nothing measurable. Data Saver handling is 07's.

**Desktop:** `DesktopNetworkMonitor` (`:core:network` `desktopMain`). The JVM has no network-change callback, so it inspects the local interfaces with `java.net.NetworkInterface` every 15 s, immediately after `PowerMonitor` reports a resume, and after a failure classified as `Offline`, `DnsFailure` or `ConnectionFailed`: `isConnected` = some interface is up, not loopback, not point-to-point-only, and has a non-link-local unicast address; `isValidated = isConnected` (no captive-portal detection on the desktop); `isMetered = false` in v1.0 ([D85](../PLAN.md#3-key-decisions); metered and Wi-Fi-only rows say "Not used on computers", 11); `isVpn` = an up interface named like `tun*`, `utun*`, `wg*`, `ppp*` or `tap*` (diagnostics only). It **never probes a remote host**: N3 allows traffic only to hosts the user chose. Unverified: interface naming and virtual adapters (Hyper-V, Docker, VirtualBox) on each OS; a virtual adapter can make `isConnected` true without a route, which only delays the `Offline` error to the first failed request (S13 records the behaviour).

### Network security config

`app/src/main/res/xml/network_security_config.xml`, referenced by the app manifest (moved from `:core:network`, which is a KMP module now and has no Android resources):

```xml
<network-security-config>
    <!-- D28 / PO-13: cleartext allowed (many feeds, enclosures and covers are http://); system CAs only -->
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
    <!-- Applies only while android:debuggable is true, i.e. only in local debug builds (release builds are not debuggable, D96) -->
    <debug-overrides>
        <trust-anchors><certificates src="system" /><certificates src="user" /></trust-anchors>
    </debug-overrides>
</network-security-config>
```

- **`<debug-overrides>`** applies only when `android:debuggable` is `true` and is ignored otherwise ([Network security config](https://developer.android.com/privacy-and-security/security-config)); since published APKs are non-debuggable release builds (checked by `verifyManifestPermissions` and 09's `check-apk.sh --published`), user CAs are trusted only in local `debug` builds, for proxy debugging.
- `android:usesCleartextTraffic` is never set (Android 17 announces its deprecation).
- **Certificate Transparency** is enforced by default for targetSdk 37. The schema does allow `<certificateTransparency enabled="false"/>` in `base-config` or a `domain-config` ([Network security config](https://developer.android.com/privacy-and-security/security-config)), but v1 uses neither: feed hosts are user-chosen, so a static per-domain list cannot help, and a global opt-out would weaken every connection. A CT or untrusted-CA failure is a per-feed error, never a silent drop ([03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)); for a sync server with a self-signed or private-CA certificate it is `CertificateRejected` (10). Certificates from the user store (trusted only in debug builds) are not CT-checked by the platform.
- **Desktop TLS** uses the bundled runtime's default trust store (the `cacerts` of the Temurin image, unmodified), with no user-CA override and no CT enforcement of our own (Unverified: the JDK performs no CT check by default); a private-CA sync server on the desktop needs a certificate the runtime trusts, as 10's help explains.
- **Scheme-less user input tries `https://` first** — implemented by 03's input normalisation, not here.
- **ECH:** for targetSdk 37 the platform uses Encrypted Client Hello when the networking library integrates it ([Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)); OkHttp 5.5.0's ECH support is opt-in ([OkHttp changelog](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md)) and is not enabled in v1 (revisit in v1.x).

---

## Platform compliance

Serves N2, N7. Delivered in M0a (Android checklist and manifest), M0b (desktop list), verified in M11b (Android) and MD5 (desktop). Honours [D43](../PLAN.md#3-key-decisions), [D5](../PLAN.md#3-key-decisions), [D85](../PLAN.md#3-key-decisions), [D88](../PLAN.md#3-key-decisions). Every rule that binds an Android app with minSdk 26 / targetSdk 37, mapped to Neutrodyne's mechanism and the owning document. Row IDs P1–P41 are platform-compliance rows, cited elsewhere as "01 Pn"; they are unrelated to PLAN's risk IDs P3–P15, which every document cites as "risk Pn". Rows P36–P39 no longer apply since 2026-10-05 (no in-app install) and P40 since the scope revision of 2026-10-05 (release builds); all keep their IDs ([PLAN 8](../PLAN.md#8-risks-and-mitigations)). The desktop rules follow in [Desktop compliance](#desktop-compliance) below.

| # | Rule (platform version, scope) | Neutrodyne mechanism | Owner |
|---|---|---|---|
| P1 | No store enforces a target API any more (GitHub-only), but the target still decides behaviour: A15 refuses to install apps with `targetSdkVersion` < 24 ([A15 all apps](https://developer.android.com/about/versions/15/behavior-changes-all)) | keep targeting the newest API: targetSdk 37, raised with each platform release | 01, 09 |
| P2 | A15 (target 35) edge-to-edge enforced; A16 (target 36) opt-out removed | `enableEdgeToEdge()` in `MainActivity`; never `windowOptOutEdgeToEdgeEnforcement`; insets per [08 Adaptive layouts](08-ui-ux.md#adaptive-layouts) | 01, 08 |
| P3 | A16 (target 36) predictive back on by default; `onBackPressed()` not called, `KEYCODE_BACK` not dispatched | Nav3 back; `NavigationBackHandler`/`PredictiveBackHandler`; `android:enableOnBackInvokedCallback="true"` for Android 13–15 devices (Unverified necessity at target 37); `onBackPressed` overrides banned | 01, 08 |
| P4 | A16 (target 36, sw ≥ 600 dp) orientation/resizability/aspect locks ignored; A17 (target 37) opt-out removed | no `screenOrientation`, `resizeableActivity="false"`, `maxAspectRatio`, `minAspectRatio`; designed layouts per [08](08-ui-ux.md#adaptive-layouts) | 01, 08 |
| P5 | A14 FGS types mandatory with matching `FOREGROUND_SERVICE_*` permission | `mediaPlayback` ([06](06-playback.md#service-architecture)); `dataSync` only via WorkManager's `SystemForegroundService` ([07](07-downloads.md#runners-and-scheduling)) | 06, 07 |
| P6 | A15 `dataSync` FGS limited to 6 h / 24 h; `mediaPlayback` and `dataSync` FGS cannot start from `BOOT_COMPLETED` | `dataSync` used only for API 26–33 manual downloads (D47); no FGS from boot; resumption via `MediaButtonReceiver` + `onPlaybackResumption` ([06 System surfaces](06-playback.md#system-surfaces)) | 06, 07 |
| P7 | A16 (all apps) job runtime quota also applies to jobs started while visible and continuing, and to jobs running beside an FGS | resumable workers, 8-min soft deadlines, stop reasons logged; UIDT for manual downloads ([03](03-feeds-and-discovery.md#refresh-scheduling), [07](07-downloads.md#runners-and-scheduling)) | 03, 07 |
| P8 | A14 UIDT jobs: `RUN_USER_INITIATED_JOBS`, schedulable only while visible, `setNotification` within 10 s of `onStartJob`, not quota-bound | `ManualDownloadJobService`, namespace `downloads`, `JOB_ID_MANUAL = 1001` | 07 |
| P9 | A14 (target 34) JobScheduler network constraints require `ACCESS_NETWORK_STATE` | declared by `:core:network` | 01 |
| P10 | A14+ tasks that time out too often can push the app into the restricted bucket | soft deadlines everywhere; diagnostics shows the bucket ([09](09-quality-and-release.md#crash-reporting-and-diagnostics)) | 03, 07, 09 |
| P11 | A17 (all apps) background audio hardening: playback/focus/volume need a visible activity or a non-`shortService` FGS; target 37 requires while-in-use capability; failures are silent | [D43](../PLAN.md#3-key-decisions) start paths; "Tap to resume" after demotion; `set-enable-hardening throw` test ([06 Background restrictions](06-playback.md#background-restrictions)) | 06 |
| P12 | A15 (target 35) audio focus only for the top app or an app running an FGS | focus requested only by the playback service | 06 |
| P13 | A12+ background FGS-start restrictions (exemptions include notification/widget interaction and media buttons; running a job is not an exemption) | playback starts from UI, notification, media key; `onForegroundServiceStartNotAllowedException` handled ([06](06-playback.md#background-restrictions)); downloads never start an FGS from a job on API 34+ | 06, 07 |
| P14 | A17 (target 37) Certificate Transparency on by default (opt-out possible globally or per domain in the network security config) | opt-out not used; `NetError.Tls(CERTIFICATE_TRANSPARENCY)` surfaced per feed | 01, 03 |
| P15 | A17 (target 37) `ACCESS_LOCAL_NETWORK` runtime permission (`NEARBY_DEVICES` group); without it TCP to LAN hosts typically times out, UDP fails with `EPERM` ([Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)) | Declared by `:sync:impl` (MS2) and requested **only** in sync setup, contextually, when the configured sync server resolves to a local address on API 37+ (rationale first, 08; [10 Client sync engine](10-sync.md#client-sync-engine)); while granted, only the `SYNC` client passes the [LAN guard](#interceptors); LAN feeds stay unsupported and fail fast with `LocalNetworkUnsupported` ([D28](../PLAN.md#3-key-decisions), amended 2026-10-05) | 01, 03, 10 |
| P16 | Cleartext blocked by default since target 28; A17 (all apps) plans to deprecate `usesCleartextTraffic` | network security config `base-config cleartextTrafficPermitted="true"` | 01 |
| P17 | User-installed CAs not trusted since target 24; `<debug-overrides>` trust anchors apply whenever the app is debuggable | release builds are not debuggable, so they trust system CAs only; the shared file's `<debug-overrides>` adds user CAs in local `debug` builds for proxy debugging ([Network security config](#network-security-config)) | 01 |
| P18 | A17 (all apps) RAM-based per-app memory limits | bounded Coil memory cache and sized decodes ([08](08-ui-ux.md#artwork-pipeline)); streaming parse, no whole-feed strings ([03](03-feeds-and-discovery.md#parser)) | 08, 03 |
| P19 | A17 (target 37) widget `RemoteViews` bitmap memory cap | v1.x widgets pass artwork as content-URI icons | 08 |
| P20 | A17 (target 37) reflection on `static final` fields blocked; lock-free `MessageQueue` | our code uses neither; library impact checked by the API 37 instrumented smoke run (M0a, including Chaquopy's `selftest` in `:ytx` when S7 is go) and the YouTube smoke test through `:ytx` on the release APKs (M9a). Unverified: impact on Chaquopy's Java interop and on LeakCanary (debug builds only) | 01, 04, 09 |
| P21 | 16 KB page sizes (devices since A15): on a 16 KB device an app whose native libraries are not 16 KB-aligned runs only in a compatibility mode with a warning (A16+) ([page sizes](https://developer.android.com/guide/practices/page-sizes)) | native code: `sqlite-bundled` ([S6](#s6-sqlite-bundled-16-kb-alignment-and-size)); in the 64-bit APKs CPython's `libpython`, `libcrypto`, `libssl`, `libsqlite3`, Chaquopy's JNI libraries and the `lib-dynload` extension modules that Chaquopy extracts from assets ([S7](#s7-chaquopy-under-agp-941)); quickjs-kt's `.so` if shipped. CI `zipalign -c -P 16` plus `llvm-readelf -l` over every `.so`, including those inside Chaquopy's asset zips (`check-apk.sh`, [09](09-quality-and-release.md#ci-pipelines)) | 01, 04, 09 |
| P22 | A13 `POST_NOTIFICATIONS` runtime permission; media-session notifications exempt; FGS start does not need it | requested contextually (first download, first new-episode opt-in), never at launch ([03](03-feeds-and-discovery.md#new-episode-notifications), [07](07-downloads.md#progress-and-notifications)) | 03, 07 |
| P23 | A13 per-app language (`localeConfig`); AppCompat backport | `generateLocaleConfig = true`, `res/resources.properties` (`unqualifiedResLocale=en-US`), `MainActivity : AppCompatActivity` with an AppCompat theme, `AppLocalesMetadataHolderService` (`autoStoreLocales`) for API ≤ 32; picker UI per [09 Localisation](09-quality-and-release.md#localisation) | 01, 09 |
| P24 | A12 `android:exported` required on components with intent filters; `PendingIntent` mutability flag required | every component declares `exported`; all `PendingIntent`s `FLAG_IMMUTABLE` with explicit components (Unverified source: not re-checked in this research) | 01 |
| P25 | A14 (target 34) implicit intents reach only exported components; mutable `PendingIntent`s with implicit intents throw; runtime receivers need an export flag | internal intents explicit; `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` (Unverified source: not re-checked in this research) | 01 |
| P26 | A11 package visibility | no `queryIntentActivities`/`resolveActivity` probing: `startActivity` + catch `ActivityNotFoundException` ("Watch on YouTube" included); the same for the update card's browser links ([Intent routing](#intent-routing)); `<queries>` entries: ACRA's `mailto` only (Unverified need); never `QUERY_ALL_PACKAGES` | 01, 09 |
| P27 | A16 Safer Intents opt-in (`android:intentMatchingFlags="enforceIntentFilter"`), planned to become default | not adopted in v1 (internal explicit intents carry `neutrodyne://open/…` data that matches no filter); see [Open questions](#open-questions) | 01 |
| P28 | Auto Backup: 25 MB cap; `<include>` disables defaults; A16 QPR2 `cross-platform-transfer` element | include-only rules, no cross-platform section ([05 Auto Backup](05-groups-opml-backup.md#auto-backup), D34) | 05 |
| P29 | No direct battery-optimisation exemption request (N2); exact alarms not needed | no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`; diagnostics links to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` only (N2) | 01, 09 |
| P30 | A14/A15 background-activity-launch hardening for `PendingIntent` senders and creators; A17 adds further BAL hardening (`MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`; Unverified details) | activities start only from a user tap on a notification (sent by the system) or from a visible activity (`ExternalImportActivity` → `MainActivity`); our code never calls `PendingIntent.send()` for an activity and uses no full-screen intents; the update notification opens Settings › Updates through an ordinary tap (09) | 01, 09 |
| P31 | A17 (target 37) Encrypted Client Hello used when the networking library supports it | OkHttp's ECH stays off in v1 ([Network security config](#network-security-config)) | 01 |
| P32 | A17 (target 37) no longer relies on implicit URI read grants for `content://` extras of `ACTION_SEND` (Unverified scope) | every share intent sets `FLAG_GRANT_READ_URI_PERMISSION` and `ClipData` explicitly (05 OPML/backup share, 07 "Share file") | 05, 07 |
| P33 | A10 (target 29+) untrusted apps cannot `execve()` files in their home directory ([A10 behaviour changes](https://developer.android.com/about/versions/10/behavior-changes-10)); A12+ limits child ("phantom") processes | on Android the engine runs in-process in `:ytx` through Chaquopy, with no child process and no exec ([D72](../PLAN.md#3-key-decisions)); `checkBannedApis` allows `ProcessBuilder`/`Runtime.exec` only in the desktop module `:youtube:ytdlp-desktop` ([D90](../PLAN.md#3-key-decisions)), which Android never includes. Only fallback A2 would exec on Android, and only a launcher installed into `nativeLibraryDir` from `jniLibs` (`useLegacyPackaging = true`), with a reviewed exception | 01, 04 |
| P34 | A14 (target 34) dynamically loaded DEX/JAR/APK files must be read-only ([A14 behaviour changes](https://developer.android.com/about/versions/14/behavior-changes-14)) | no DEX/JAR/APK is ever loaded at runtime (`DexClassLoader` banned); engine updates are pure Python, compiled to `.pyc` on the device and made read-only anyway ([D76](../PLAN.md#3-key-decisions), [04 Engine updates](04-youtube.md#engine-updates)) | 01, 04 |
| P35 | A17 (target 37) native files loaded with `System.load()` must be read-only, else `UnsatisfiedLinkError` ([A17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)) | our code never calls `System.load`; Chaquopy loads the extension modules it extracts from assets, and its master (17.1.0) marks them read-only for target 37 — S7 verifies on the API 37 image; engine updates never download native code | 01, 04 |
| P36 | A8 (API 26) installing APKs needs the installing app's `REQUEST_INSTALL_PACKAGES` and the user's per-source "install unknown apps" grant (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, [Settings](https://developer.android.com/reference/android/provider/Settings)) | **Not applicable since 2026-10-05 (no in-app install, PO-31).** Neutrodyne declares no install permission ([Manifest and permissions](#manifest-and-permissions)); the grant now belongs to the browser or file manager that opens the downloaded APK, which 08's Install & updates help explains | 08, 09 |
| P37 | A12 (API 31) unattended self-update with `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | **Not applicable since 2026-10-05 (no in-app install, PO-31)**; the permission is explicitly not requested | — |
| P38 | A14 (API 34) install constraints (`GENTLE_UPDATE`) for installers of record | **Not applicable since 2026-10-05 (no in-app install, PO-31)**; nothing waits on playback or downloads for an update ([D78](../PLAN.md#3-key-decisions)) | — |
| P39 | A16 QPR2 (API 36.1) developer-verification results reported to installers through their sessions | **Not applicable since 2026-10-05 (no in-app install, PO-31)**: Android's own installer applies developer verification when the user installs the downloaded APK, and 08's help page explains what it shows ([D80](../PLAN.md#3-key-decisions), [09 Developer verification](09-quality-and-release.md#developer-verification)) | 08, 09 |
| P40 | Published APKs were debuggable (PO-35's first resolution): `run-as`, JDWP, CheckJNI, `adb backup` of app data | **Not applicable since 2026-10-05 (scope revision):** published APKs are non-debuggable release builds ([D96](../PLAN.md#3-key-decisions)); risks T18 and P11 are retired; P41 holds the release-build rules | — |
| P41 | `android:debuggable` lets an app be debugged even on user builds of Android; `android:testOnly` restricts installs to ADB ([application element](https://developer.android.com/guide/topics/manifest/application-element)); sideloaded apps get no cloud baseline profiles | published APKs are `release` builds: `android:debuggable` is never written in a source manifest (AGP sets it only for `debug`; `checkBannedApis`), `verifyManifestPermissions` rejects `android:debuggable="true"` and `testOnly` in the merged `release` manifest, and 09's `check-apk.sh --published` and `aapt2 dump badging` (PLAN M0 AC7) check the packaged APKs; R8 keep rules cover every reflective and JNI entry point ([Release build and baseline profiles](#release-build-and-baseline-profiles)); ProfileInstaller installs the bundled baseline profile on first start | 01, 09 |

### Desktop compliance

Delivered in M0b (single instance, directories, native-access flag, image checks), MD2 (OS registrations, power), MD5 (final audit). The desktop has no store review or platform policy of its own beyond the OS gates ([D80](../PLAN.md#3-key-decisions)); these rules make N2 and N7's desktop clauses checkable. IDs DC1–DC10 are cited as "01 DCn".

| # | Rule | Mechanism | Owner |
|---|---|---|---|
| DC1 | One process per user (Room has no multi-instance invalidation off Android; N7) | `SingleInstanceLock` before the database opens; a second launch hands over its arguments and exits; sync, lanes and downloads run only in the lock holder (risk T26) | 11 |
| DC2 | Per-user installation without administrator rights on Windows (N7) | MSI with `perUserInstall = true` and the portable ZIP; macOS: the app is dragged to `/Applications` or `~/Applications`; Linux: DEB/RPM through the package manager (system-wide, `/opt/neutrodyne`) or the per-user tar.gz | 11 |
| DC3 | Data only in the per-OS directories of R8.11 and the user's chosen download folder | `AppDirs` resolves every path ([11 Desktop shell](11-desktop.md#desktop-shell)); `StoragePaths` feeds Room, DataStore and the artwork store; uninstall never deletes user data | 11, 01 |
| DC4 | No process started except the YouTube engine child (N7) | `checkBannedApis` rule 5; links and folders open through `java.awt.Desktop`, OS APIs or D-Bus, never a shell | 01, 11 |
| DC5 | Native code loaded only from the app image, with explicit native access (N7) | launcher flag `--enable-native-access=ALL-UNNAMED`; FFM `SymbolLookup.libraryLookup` with absolute paths inside the image for `ndmedia` and FFmpeg; `System.load` banned; JNI libraries (sqlite-bundled, Skiko, JNA, quickjs-kt) load their own natives ([11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)) | 11 |
| DC6 | The macOS bundle passes `codesign --verify --deep --strict` with its ad-hoc signature (N7) | everything in the bundle signed ad hoc last, after every modification; CI verifies on the macOS runner (09) | 11, 09 |
| DC7 | No global keyboard or input hooks | media keys and headset buttons arrive only through SMTC, Now Playing and MPRIS ([D87](../PLAN.md#3-key-decisions)); in-app shortcuts only while the window has focus (08) | 11, 08 |
| DC8 | Playback never starts by itself: not at start-up, after wake, from sync or from a handoff (N2) | the restored session is published paused; `SessionAdopter` writes state only while idle; "Continue on this device" needs a click ([D43](../PLAN.md#3-key-decisions) analogue, [06 Shared playback core](06-playback.md#shared-playback-core)) | 06, 11 |
| DC9 | Background work only while the app runs; idle sleep inhibited only while playing; the window's close keeps the process only while playing or downloading (N2) | `DesktopJobRunner` in-process; `IdleSleepInhibitor` tied to playback state; close behaviour per [D85](../PLAN.md#3-key-decisions) | 11 |
| DC10 | Network: no LAN restriction of our own; macOS Local Network privacy for a LAN sync server | the LAN guard is a pass-through on the desktop ([Interceptors](#interceptors)); the bundle declares `NSLocalNetworkUsageDescription`; the help explains the prompt (risk SR4, [10 Security](10-sync.md#security)) | 01, 10, 11 |

---

## Manifest and permissions

Serves N2, N7, N3, N13, R6.2–R6.3 (build side: the update check needs no install permission and no component of its own). Delivered in M0a (app shell), extended in M1a, M3, M4, M5, M6, M9a (`YtxService`), MS2 (`ACCESS_LOCAL_NETWORK`); M11a adds nothing. Android only: the desktop has no manifest (its `Info.plist`, MSI and desktop-entry metadata are 11's). Each module declares what its own code needs in its own manifest — `src/main/AndroidManifest.xml` in `:app` and the Android-only libraries, `src/androidMain/AndroidManifest.xml` in KMP modules — even if a library also merges it, so removing a library never silently drops a permission. `app/policy/permissions.txt` holds the exact expected merged set of the `release` variant; [`verifyManifestPermissions`](#gradle-side-policy-tasks) fails on any difference.

### Permissions

| Permission | Declared by | From | Why |
|---|---|---|---|
| `INTERNET` | `:core:network` | M0a | everything |
| `ACCESS_NETWORK_STATE` | `:core:network` | M0a | `NetworkMonitor`; JobScheduler/WorkManager network constraints (A14) |
| `POST_NOTIFICATIONS` | `:app` | M0a | new-episode, download, import, alert and update-available notifications; requested contextually only |
| `FOREGROUND_SERVICE` | `:playback:impl`, `:download:impl` | M4, M6 | playback FGS; WorkManager foreground worker (API 26–33 manual downloads) |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `:playback:impl` | M4 | `NeutrodynePlaybackService` |
| `FOREGROUND_SERVICE_DATA_SYNC` | `:download:impl` | M6 | `SystemForegroundService` type `dataSync`, only for API 26–33 manual downloads started while visible (D47) |
| `WAKE_LOCK` | `:playback:impl` (+ merged by media3, WorkManager) | M4 | ExoPlayer wake/Wi-Fi locks; WorkManager |
| `RUN_USER_INITIATED_JOBS` | `:download:impl` | M6 | UIDT manual downloads (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | `:download:impl` (+ merged by WorkManager) | M6 | persisted UIDT job (`setPersisted(true)`); WorkManager reschedules — never starts an FGS |
| `ACCESS_LOCAL_NETWORK` | `:sync:impl` (`androidMain`) | MS2 | runtime permission (`NEARBY_DEVICES` group, API 37+), requested **only** in sync setup when the user's sync server resolves to a local address; never for feeds ([D28](../PLAN.md#3-key-decisions), P15, [10 Client sync engine](10-sync.md#client-sync-engine)). Unverified: whether declaring it changes anything on API < 37 (it should be unknown to older platforms and ignored; S17 checks on API 26 and 36) |
| `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | merged by `androidx.core` (declared `signature`-level and used by the app itself) | M0a | `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` on API < 33 (P25). Because the signing key is public, any app signed with it holds this permission, so such receivers treat every intent as untrusted: validate extras, only route or refresh state, never change data without a user action (2026-10-05, [09 Public key trade-offs](09-quality-and-release.md#public-key-trade-offs)). Unverified exact merged name; the first `verifyManifestPermissions` run in M0a shows it, and `permissions.txt` lists it with the release `applicationId` (`ch.lkmc.neutrodyne.…`; the `debug` variant's `.debug` name is not checked) |

The set is closed (N7). It holds **no install permission**: the update check only links to the GitHub release and the user installs with Android's installer ([D78](../PLAN.md#3-key-decisions)), so `permissions.txt` gains nothing in M11a. `:youtube:ytdlp` declares no permission: `:ytx` uses the app's `INTERNET`. `ACCESS_LOCAL_NETWORK` is the only runtime permission besides `POST_NOTIFICATIONS`, and both are requested contextually, never at launch.

**Explicitly not requested** (adding any requires a PLAN amendment): `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `MANAGE_EXTERNAL_STORAGE` (downloads use app-specific storage, D48), `FOREGROUND_SERVICE_SPECIAL_USE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` and the other `NEARBY_DEVICES` permissions (only `ACCESS_LOCAL_NETWORK` is used), `QUERY_ALL_PACKAGES`, `INSTALL_PACKAGES` (privileged), `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (the app installs nothing, [D78](../PLAN.md#3-key-decisions); `checkBannedApis` also rejects them), `REQUEST_DELETE_PACKAGES`, `SYSTEM_ALERT_WINDOW`, any location permission, `com.google.android.gms.permission.AD_ID`. A library that merges one of these is fixed with `tools:node="remove"` in `:app` and a comment.

### Application element and components

```xml
<!-- Merged view of the release variant (sketch). Comments name the declaring module and owning document. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
  <queries>  <!-- :app (09): ACRA mail sender; Unverified need -->
    <intent><action android:name="android.intent.action.SENDTO" /><data android:scheme="mailto" /></intent>
  </queries>
  <!-- android:debuggable is never written here: AGP sets it to true only for the local debug build type; release,
       benchmarkRelease and nonMinifiedRelease are not debuggable; android:testOnly must never appear (P41) -->
  <application
      android:name=".NeutrodyneApplication"
      android:label="@string/app_name" android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round"
      android:theme="@style/Theme.Neutrodyne"
      android:supportsRtl="true"
      android:appCategory="audio"
      android:enableOnBackInvokedCallback="true"
      android:networkSecurityConfig="@xml/network_security_config"
      android:allowBackup="true"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules"
      android:hasFragileUserData="true">
    <!-- localeConfig is generated by generateLocaleConfig from :app's res/values-*/strings.xml (app_name per locale, D83) -->

    <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop"
              android:theme="@style/Theme.Neutrodyne.Starting"
              android:windowSoftInputMode="adjustResize">          <!-- :app; subscribe/share filters: 03 -->
      <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
    </activity>
    <activity android:name=".ExternalImportActivity" android:exported="true" />   <!-- :app; filters and theme: 05 (M3) -->

    <service android:name="ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService" android:exported="true"
             android:foregroundServiceType="mediaPlayback" />   <!-- :playback:impl (M4); intent filters: 06 -->
    <receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true" />   <!-- :playback:impl (M5); MEDIA_BUTTON filter: 06 -->
    <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />  <!-- :playback:impl (M5); plain meta-data, no GMS code -->

    <service android:name="ch.lkmc.neutrodyne.download.impl.ManualDownloadJobService" android:exported="false"
             android:permission="android.permission.BIND_JOB_SERVICE" />                       <!-- :download:impl androidMain (M6) -->
    <service android:name="androidx.work.impl.foreground.SystemForegroundService"
             android:foregroundServiceType="dataSync" tools:node="merge" />                       <!-- :download:impl androidMain (M6) -->
    <receiver android:name="ch.lkmc.neutrodyne.download.impl.DownloadActionReceiver" android:exported="false" />  <!-- :download:impl androidMain (M6) -->
    <receiver android:name="ch.lkmc.neutrodyne.core.data.youtube.YouTubeAlertActionReceiver" android:exported="false" />  <!-- :core:data androidMain (M9a); breaker notice actions: 04 -->
    <receiver android:name="ch.lkmc.neutrodyne.SnapshotNowReceiver" android:exported="true"
              android:permission="android.permission.DUMP" />   <!-- :app (M3); no intent filter; shell-only bmgr snapshot trigger (D2 exception): 05 -->

    <service android:name="ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxService" android:process=":ytx"
             android:exported="false" />           <!-- :youtube:ytdlp (M0a while S7 is go: ping/selftest only; engine M9a); bound only by YtDlpClient and tests; lifecycle: 04 -->

    <provider android:name="ch.lkmc.neutrodyne.core.artwork.ArtworkProvider" android:authorities="${applicationId}.artwork"
              android:exported="true" />                       <!-- :core:artwork androidMain (M4); read-only contract: 08 -->
    <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider"
              android:exported="false" android:grantUriPermissions="true">                        <!-- :app (M3); paths: 05, 07 -->
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
    </provider>
    <provider android:name="androidx.startup.InitializationProvider" android:authorities="${applicationId}.androidx-startup"
              tools:node="merge">                                                                  <!-- :app (M0a) -->
      <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove" />
      <!-- ProfileInstallerInitializer stays: it installs the release build's baseline profile (Release build and baseline profiles) -->
    </provider>
    <service android:name="androidx.appcompat.app.AppLocalesMetadataHolderService" android:enabled="false" android:exported="false">
      <meta-data android:name="autoStoreLocales" android:value="true" />                             <!-- :app (M0a) -->
    </service>
    <!-- Library-merged and kept: androidx.media3.session.BluetoothValidationActivity (BLUETOOTH_PRIVILEGED-protected, AVRCP workaround);
         androidx.profileinstaller.ProfileInstallReceiver (exported, protected by android.permission.DUMP, used by Macrobenchmark) -->
  </application>
</manifest>
```

Attribute decisions:

| Attribute | Value | Reason / owner |
|---|---|---|
| `allowBackup`, `dataExtractionRules`, `fullBackupContent` | true; include-only rules | [05 Auto Backup](05-groups-opml-backup.md#auto-backup) owns the XML. **M0a ships both files with a single include (`datastore/settings.preferences_pb`)** so tester builds never back up the Room DB before M3 adds the snapshot ([D34](../PLAN.md#3-key-decisions)) |
| `hasFragileUserData` | true | uninstall dialog offers to keep data ([07 Storage layout](07-downloads.md#storage-layout)) |
| `appCategory` | `audio` | system categorisation |
| `configChanges` on `MainActivity` | not declared | recreation is the tested path (state saved through Nav3 and ViewModels); PiP in v1.x revisits this ([06 Video](06-playback.md#video)) |
| `theme` | application: `Theme.Neutrodyne` (parent `Theme.AppCompat.DayNight.NoActionBar`); `MainActivity` only: `Theme.Neutrodyne.Starting` (parent `Theme.SplashScreen`, `postSplashScreenTheme = @style/Theme.Neutrodyne`) | `AppCompatActivity` throws without an AppCompat theme; the splash theme stays on the launcher activity so `ExternalImportActivity` (theme: 05) never shows a splash; no MDC dependency |
| `launchMode` | `singleTop` | notification and deep-link intents arrive in `onNewIntent` |
| `directBootAware` | not set | not supported |
| `intentMatchingFlags` | not set | P27 |
| `android:process` | only `YtxService` (`:ytx`); ACRA's sender declares `:acra` itself | [Application start-up](#application-start-up); `checkBannedApis` rejects any other declaration in our manifests |
| `extractNativeLibs` / `jniLibs.useLegacyPackaging` | S7 kept default packaging (2026-10-06): `.so` files stored, page-aligned and loaded from the APK; legacy packaging compresses them (measured −9.8 MB on the `arm64-v8a` release APK) at the cost of extraction on install, and is required if fallback A2 execs a launcher | 01, recorded in [D77](../PLAN.md#3-key-decisions) if it deviates |
| `<queries>` | ACRA `mailto` only | P26 |
| `debuggable` | not written in any source manifest; AGP injects `true` only for the local `debug` build type and nothing, so `false`, for `release`, `benchmarkRelease` and `nonMinifiedRelease`; the two plugin-created build types are profileable (Unverified: that AGP expresses this as [`<profileable android:shell="true"/>`](https://developer.android.com/guide/topics/manifest/profileable-element) in their merged manifest; 09's first Macrobenchmark run confirms it) | P41, [D96](../PLAN.md#3-key-decisions) |
| `testOnly` | never in the release merged manifest or APK (Android Studio adds it when you click Run, [application element](https://developer.android.com/guide/topics/manifest/application-element); Unverified that a command-line `assembleRelease` never sets it, which PLAN M0 AC7 checks with `aapt2 dump badging`) | `verifyManifestPermissions`; PLAN M0 AC7 |

---

## Licensing and dependency policy

Serves N8. Delivered in M0a (Licensee on `:app`, the Android Python lockfile and its check, About/Licences, contribution rule), M0b (Licensee on `:desktopApp` and `:sync:server`, the runtime lock, the desktop Licences entries for the runtime), MD0/MD1b (`native-components.lock`, `checkNativeLicences`, the FFmpeg source bundle), M9a (Android engine stack complete), MD3 (desktop Python lock), MS1 (server image sources). Honours [D3](../PLAN.md#3-key-decisions) (re-resolved by the scope revision), [D60](../PLAN.md#3-key-decisions), [D89](../PLAN.md#3-key-decisions), [D95](../PLAN.md#3-key-decisions), [PO-1](../PLAN.md#po-1-licensing-of-shipped-binaries) (re-resolved 2026-10-05); mitigates risks L2, L4, L5, L6. YouTube-specific legal analysis and the engine's licence boundary: [04 Licensing and legal](04-youtube.md#licensing-and-legal); runtime-exception and FFmpeg obligations in the packaging pipeline: [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception); the server image: [10 Deployment](10-sync.md#deployment).

### Licence structure

Our own code — Kotlin, the Python shim `neutrodyne_ytx`, the native glue `ndmedia`, scripts, build logic and documentation — is Unlicense (`LICENSE` at the root). Third-party components in a shipped artefact fall into exactly four classes plus the two named cases below the table ([D3](../PLAN.md#3-key-decisions), amended 2026-10-05); nothing else ships, and nothing GPL is linked into or derived from our code:

| Class | Allowed form | Where it occurs |
|---|---|---|
| **Permissive** | Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, PSF-2.0/Python-2.0, Zlib, bzip2-1.0.6, Unicode-3.0, CC0-1.0, public domain (SQLite's `blessing`), Unlicense; Apache-2.0 WITH LLVM-exception; FTL (FreeType, always elected under the FTL, never GPL-2.0), libpng-2.0, IJG (added 2026-10-05 for Skiko's statically embedded FreeType, libpng 1.6.x and libjpeg-turbo 3.1.x — found in `libskiko-linux-x64.so` 0.150.1, which has no `libfreetype` dependency; the FTL credit line and the IJG statement go into About and the README) | every artefact |
| **LGPL, dynamically linked, with its source attached** | LGPL-2.1 or LGPL-3.0 ([D3](../PLAN.md#3-key-decisions)); the FFmpeg build must stay LGPL-2.1-or-later (no `--enable-version3`, risk L6). Loaded at run time as separate, unrenamed shared libraries the user can replace; its notices shipped; its exact corresponding source (pristine tarball, configure lines, build scripts, an empty or real `changes.diff`) attached to every release that ships it | desktop installers: the minimal FFmpeg build ([D86](../PLAN.md#3-key-decisions)); libmpv only if MD0 chooses the fallback; the server image: glibc of the distroless base |
| **MPL-2.0 for unmodified files and data** | an unmodified file that is data, not code we compile or link, or such data mechanically converted by its upstream: CA bundles, public-suffix lists | APKs and desktop installers: certifi's `cacert.pem` with the engine; OkHttp's compiled Public Suffix List (`okhttp3/internal/publicsuffix/PublicSuffixDatabase.list` in `okhttp-jvm` 5.5.0, `assets/PublicSuffixDatabase.list` in `okhttp-android`, checked 2026-10-05; [PSL licence](https://github.com/publicsuffix/list/blob/main/LICENSE)); the JDK's public-suffix data and the image's CA certificates under the runtime exception |
| **The runtime exception** | one unmodified, jlink-trimmed OpenJDK runtime from one vendor (Temurin 25) per release — HotSpot under GPL-2.0, the class library, the jpackage launcher and `wixhelper.dll` under GPL-2.0 WITH Classpath-exception-2.0, the GCC runtime inside `libjvm` under GPL-3.0 WITH GCC-exception-3.1, Microsoft's VC++ redistributables — plus the server image's unmodified distroless base layers; never patched; its `legal/` tree kept; `openjdk-{jdk}-temurin-sources.tar.gz` and `RUNTIME-SOURCES.md` attached to every release that ships it, and `neutrodyne-server-image-sources-{v}.tar.xz` for the image ([OpenJDK GPL-2.0 with the Classpath Exception](https://openjdk.org/legal/gplv2+ce.html), [Adoptium FAQ](https://adoptium.net/docs/faq/)) | desktop installers ([D89](../PLAN.md#3-key-decisions)), the server image ([D95](../PLAN.md#3-key-decisions)); never the APKs, never the server JAR |

**Named cases — PO-48 proposed defaults (2026-10-05, awaiting owner; rejection fallbacks stay as stated: Windows ZIP-only without the MSI case, CPython built by us from upstream sources without PBS patches).** (1) python-build-standalone's own MPL-2.0 build patches, compiled into the CPython it ships (for example `patch-posixmodule-remove-system.patch`), unmodified by us: `python-build-standalone-{pbsTag}-src.tar.gz` (the PBS repository archive at the pinned tag) is attached to every release from MD3 and named in `RUNTIME-SOURCES.md` ([PBS LICENSE](https://github.com/astral-sh/python-build-standalone/blob/main/LICENSE); MPL-2.0 §3.2). (2) MS-RL: the unmodified WiX components jpackage embeds in every MSI — WiX Util's custom action behind `RemoveFolderEx`, which jpackage always adds for the install directory ([`WixAppImageFragmentBuilder.java`](https://github.com/openjdk/jdk25u/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixAppImageFragmentBuilder.java); [RemoveFolderEx](https://docs.firegiant.com/wix/schema/util/removefolderex/) is implemented by a custom action), and, because `licenseFile` is set, the WixUI dialog library and bitmaps ([`WixUiFragmentBuilder.java`](https://github.com/openjdk/jdk25u/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixUiFragmentBuilder.java)); WiX is pinned in the Windows job, `wix-{wix}-src.tar.gz` is attached to every release with an MSI, and the MS-RL text is in `THIRD_PARTY_NOTICES.md` and the desktop Licences screen (MS-RL §3(A): "you must provide recipients the source code to that file along with a copy of this license", [LICENSE.TXT](https://github.com/wixtoolset/wix/blob/main/LICENSE.TXT)). Microsoft's VC++ runtime DLLs ship unmodified under Microsoft's redistribution terms wherever the OpenJDK runtime or python-build-standalone brings them.

The Classpath Exception, the GCC Runtime Library Exception and mere aggregation keep our own licence unaffected; this is the plan's reading of the licence texts and the [FSF FAQ](https://www.gnu.org/licenses/gpl-faq.html), not legal advice. **No other GPL and no AGPL code anywhere**, in any artefact or build step that ships output: no ProGuard (GPL-2.0; Android uses R8, BSD-3-Clause, and the desktop JARs stay unminified), no jextract (GPL-2.0), no JavaCPP `-gpl` artefacts, no distribution builds of FFmpeg or libmpv, no copied GPL code (the mpv-skipsilence script included). LGPL Java libraries with permissive substitutes stay out of every classpath (logback → slf4j-simple, argon2-jvm → Bouncy Castle, MariaDB Connector/J); a Gradle dependency is never LGPL or MPL except JNA, which is dual-licensed and used under Apache-2.0.

| Artefact | Contents by class |
|---|---|
| Android APKs (every ABI and the emergency build) | own code; permissive Gradle dependencies; OkHttp's compiled Public Suffix List (MPL-2.0 data); the engine stack of the 64-bit APKs (CPython and its bundled libraries, Chaquopy, yt-dlp; permissive) and certifi (MPL-2.0 data). No LGPL, no runtime exception |
| Desktop installers and ZIPs (every target) | own code incl. `ndmedia`; permissive Gradle dependencies (Compose Multiplatform with Skiko and the libraries Skiko embeds — FreeType under FTL, libpng, libjpeg-turbo, HarfBuzz, ICU, libwebp, expat, zlib — the AndroidX KMP libraries with the bundled SQLite, Ktor, OkHttp with its Public Suffix List as MPL-2.0 data, Okio, Coil, kotlinx, Metro runtime, JNA under Apache-2.0, dbus-java on Linux); miniaudio; the python-build-standalone stack (permissive, its MPL-2.0 build patches with source attached, the VC++ DLLs on Windows) and yt-dlp with certifi (MPL-2.0 data); FFmpeg (LGPL, dynamically linked, source attached); the OpenJDK runtime (the runtime exception); in the MSI only, the WiX components (MS-RL, source attached) |
| Server JAR | own code; permissive dependencies (Ktor, kotlinx, sqlite-jdbc, Bouncy Castle, slf4j) |
| Server image | the JAR plus the distroless `java25-debian13` base: Temurin under the runtime exception; Debian packages (glibc LGPL, libgcc under the GCC exception, CA certificates as MPL-2.0 data, tzdata) with their sources attached |
| YouTube-engine updates | yt-dlp with yt-dlp-ejs (Unlicense; meriyah ISC, astring MIT), pure Python |

Checks, each in CI and as a release blocker where it concerns a published artefact ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)): Licensee on `:app`, `:desktopApp` and `:sync:server` for Gradle dependencies ([below](#licensee-allow-list)); `checkPythonLicences` for both engine hosts and `checkNativeLicences` for the desktop native libraries, for everything Gradle cannot see ([Python and native components](#python-and-native-components)); 09's APK content scan (`check-apk.sh`), desktop image scan (`check-desktop-image.sh`, which also lists the MSI's `Binary` table against the pinned WiX components), server image scan (`check-server-image.sh`), `check-runtime-sources.sh` (the bundled runtime matches the attached source; the PBS and WiX source assets are present) and the embedded-native inventory `check-embedded-natives.sh` (2026-10-05: lists the native libraries and data files inside every Gradle JAR on the desktop and Android runtime classpaths — Skiko, `sqlite-bundled`, quickjs-kt, JNA's libffi, OkHttp's resources — and fails on one without a manual AboutLibraries entry and a `THIRD_PARTY_NOTICES.md` section, because Licensee sees only POM licences). `verifyDependencyPolicy`, `checkSpdxHeaders` and the [contribution rule](#copied-code-and-contributions) keep GPL code out of the source tree. Every bundled component is listed with its full licence text on each app's Licences screen and in `THIRD_PARTY_NOTICES.md` (N8).

### Licensee allow-list

Applied in `:app` (`licenseeRelease`; `release` is the published variant, and `benchmarkRelease` adds no runtime dependency of its own), `:desktopApp` (`licensee`, `runtimeClasspath`) and `:sync:server` (`licensee`, `runtimeClasspath`), with one shared configuration in build-logic:

```kotlin
licensee {
    allow("Apache-2.0"); allow("MIT"); allow("BSD-2-Clause"); allow("BSD-3-Clause"); allow("Unlicense"); allow("CC0-1.0")
    // JNA: POM declares Apache-2.0 and LGPL-2.1-or-later; used under Apache-2.0 (desktop only)
    allowDependency("net.java.dev.jna", "jna", "5.19.1") { because("dual-licensed; used under Apache-2.0 (D3)") }
    allowDependency("net.java.dev.jna", "jna-platform", "5.19.1") { because("dual-licensed; used under Apache-2.0 (D3)") }
    // No GPL, AGPL, LGPL or MPL artifact is otherwise allowed, not even scoped (D3).
    // allowUrl(...) entries only for artifacts whose POM names a known licence by URL, each with because(...)
}
```

Any further permissive licence on a Gradle dependency (e.g. ISC; a weak-copyleft licence such as EPL-2.0 needs a [D3](../PLAN.md#3-key-decisions) amendment first) needs a reviewed PR adding a scoped `allowDependency(...) { because(...) }`, never a global `allow`. Unverified: whether Licensee accepts a dependency when only one of its declared licences is allowed (then the JNA entries are unnecessary) — M0b records it; Bouncy Castle's and Skiko's POM licence forms (`allowUrl` if needed, M0b/MS1). Tink and quickjs-kt are Apache-2.0; S7 recorded (2026-10-06): Chaquopy's runtime does not reach `releaseRuntimeClasspath` (the plugin resolves it through detached configurations into the variant pipeline), so Licensee never sees it — it is covered by the Android Python lockfile and the manual AboutLibraries entries.

### Python and native components

Four lockfiles list every component that ships but is not a Gradle dependency with a POM. Each is edited by hand in the PR that changes a component and reviewed like code; the allow-lists live in build-logic (`PythonLicencePolicy.kt`, `NativeLicencePolicy.kt`), not in the lockfiles, so widening one is a visible build-logic change, never a side effect of a lockfile edit.

| Lockfile | Lists | Checked by | From |
|---|---|---|---|
| `youtube/ytdlp/python-components.lock` | the Android engine stack: the CPython runtime and the libraries it bundles, Chaquopy's runtime, the vendored yt-dlp with yt-dlp-ejs, the shim, data files, and native code inside Gradle artifacts that their POM does not describe (QuickJS inside quickjs-kt) | `:youtube:ytdlp:checkPythonLicences`, `verifyBundledYtDlp` | M0a |
| `youtube/ytdlp-desktop/python-components.lock` | the python-build-standalone release pin with the SHA-256 of each target's archive, the component list and licences taken from the release's `PYTHON.json` licence metadata (after our trim), the vendored yt-dlp, certifi, QuickJS when the JS provider ships | `:youtube:ytdlp-desktop:checkPythonLicences`, `verifyBundledYtDlp`, `fetchPythonStandalone` ([11 Desktop YouTube engine host](11-desktop.md#desktop-youtube-engine-host)) | M0a (empty), MD3 |
| `playback/native/native-components.lock` | FFmpeg (version, SPDX, source URL and SHA-256, the configure line per target), miniaudio, the C++/WinRT headers, `ndmedia` itself | `:playback:native:checkNativeLicences`, `buildFfmpeg`, `assembleFfmpegSource` ([11 Desktop playback engine](11-desktop.md#desktop-playback-engine)) | MD0 |
| `desktopApp/runtime.lock` | the JDK vendor and version, the SHA-256 of each target's archive and of the vendor's source tarball | `:desktopApp`'s packaging tasks and 09's `check-runtime-sources.sh` ([11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception)) | M0b |

```toml
# youtube/ytdlp/python-components.lock  (sketch; versions as planned, Unverified until S7 reads them from the runtime)
schema = 1
chaquopy = "17.0.0"          # must equal libs.versions.chaquopy
python = "3.14.0"            # the version part of the exact runtime Chaquopy packages (com.chaquo.python:target:3.14.0-0;
                             # the DSL's "3.14" is only the selector — checked against the resolved target, 2026-10-06)
pip = []                     # must equal the chaquopy { pip { } } requirements: none in v1

[[component]]
name = "CPython"
version = "3.14.0"
origin = "maven:com.chaquo.python:target:3.14.0-0"
licence = "Python-2.0"        # PSF-2.0 with the BeOpen, CNRI and CWI terms of CPython's LICENSE
kind = "runtime"             # runtime | native | python | data | pbs-patches (desktop lock only)
aboutLibrariesId = "cpython"  # entry text: CPython's full LICENSE incl. "Licenses and Acknowledgements for Incorporated Software"

[[component]]
name = "zstd"
version = "bundled with CPython 3.14.0"
origin = "maven:com.chaquo.python:target:3.14.0-0"
licence = "BSD-3-Clause OR GPL-2.0-only"
elected = "BSD-3-Clause"     # an OR expression is accepted only with an elected, allowed alternative
kind = "native"
aboutLibrariesId = "zstd"

[[component]]
name = "CA certificate bundle (certifi cacert.pem)"
licence = "MPL-2.0"
kind = "data"                # MPL-2.0 is accepted only for kind = "data", unmodified
aboutLibrariesId = "certifi-cacert"
# … one entry per row of the inventory below
```

**Python allow-list** (`PythonLicencePolicy.kt`, shared by both Python locks): `Unlicense`, `MIT`, `ISC`, `Apache-2.0`, `Apache-2.0 WITH LLVM-exception`, `BSD-2-Clause`, `BSD-3-Clause`, `0BSD`, `PSF-2.0`, `Python-2.0` (because CPython is distributed under the whole PSF/BeOpen/CNRI/CWI stack, not PSF-2.0 alone), `Unicode-3.0` (because CPython's `unicodedata` and `str` carry an extract of the Unicode Character Database under the Unicode License v3), `Zlib`, `bzip2-1.0.6`, `blessing` (SQLite's public-domain dedication) and `LicenseRef-PublicDomain`; for the desktop lock also the licence of python-build-standalone's ncurses and libedit builds as `PYTHON.json` reports them (Unverified SPDX forms until MD3's review; expected MIT-style and BSD-3-Clause); `MPL-2.0` only for `kind = "data"` (unmodified data), plus exactly one conditional code case for the desktop lock only: `MPL-2.0` with `kind = "pbs-patches"` for the pinned python-build-standalone build patches, unmodified by us, and only when the lock's `pbsSource` entry names the lock's own PBS release tag and carries a SHA-256 and the MPL-2.0 notices entry exists — what `checkPythonLicences` can see; that the `python-build-standalone-{pbsTag}-src.tar.gz` asset is attached to the release with that SHA-256 is checked at publish time by `check-runtime-sources.sh --release` ([11 Lockfiles](11-desktop.md#lockfiles), [09 release.yml](09-quality-and-release.md#releaseyml); PO-48 proposed default, awaiting owner). Any other MPL-2.0 code fails, and the patches are never recorded as `kind = "data"`. Anything containing `GPL` (GPL, LGPL, AGPL) or `Sleepycat` fails, also inside an `OR` expression unless an allowed alternative is `elected`.

**Native allow-list** (`NativeLicencePolicy.kt`): the permissive set above plus `MIT-0` and `Unlicense OR MIT-0` (miniaudio, `elected = "MIT-0"`), and `LGPL-2.1-or-later` (or `LGPL-3.0-or-later`, which D3 also allows, for a component other than FFmpeg, such as the libmpv fallback) **only** for entries with `kind = "lgpl-shared"`, `linking = "dynamic"`, a `source` entry with URL and SHA-256, and a configure line without `--enable-gpl`, `--enable-version3` or `--enable-nonfree`.

Inventory (S7 read the versions from the runtime on 2026-10-06 — CPython's own `Android/android.py` dep pins confirmed by the version strings inside the shipped `.so` files; the lockfile carries the details. The ABI-independent `assets/chaquopy/` payloads — both 64-bit `bootstrap-native` sets and the stdlib `.imy` files — also ship unchanged in the `armeabi-v7a` APK, where they are dead weight the ABI split cannot drop; measured in [S7](#s7-chaquopy-under-agp-941). Licences per [CPython's licence page](https://docs.python.org/3/license.html), [yt-dlp](https://github.com/yt-dlp/yt-dlp#licensing), [yt-dlp-ejs](https://github.com/yt-dlp/ejs), [Chaquopy](https://github.com/chaquo/chaquopy), [quickjs-kt](https://github.com/dokar3/quickjs-kt)):

| Component | Version (measured by S7, 2026-10-06) | Licence | Ships in |
|---|---|---|---|
| CPython runtime and standard library | 3.14.0 | Python-2.0 (PSF-2.0 with the BeOpen, CNRI and CWI terms); its Licences entry and `THIRD_PARTY_NOTICES.md` text is CPython's full licence verbatim, including every "Licenses and Acknowledgements for Incorporated Software" notice (Mersenne Twister, SipHash24, strtod and dtoa, cfuhash, Global Unbounded Sequences, the Zstandard bindings and the others listed there, [CPython licence](https://docs.python.org/3/license.html)) | all three APKs (usable only on `arm64-v8a`, `x86_64`) |
| mimalloc (CPython's allocator) | as bundled | MIT | all three APKs |
| Unicode Character Database extract (`unicodedata`, `str`) | as bundled | Unicode-3.0, data | all three APKs |
| OpenSSL (`libcrypto`, `libssl`; Python's `ssl` module, not used for network traffic, [D74](../PLAN.md#3-key-decisions)) | 3.0.18 (banner "OpenSSL 3.0.18 30 Sep 2025") | Apache-2.0 | all three APKs |
| SQLite (Python's `_sqlite3`) | 3.50.4 (source id 2025-07-30) | blessing (public domain) | all three APKs |
| libffi | 3.4.4 | MIT | all three APKs |
| expat | 2.7.3 | MIT | all three APKs |
| HACL* | as bundled | MIT | all three APKs |
| mpdecimal | as bundled | BSD-2-Clause | all three APKs |
| zstd | 1.5.7 | BSD-3-Clause (elected from `BSD-3-Clause OR GPL-2.0-only`) | all three APKs |
| xz (liblzma) | 5.4.6 | 0BSD | all three APKs |
| bzip2 | 1.0.8 | bzip2-1.0.6 | all three APKs |
| zlib | 1.2.8 (NDK r28.2 sysroot build) | Zlib | all three APKs |
| Chaquopy runtime (Java, JNI, bootstrap) | plugin 17.1.0 self-built @ `a41f0c9`; runtime payloads = released 17.0.0 republished under 17.1.0 (`third_party/chaquopy-maven`) | MIT | all three APKs |
| LLVM libc++ (statically linked into Chaquopy's JNI libraries; no `libc++_shared.so` ships) | NDK r28.2 | Apache-2.0 WITH LLVM-exception | all three APKs |
| CA certificate bundle (certifi `cacert.pem`, shipped by Chaquopy) | 2026.7.22 | MPL-2.0, data only | all three APKs |
| yt-dlp (official zipimport release) | 2026.08.19 | Unlicense | 64-bit APKs, engine updates — vendored from M9a, not yet in the lockfile |
| yt-dlp-ejs (inside the yt-dlp release) | 0.8.0 | Unlicense; its solver bundles meriyah (ISC) and astring (MIT) | 64-bit APKs, engine updates — from M9a |
| `neutrodyne_ytx` shim | SHIM_API_VERSION 1 | Unlicense | all three APKs (packaged; unusable without a 64-bit interpreter) |
| QuickJS (inside quickjs-kt; only if the JS provider ships) | as bundled by 1.0.15 | MIT | 64-bit APKs — from M9b |

The desktop inventory (python-build-standalone CPython 3.14.8 and its bundled OpenSSL, SQLite, libffi, expat, mpdecimal, zstd, xz, bzip2, zlib, libedit and ncurses, with `_dbm`, `_gdbm`, Tcl/Tk, `pip` and the test suite removed; FFmpeg 9.0.x; miniaudio 0.11.x; the C++/WinRT headers) and the runtime's `legal/` contents are owned by [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception) and [11 Desktop YouTube engine host](11-desktop.md#desktop-youtube-engine-host), which keep their tables in step with the locks.

Never shipped (each would be a licence regression, risk L2; [D3](../PLAN.md#3-key-decisions)): yt-dlp's PyInstaller executables (GPL parts) and PyInstaller itself, youtubedl-android (GPL-3.0), a Termux-built Python (GNU readline), `mutagen` (GPL-2.0+, part of yt-dlp's `default` extra), `bgutil-ytdlp-pot-provider` (GPL-3.0), Deno, Node, Bun (statically linked LGPL JavaScriptCore), the `qjs` CLI, the AppImage runtime (statically linked LGPL libfuse), python-build-standalone's `_dbm` (Sleepycat Berkeley DB) and Tcl/Tk, distribution builds of FFmpeg or libmpv, ProGuard, jextract, JavaCPP `-gpl` artefacts, logback, argon2-jvm.

Tasks (all configuration-cache safe):

| Task | Project | In `check` | Fails when |
|---|---|---|---|
| `checkPythonLicences` | `:youtube:ytdlp` | yes (from M0a, whatever S7 decides; the licence allow-list check is unconditional) | a component's licence is not on the allow-list (MPL-2.0 outside `kind = "data"` — the Android lock has no `pbs-patches` case; an `OR` expression without an allowed `elected`); the lockfile is not valid TOML, a `[[component]]` entry is not a table or lacks a required field (Tomlj's recoverable errors are rejected since 2026-10-06 — a duplicated key used to keep its first value and pass); only while `:youtube:ytdlp` applies Chaquopy (S7 go): the lockfile's `chaquopy`, `python` or `pip` differ from the build's Chaquopy plugin version, the exact resolved `com.chaquo.python:target` runtime version (`3.14.0-0`: the DSL's `3.14` selects `3.14.0`, so the lock must match the packaged runtime, not the selector — 2026-10-06) or pip requirements; the CPython component's `version`/`origin` differs from that runtime, or another component pins a different `com.chaquo.python:target` origin; the build declares any `install("-r", …)` requirement file or a `-r`/`--requirement`/`-e`/`--editable` `options(…)` flag — all bypass the lock's `pip` list and are rejected under the no-pip policy (2026-10-06); on a fallback host the lockfile's `python` entry is checked against the A2 package instead, and with the Kotlin port the CPython rows leave the lockfile; the vendored `bundled.json` version differs from the yt-dlp entry; the top-level packages inside the vendored `yt-dlp` are anything but `yt_dlp` and `yt_dlp_ejs`; a component has no AboutLibraries manual definition with its `aboutLibrariesId` (so the Licences screen and `THIRD_PARTY_NOTICES.md` cannot drift) |
| `checkPythonLicences` | `:youtube:ytdlp-desktop` | yes (from M0a on an empty lock; content MD3) | the same licence rules over the desktop lock, with only the `kind = "pbs-patches"` MPL-2.0 named case (PO-48 proposed default): it fails on MPL-2.0 code outside that case, on a `pbs-patches` entry whose `pbsSource` tag differs from the lock's PBS release tag or has no SHA-256, whose MPL-2.0 notices entry is missing, or on PBS patches recorded as `kind = "data"` (the attached source asset is `check-runtime-sources.sh --release`'s check, not this task's); the trimmed image still contains a module the trim list removes (`_dbm`, `_gdbm`, `_tkinter`, `readline`, `ensurepip`, `pip`, `test`); a target's archive SHA-256 differs from the lock |
| `verifyBundledYtDlp` | both engine hosts | yes (no-op until M9a vendors the file) | the SHA-256 of the vendored `yt-dlp` differs from its line in the committed `SHA2-256SUMS` or from `bundled.json`; `SHA2-256SUMS.sig` does not verify against `keys/yt-dlp-release-key.asc` whose fingerprint must equal the one pinned in build-logic (`AC0C BBE6 848D 6A87 3464 AF4E 57CF 6593 3B5A 7581`, [yt-dlp public key](https://github.com/yt-dlp/yt-dlp/blob/master/public.key); re-verified when M9b starts); `ORIGIN` in the zip's `yt_dlp/version.py` is not `yt-dlp/yt-dlp`. Verification uses build-logic's `OpenPgpSignatureCheck` (JDK `Signature`, no `gpg` binary) |
| `checkNativeLicences` | `:playback:native` | yes (from MD0) | a component's licence is not on the native allow-list; an LGPL entry is not `lgpl-shared`/`dynamic` or lacks its source entry; the FFmpeg configure line of any target contains `--enable-gpl`, `--enable-version3` or `--enable-nonfree`; a vendored source's SHA-256 differs from the lock |
| `shimTest`, `shimTestStdio` | `:youtube:ytdlp`, `:youtube:ytdlp-desktop` | no (need a host CPython of the target minor version; CI `unit` job) | the shim's pytest suite fails against the bundled yt-dlp with `ReplayRH` through the Chaquopy or the stdio adapter (04 owns the content) |

`buildFfmpeg` additionally asserts that the built `avcodec_license()` reports "LGPL version 2.1 or later" and that the libraries keep their upstream names (risk L6). 09's scripts close the loop on the built artefacts: `check-apk.sh` fails on forbidden content (`mutagen`, `readline`, `libreadline`, `org/schabi/newpipe`, `org/mozilla/javascript`) and uses the Android lock as its input for what native libraries and top-level Python packages an APK may contain; `check-desktop-image.sh` fails on `_dbm`, `libreadline`, `_tkinter`, `AppRun`, `libfuse`, `proguard`, `mutagen`, `qjs`, Deno, Node or Bun, on an FFmpeg library that does not report LGPL, and on a missing `legal/` tree; `check-server-image.sh` checks the image's package list against its source bundle; `check-runtime-sources.sh` matches the runtime's `release` file (implementor, version) with `runtime.lock` and the attached tarball (09 owns the scripts, [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception) the rules).

### AboutLibraries and the Licences screen

- The AboutLibraries Gradle plugin generates library metadata for `:app`'s `release` variant (and `benchmarkRelease`) and for `:desktopApp`'s `runtimeClasspath`; debug-only dependencies never appear. All three ABI APKs of a default build show the same list, engine stack included (08 labels it on the `armeabi-v7a` APK); only the [emergency builds](#emergency-build-without-the-engine) omit the engine entries. Verified 2026-10-06 (M0a step 21): 15.2.0 publishes both plugin IDs from the same jar — `:app` applies `com.mikepenz.aboutlibraries.plugin.android`, `:desktopApp` the plain `com.mikepenz.aboutlibraries.plugin`, which works on the plain-JVM Compose application (the extension is `aboutLibraries { }` in both; M0b step 33 records how the desktop Licences screen consumes its output). The plugin runs in offline mode so builds stay reproducible ([09 Hygiene](09-quality-and-release.md#hygiene)); verified 15.2.0 property names: `aboutLibraries { offlineMode.set(true); collect { configPath.set(<project>/config); fetchRemoteLicense.set(false); fetchRemoteFunding.set(false) } }`.
- `:feature:settings` renders `LicencesKey` itself in common code with `Nd*` components from `aboutlibraries-core` data (KMP; loaded at runtime from the generated resource of each shell) — **not** with AboutLibraries' Compose UI artifact, which could pull a different Compose/Material3 line (the same trap as `material-kolor`). Verified 2026-10-06: `aboutlibraries-core` 15.2.0's module metadata declares only `kotlin-stdlib` and `kotlinx-serialization-json` — no Compose dependency.
- Each entry shows name, version, licence name and the full licence text; Apache-2.0 `NOTICE` contents are added through AboutLibraries' `config/aboutlibraries/` overrides where a dependency ships one (Unverified which do).
- Manual library definitions in the same config directory cover code that ships but is not on a runtime classpath. **Android:** every component of the Android lockfile — CPython (its full licence text with the incorporated-software notices) and its bundled libraries, the Chaquopy runtime and `libc++_shared`, yt-dlp, yt-dlp-ejs with meriyah and astring, the CA bundle (MPL-2.0), QuickJS when the JS provider ships. **Desktop:** the OpenJDK runtime (GPL-2.0 incl. HotSpot, the Classpath Exception text, the GCC Runtime Library Exception, the VC++ redistributable terms on Windows, where the runtime's `legal/` tree is in the installed app, and where its source is: the release page), FFmpeg (LGPL-2.1, the notice "This software uses code of FFmpeg licensed under the LGPLv2.1 and its source can be downloaded here" with the release link, [FFmpeg legal](https://ffmpeg.org/legal.html)), miniaudio, the C++/WinRT headers, the python-build-standalone stack and yt-dlp per the desktop lock (with the PBS MPL-2.0 text and where its source is), the WiX components of the MSI (MS-RL, where its source is), and Skiko's embedded libraries per target from Skiko's and skia-pack's third-party notices (FreeType with the FTL credit line "Portions of this software are copyright © <year> The FreeType Project (www.freetype.org). All rights reserved.", libpng, libjpeg-turbo with the IJG statement "This software is based in part on the work of the Independent JPEG Group", HarfBuzz, ICU, libwebp, expat, zlib; added 2026-10-05). **Both:** OkHttp's Public Suffix List (MPL-2.0 data). Each has its `aboutLibrariesId`, plus any permissive or Unlicense code copied or ported under the [contribution rule](#copied-code-and-contributions). `THIRD_PARTY_NOTICES.md` mirrors both lists. The engine entries sit in their own subdirectory that the emergency builds leave out.
- **Packaging:** never exclude `META-INF/LICENSE*` or `META-INF/NOTICE*` wholesale; only the duplicate `/META-INF/{AL2.0,LGPL2.1}` entries are excluded from the APKs ([common config](#common-android-configuration)); the server's `fatJar` keeps every dependency's licence files; the desktop image keeps the runtime's `legal/` tree untouched.

### About statements

About (`:feature:settings`) shows the version, the build's identity (`BuildInfo.apkAbi` on Android; OS, architecture and install kind on the desktop), one licence statement and a "Source code" link to `BuildInfo.repoUrl`. The statement is an ordinary Compose resource of `:feature:settings`, one per platform:

| Build | Statement (en) |
|---|---|
| every APK | "Neutrodyne's source code is dedicated to the public domain under the Unlicense. The app also includes third-party components under permissive licences, listed under Licences." |
| every desktop build | "Neutrodyne's source code is dedicated to the public domain under the Unlicense. This app also includes third-party components under permissive licences, FFmpeg under the GNU LGPL 2.1, and an unmodified OpenJDK runtime under the GNU GPL 2.0 with the Classpath Exception; the source code of both is attached to every release. All components are listed under Licences." |

With the engine active, About may add the credit line "YouTube engine: yt-dlp {activeVersion}" from `YouTubeEngine.status` (08 decides placement). [04 Licensing and legal](04-youtube.md#licensing-and-legal) owns the engine-stack notice wording on the Licences screen and must use the statements above for About.

### Copied code and contributions

`CONTRIBUTING.md` and the PR template (created in M0a) carry this rule verbatim:

1. **Behaviour-only reuse.** Never copy code from GPL or AGPL projects (AntennaPod, the NewPipe app, NewPipe Extractor, LibreTube, Podcini, youtubedl-android, Seal, YTDLnis, mpv's skip-silence scripts, GPL sync servers) or MPL projects (Pocket Casts) into any module, and never copy LGPL code into our source either: LGPL components are used only as the separately linked libraries [D3](../PLAN.md#3-key-decisions) lists. There is no exception. Re-implement behaviour from documentation and design notes.
2. **Clean-room areas.** The gpodder and Open Podcast API compatibility layers (v1.x) are written from their published documentation and recorded traffic only, by people who have not read GPL or AGPL server or client sync code ([10 Compatibility layers](10-sync.md#compatibility-layers)). Desktop skip silence is a port of Media3's Apache-2.0 silence-skipping processor, never of a GPL mpv script ([11 Desktop playback engine](11-desktop.md#desktop-playback-engine)).
3. **Permissive and public-domain code** (Apache-2.0, MIT, BSD, ISC, CC0, Unlicense — e.g. nav3-recipes, Media3's silence skipping, Sonic, `rocicorp/fractional-indexing`) may be copied or ported only with its original copyright header and `SPDX-License-Identifier` line kept (or a credit header for a port), plus an entry in `THIRD_PARTY_NOTICES.md`. yt-dlp is Unlicense: porting its logic (for example the Kotlin InnerTube fallback of [D72](../PLAN.md#3-key-decisions)) is allowed, with a credit header in each ported file ("Ported from yt-dlp `<path>` at `<commit>`, Unlicense") and an entry in `THIRD_PARTY_NOTICES.md`. Specification text under CC BY-SA (the Open Podcast API) is linked, never pasted into code or documents.
4. All contributions are dedicated under the Unlicense.
5. PR template checkboxes: "No code was copied from GPL, AGPL, LGPL or MPL projects; copied or ported permissive or Unlicense code keeps its header (or credit line) and is listed in THIRD_PARTY_NOTICES.md." and "A new bundled component (Gradle, Python, native or runtime) has its lockfile or Licensee entry, its Licences entry and its notice." Reviewers enforce both ([09 Static analysis](09-quality-and-release.md#static-analysis) runs `checkSpdxHeaders`).

---

## M0 scaffold checklist

Delivers [M0](../PLAN.md#m0-scaffold-and-ci): steps 1–28 are **M0a** (KMP scaffold, Android app, CI and the release pipeline for APKs; spikes S1–S12 and S19), steps 29–37 are **M0b** (desktop shell, desktop CI and installers, server skeleton, brand assets; spike S13 with 11). Ordered; each step ends with a green `./gradlew build` unless stated. Steps marked (08), (09), (10) or (11) follow those documents for content.

**M0a**

1. Repository hygiene: `.gitignore` (Gradle, Android Studio, `local.properties`, `*.jks`; it must not match `signing/neutrodyne-public.keystore`), `.gitattributes` (`* text=auto eol=lf`, binaries, `signing/*.keystore binary`), `.editorconfig` (09). Generate the committed keystore `signing/neutrodyne-public.keystore` once with the `keytool` command of [09 Committed keystore](09-quality-and-release.md#committed-keystore) and add `signing/README.md` (public on purpose; download only from the GitHub release page; forks change the application ID or the key) ([Signing config](#signing-config)); no key ceremony, holders or backups exist ([D61](../PLAN.md#3-key-decisions)).
2. Gradle wrapper: `gradle wrapper --gradle-version 9.7.1 --distribution-type bin`; set `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` from gradle.org's published checksum.
3. Write `gradle/libs.versions.toml` exactly as in [Toolchain and versions](#gradlelibsversionstoml).
4. Write `settings.gradle.kts`, `gradle.properties` (version `0.1.0` / `10095`), root `build.gradle.kts` ([above](#settingsgradlekts-gradleproperties-root-build)); `compose-stability.conf`.
5. Create `build-logic/` (settings, `convention/build.gradle.kts`, plugin classes for every ID of [Convention plugins](#convention-plugins), the plugin guards, `ModuleRules.kt`); policy tasks may start as no-ops returning success, filled in step 21.
6. **Spike S1** (KGP and the KMP plugin under AGP 9.4.1). Record the result before continuing; on failure apply its fallback.
7. Create every module of [Module layout](#module-layout) (except `:benchmark`) with its plugins, targets, namespace, an `internal` placeholder and one placeholder test (there are no `:update:*` modules; the update check arrives in `:core:domain`, `:core:model` and `:core:data` in M11a); every KMP module compiles for `android` and `desktop` (PLAN M0 AC10). Then **Spike S7** on `:youtube:ytdlp` ([S7](#s7-chaquopy-under-agp-941)); record the outcome before continuing. If go, `:youtube:ytdlp` keeps Chaquopy applied with the hello-world `neutrodyne_ytx/selftest.py` and the real `YtxService` declared in `:ytx` (answering only `ping` and `selftest` until M9a), and `NeutrodyneApplication`'s `ProcessRole.YTX` branch and `YtxProcessStartTest` are live from then on, so every later AGP, Kotlin or Chaquopy bump that breaks the integration fails CI at once; on a fallback it stays a plain Android library stub and D72 is amended. Every source file carries `SPDX-License-Identifier: Unlicense`.
8. **Spike S8** (Metro across KMP modules) on the skeleton, before any DI code is written; on failure switch to Koin and amend D82 ([S8](#s8-metro-across-kmp-modules)).
9. `:core:common`: `AppScope`/`YtxScope` (already in place since S8), `Clock`, `Dispatcher`/`NeutrodyneDispatchers`, `ApplicationScope`, `Outcome`, `suspendRunCatching`, `Log`/`LogSink`/`Redactor`, `AppInitializer`, `NetworkMonitor`/`NetworkStatus`, `PlatformInfo`, `CredentialLookup`/`Origin`, `HttpClientKind`, `LocalNetworkAccess`, the `expect`s `Nfc`, `DateFormatter`, `StoragePaths` with their `actual`s, `AppDirs` and `JobLane` (`desktopMain`, 11) — with the unit tests listed in [Testing](#testing).
10. `:core:model`: `BuildInfo` (fields per [Build variants and ABIs](#build-variants-and-abis)), `NetError`/`TlsKind`, `IpFamily` (04's enum, placed here), `SettingsFile`/`SettingKey` (with `synced`), `AllSettingKeys` (empty list + test), `DesktopOs`, `DesktopArch`, `InstallKind` (11's values). `ExternalReason` (04's enum, placed here so that `:playback:api` and `:core:ui` can use it under rules 7 and 10) follows in M2 with its first consumer.
11. `:core:navigation`: all canonical keys plus 08's `SettingsHomeKey`, `TopLevelKey`, `NavKeySerializers` with its registration test, `EntryProviderInstaller`, `AppNavigator` (with 08's `pushDetail`), `LocalAppNavigator`, `NdSceneMetadata`, 08's `PaneLayout`/`LocalPaneLayout`/`LocalNavTab`, `IntentRouter` with `RouteInput` and `Route` (internal routes only in M0a).
12. `:core:datastore`: both `DataStore`s, `SettingsStore`, `DeviceSettingsStore`; `:core:domain`: `SettingsRepository` interface; `:core:data` stub: `CredentialLookup.None` binding.
13. `:core:network:okhttp` (`CoreClients`, `NetworkClients` with all seven kinds, `pinnedToFamily`, `UserAgentInterceptor`, `AuthInterceptor`, `IdentityEncodingInterceptor`, `LocalNetworkGuard`, `DnsFamilyHints`, `FamilyHintDns`, `JvmNetErrors`) and `:core:network` (`NeutrodyneHttpClients`, `NetErrorClassifier`, `ConnectivityNetworkMonitor`, `DesktopNetworkMonitor`, the manifest permissions) — with tests. **Spike S12** runs here ([S12](#s12-ktor-fetch-pipeline)).
14. `:core:testing`: `MainDispatcherRule`, `TestClock`, `FakeNetworkMonitor` (09 owns the full inventory).
15. `:core:designsystem`: `NeutrodyneTheme` (dynamic colour on API 31+, a placeholder brand scheme until step 31, light/dark) and Material Symbols for the five destinations and the gear (08). `:core:ui`: the public `Res` with the destination labels, `UiText`, the navigation host (`NavigationState`, `NeutrodyneNavHost`, the overlay scene strategies) and the `NeutrodyneRoot` layout with its slots, `PlatformActions` stubs. **Spikes S9 and S11** run here ([S9](#s9-compose-multiplatform-ui-stack), [S11](#s11-compose-resources-and-per-app-language)).
16. Feature stubs: each top-level feature installs its `TopLevelKey` entry showing a placeholder empty state; `:feature:settings` installs `SettingsHomeKey` (the gear's target, 08), `SettingsKey` (M0a renders `ABOUT`: version, build identity, licence statement, source link; 08 adds Appearance) and `LicencesKey` (AboutLibraries plus the manual entries of the Chaquopy runtime and CPython stack when S7 is go).
17. `:app`: `NeutrodyneApplication` (`ProcessRole`, ACRA guard, lazy `AndroidAppGraph`, `YtxGraph`, initializer runner), `AndroidAppGraph` and `CoreBindings` (dispatchers, scope, `DeviceClock`, `BuildInfo`, `PlatformInfo`, `StoragePaths`, the `@Multibinds` declarations), `MetroWorkerFactory`, an empty `YouTubeBindingsModule` in `app/src/main` (its first binding arrives in M2, [YouTube bindings](#youtube-bindings)), build types `release` and `debug`, the `neutrodynePublic` signing config, ABI splits, R8 with `keepRules/app.keep`, the baseline-profile consumer plugin (no profiles yet), `app/src/debug/` (`DebugToolsInitializer`, `DebugHttpLogInterceptor`) ([Build variants and ABIs](#build-variants-and-abis), [Debug build type](#debug-build-type)), `MainActivity` (AppCompat, splash with `StartupViewModel`, edge-to-edge, `setContent { NeutrodyneRoot(…) }`), the Android `IntentRouter` adapter, themes XML, the network security config, manifest per [Manifest and permissions](#manifest-and-permissions) (M0a subset), M0a backup rule files, `res/resources.properties`, `res/values*/strings.xml` with `app_name` per shipped locale.
18. `:desktopApp`, `:sync:server` and the desktop-only, sync and island modules as compiling stubs: `:desktopApp`'s `MainKt` builds `DesktopAppGraph` (a graph test proves it), `:sync:server`'s `MainKt` prints its version; the shells' module-graph rules apply from here.
19. ACRA mail + dialog wired to `ACRA_MAILTO` from the committed `neutrodyne.acraMailto` (disabled while empty; on in release builds, forced empty in debug builds, [D62](../PLAN.md#3-key-decisions)) (09).
20. **Spikes S2–S6 and S10**; write results into [Spikes](#spikes) and the owning documents.
21. Policy tooling: Licensee (`licenseeRelease`), module-graph assertion (record the 2.9.1 DSL names and its KMP behaviour), AboutLibraries (confirm the plugin ID; confirm `aboutlibraries-core` has no Compose dependency with `./gradlew :feature:settings:dependencies`), `verifyDependencyPolicy` (declared and resolved checks), `verifyManifestPermissions` with `app/policy/permissions.txt`, `checkSpdxHeaders`, `checkBannedApis` with the source-set rules, `PythonLicencePolicy.kt` with `youtube/ytdlp/python-components.lock` (the Chaquopy runtime and CPython stack if S7 is go, otherwise empty) and an empty `youtube/ytdlp-desktop/python-components.lock`, both `checkPythonLicences`, `verifyBundledYtDlp` (a no-op until M9a vendors yt-dlp), `THIRD_PARTY_NOTICES.md` ([Python and native components](#python-and-native-components)).
22. Spotless/ktlint/compose-rules (blocking) and detekt (non-blocking) (09).
23. GMD definitions (`api26` `aosp`, `api36` `aosp-atd`; the API 37 16 KB image runs through android-emulator-runner) and the M0a instrumented smoke test on the `debug` build (09).
24. **Spike S19** (release build: R8 keep rules incl. Chaquopy and kotlinx-serialization, the baseline-profile plugin 1.5.0 with a throwaway `:benchmark` on a spike branch, release APK sizes per ABI with S7's packaging) ([S19](#s19-release-build-with-r8-and-baseline-profiles)).
25. `ci.yml` (`static`, `unit` with `allTests`/`desktopTest`, `assemble` with `assembleRelease assembleDebug` and `check-apk.sh --published`, `instrumented`), `nightly.yml` (`instrumented-full`, `api37-16k`, `release-build-smoke`, the report-only `repro` job) and `release.yml` (`verify-tag`; `android`: `assembleRelease` → three ABI APKs signed with the committed keystore and checked against its certificate, the R8 mapping zip; `publish`: `SHA256SUMS`, `neutrodyne-update.json`, provenance attestations, immutable normal release) ([09 Workflows](09-quality-and-release.md#workflows)), `keepalive.yml`, `changelogs/`, Renovate config (the KMP group of catalog rule 6), PR template and `CONTRIBUTING.md` with the [contribution rule](#copied-code-and-contributions) (09). There is no mirror ([PO-34](../PLAN.md#48-further-product-owner-decisions)).
26. Negative verification (PLAN M0 AC2), recorded in the [verification log](#verification-log): (a) add `implementation(project(":feature:library"))` to `:feature:feeds` → `assertModuleGraph` fails; (b) add a GPL-licensed artifact to `:core:data` → `licenseeRelease` and `verifyDependencyPolicy` fail; (c) add a component with `licence = "GPL-3.0-or-later"` to `python-components.lock` → `checkPythonLicences` fails; (d) add a file named `mutagen/__init__.py` (and, separately, `lib/arm64-v8a/libreadline.so`) to a release APK → 09's `scripts/ci/check-apk.sh` fails; (e) add `import java.io.File` to a `commonMain` file → the build or `checkBannedApis` fails; (f) declare `com.guardsquare:proguard-base` on any configuration → `verifyDependencyPolicy` fails. Revert all six.
27. Acceptance run: `./gradlew check assembleRelease assembleDebug` green on CI in < 20 min, producing the `arm64-v8a`, `x86_64` and `armeabi-v7a` APKs of both build types and no universal APK, the release APKs within the N5 budgets or S7's and S19's sizes sent to the PO (PLAN M0 AC1); the cross-machine certificate check and the debug-isolation checks of the [verification log](#verification-log) (AC8, AC9); `./gradlew :app:buildEnvironment` shows `kotlin-gradle-plugin` resolved to 2.4.20 (AC3); the debug build installs on API 26 and API 36 GMDs, shows five labelled destinations, survives rotation and a dark-mode switch, back from Settings returns to the tab (predictive-back animation checked manually once on an API 36 image and noted in the log), and the release APK starts on the API 36 GMD (AC4); `commonTest` runs on the desktop JVM, Metro builds `AndroidAppGraph`, the destination labels come from Compose resources and switch with the per-app language (AC10); Licences lists every runtime dependency with its licence (with the CPython stack and Chaquopy when S7 is go, AC5).
28. First release: `scripts/release.sh 0.1.0` tags the prepared version and `release.yml` publishes `v0.1.0` (`versionCode` 10095) as an immutable, normal GitHub release (never a pre-release): three release APKs signed with the committed keystore, the R8 mapping zip, `SHA256SUMS`, `neutrodyne-update.json` and attestations, verified per PLAN M0 AC7; the README's "Install and update" section states that the APKs are release builds signed with a public key, what that means and how to check a download (09).

**M0b**

29. `:desktopApp` shell (11): `MainKt` with the start-up order of [Application start-up](#application-start-up), `DesktopAppGraph` with `DesktopCoreBindings` (`DesktopClock`, `BuildInfo` loader, `PlatformInfo`, `StoragePaths` from `AppDirs`), one `NeutrodyneWindow` calling `NeutrodyneRoot` with the five destinations, the macOS menu bar, a tray stub, `SingleInstanceLock` with the loopback handshake, the crash-file writer, `RollingFileSink`, the smoke switch `-Dneutrodyne.smoke=true`; the desktop graph test (PLAN M0 AC11).
30. Desktop data plumbing: DataStore files under `AppDirs` and the desktop `SettingsRepository` path; `DesktopNetworkMonitor` wired; `DesktopYouTubeBindingsModule` (empty until M2); the frozen desktop identifiers of [D61](../PLAN.md#3-key-decisions) in `nativeDistributions` (the MSI `upgradeUuid` generated once and recorded in 11).
31. Brand assets ([D97](../PLAN.md#3-key-decisions), 08; delivered early with M0a.1 on 2026-10-06 so `v0.1.0` ships with its launcher icon; the adaptive-icon XMLs live in `mipmap-anydpi/`, because Lint rejects the redundant `-v26` qualifier at minSdk 26, and `res/raw/keep.xml` keeps the notification icon until its first use in M4): commit `media-sources/neutrodyne-mono.svg`; implement `generateBrandAssets` and `checkBrandAssets` ([Brand-asset generator](#brand-asset-generator)); generate and commit the Android adaptive icon (foreground, background, monochrome), notification small icon, splash icon, the desktop ICO, ICNS, PNG set and tray images; the brand scheme in `NeutrodyneTheme` (PLAN M0 AC15).
32. Desktop packaging (11, 09): `neutrodyne.desktop.application`'s `nativeDistributions` per target (MSI per user + ZIP, DMG configuration with the macOS `0.x` ZIP of [PO-39](../PLAN.md#48-further-product-owner-decisions) through `scripts/desktop/mac-zip.sh`, DEB, RPM, tar.gz), the jlink module list, `--enable-native-access=ALL-UNNAMED`, `desktopApp/runtime.lock` with the Temurin 25 pins, the ProGuard tasks disabled; `packageDistributionForCurrentOS` and the packaged-app smoke start on each runner (PLAN M0 AC12).
33. Desktop licences: Licensee on `:desktopApp` (`licensee`), AboutLibraries on the desktop (record how the plugin applies to a plain-JVM Compose application), the desktop Licences entries for the OpenJDK runtime (GPL-2.0, Classpath Exception and GCC exception texts, where its source is; PLAN M0 AC5), `THIRD_PARTY_NOTICES.md`'s runtime section; 09's `check-desktop-image.sh` and `check-runtime-sources.sh` (PLAN M0 AC13).
34. `:sync:server` skeleton (10): `serve` with `/healthz`, `/readyz` and `/.well-known/neutrodyne-sync`, configuration from `NEUTRODYNE_SERVER_*` variables, the listen rule (a non-loopback listener only with an `https://` `NEUTRODYNE_SERVER_PUBLIC_URL` or `--insecure-lan`, [10 TLS stance and insecure LAN mode](10-sync.md#tls-stance-and-insecure-lan-mode)), `:sync:server:fatJar`, Licensee and the logback ban (PLAN M0 AC16).
35. Desktop CI and release (09): Linux x64 desktop build and smoke start (Xvfb) on every PR (`desktop-smoke`); nightly `desktop-matrix` on Windows x64, macOS arm64 and Linux x64/arm64; `release.yml` `desktop` jobs per target and the `sources` job (runtime source tarball and `RUNTIME-SOURCES.md`); the README's draft "Install on Windows, macOS or Linux" section (11).
36. **Spike S13** on the reference laptops ([PO-43](../PLAN.md#48-further-product-owner-decisions)), procedure and results in [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception): jlink module set, sizes, first frame with and without the AOT cache, idle RSS, the ad-hoc signature over nested binaries, the macOS `0.x` ZIP, Kotlin `jvmTarget` 25, the unsigned first-run flows (PLAN M0 AC14).
37. Negative verification (M0b part of AC2): a desktop image containing `_dbm`, `libreadline`, `AppRun` or `libfuse` fails `check-desktop-image.sh`; then the M0b acceptance run (AC11–AC16) and the first tester release with desktop assets.

---

## Spikes

Delivered in M0a (S1–S12, S19) and M0b (S13 with 11). Each spike runs on a throwaway branch or inside the real module skeleton, ends in **go** or **fallback**, and its outcome is written in the table below and in the owning document. A fallback that changes a D-id requires a PLAN amendment. Scope notes since the scope revision: S1 now covers the KMP plugin and `com.android.kotlin.multiplatform.library`; S2 runs in common code; S7 also yields the release build's Chaquopy keep rules.

| ID | Question | Result (fill in) | Recorded also in |
|---|---|---|---|
| S1 | KGP 2.4.20, the KMP plugin and `com.android.kotlin.multiplatform.library` resolve and build under AGP 9.4.1 | **go** (2026-10-06): AGP 9.4.1 kept, no buildscript pin; every KMP module compiles for `android` and `desktop`, `:app` and `:desktopApp` consume them, KSP 2.3.12, Metro and the Compose compiler run; the in-build assertion holds; KGP shows in the root `buildEnvironment` (`2.2.10 -> 2.4.20`), not in `:app:buildEnvironment` | D4 note in PLAN if fallback |
| S2 | Room 3 `@RawQuery` can return `PagingSource` from `commonMain` | pending | [02 Key queries](02-data-model.md#key-queries) |
| S3 | `foreign_keys` enforced with `BundledSQLiteDriver` on every pooled connection | pending | [02 Conventions](02-data-model.md#conventions) |
| S4 | Robolectric runs Room 3 with `AndroidSQLiteDriver` (DAO + migration tests) | pending | [02 Migrations and schema testing](02-data-model.md#migrations-and-schema-testing), [09 Test infrastructure](09-quality-and-release.md#test-infrastructure) |
| S5 | Nav3 API names, per-tab state retention, sheet/dialog scenes (Android) | pending | [Navigation](#navigation), [08 Navigation](08-ui-ux.md#navigation) |
| S6 | `sqlite-bundled` 16 KB alignment and APK size | pending | [09 Performance budgets](09-quality-and-release.md#performance-budgets) |
| S7 | Chaquopy embeds CPython 3.14 in `:youtube:ytdlp` under AGP 9.4.1, Gradle 9.7.1, built-in Kotlin 2.4.20 and targetSdk 37, with the app's ABI splits, and starts in `:ytx` on API 26 and on the API 37 16 KB image; the release APKs' sizes against PB12/PB13 | **go** (2026-10-06) via the self-built-master fallback: plugin 17.1.0 @ `a41f0c9` from `third_party/chaquopy-maven` (4.6 MB) + released 17.0.0 runtime payloads republished under 17.1.0; CPython 3.14.0; configuration cache clean; foreign-ABI bytes ≈ 3.5 MB per 64-bit split; `armeabi-v7a` carries 12.4 MB of unusable Python inside PB13; every `.so` 16 KB-aligned; `selftest` instrumented test written, runs on CI | [D72](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions) if they deviate; [04 YouTube engine](04-youtube.md#youtube-engine); [Python and native components](#python-and-native-components) (versions read from the runtime) |
| S8 | Metro across KMP modules and both shells | **go** (2026-10-06): Metro 1.4.5 under AGP 9.4.1 built-in Kotlin; `generateContributionProviders` on; islands need a compile-classpath (`api`) edge; Koin not needed | [D82](../PLAN.md#3-key-decisions) if Koin; [Dependency injection](#dependency-injection) |
| S9 | Compose Multiplatform UI stack on the desktop and Android | pending | [D6](../PLAN.md#3-key-decisions), [D7](../PLAN.md#3-key-decisions) if they deviate; [08 Navigation](08-ui-ux.md#navigation) |
| S10 | Room 3 with the bundled driver on every desktop target | pending | [D9](../PLAN.md#3-key-decisions) if it deviates; [02 Conventions](02-data-model.md#conventions) |
| S11 | Compose resources and Android per-app language | pending | [D83](../PLAN.md#3-key-decisions) if the fallback applies; [09 Localisation](09-quality-and-release.md#localisation) |
| S12 | Ktor over `preconfigured` OkHttp for 03's fetch pipeline | **go** (2026-10-06) | [D10](../PLAN.md#3-key-decisions) if the fallback applies; [03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline) |
| S19 | Release build with R8 and baseline profiles under AGP 9.4.1 | pending | [D96](../PLAN.md#3-key-decisions) if it deviates; [09 Macrobenchmark and profiles](09-quality-and-release.md#macrobenchmark-and-profiles) |

Spikes owned elsewhere (procedure and results in the owning document): **S13** desktop packaging and performance on real hardware — [11 Packaging and the runtime exception](11-desktop.md#packaging-and-the-runtime-exception) (M0b); **S14** sync change capture, **S15** convergence harness, **S16** server on a Raspberry Pi, **S17** network paths (Android 17 LAN permission, macOS Local Network prompt, SSE through Caddy and nginx) — [10 Testing](10-sync.md#testing) (MS0–MS2); **S18** desktop audio engine core = milestone MD0 — [11 Desktop playback engine](11-desktop.md#desktop-playback-engine).

### S1 KGP 2.4.20 under AGP 9.4.1

- **Method:** build-logic declares `implementation(libs.kotlin.gradlePlugin)` (and the other plugin artifacts). Run `./gradlew :app:buildEnvironment`, `./gradlew :core:common:compileKotlinDesktop :core:common:compileAndroidMain --info` (Unverified task names of the Android-KMP target) and `:desktopApp:compileKotlin`. Add an in-build assertion to every convention plugin: `check(project.getKotlinPluginVersion() == libs.findVersion("kotlin").get().requiredVersion) { "KGP drift: …" }` with `libs` from `VersionCatalogsExtension` ([catalog rule 5](#gradlelibsversionstoml)) (Unverified API location: `org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion`). Build `assembleDebug assembleRelease`.
- **Pass:** `kotlin-gradle-plugin:2.2.10 -> 2.4.20` (or `:2.4.20`) in the output; one KMP module with `com.android.kotlin.multiplatform.library` and `jvm("desktop")` compiles both targets and is consumed by `:app` and `:desktopApp`; KSP 2.3.12, Metro and the Compose compiler plugin run; only deprecation warnings.
- **Fallback 1:** root `build.gradle.kts` `buildscript { dependencies { classpath(libs.kotlin.gradlePlugin) } }`. **Fallback 2:** set `agp = "9.3.3"` (needs Gradle ≥ 9.5.0, satisfied by 9.7.1; Compose 1.12 needs AGP ≥ 9.2, satisfied), rerun. Record which applied.
- **CI hook:** the `static` job greps `:app:buildEnvironment` for `kotlin-gradle-plugin.*2.4.20` (PLAN M0 AC3) in addition to the in-build assertion. **Result (2026-10-06): go.** `./gradlew :app:buildEnvironment` lists no plugin classpath at all, because the plugins load through the included build `build-logic` rather than a `buildscript` block; the root project's `buildEnvironment` shows `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 -> 2.4.20`, so the `static` job greps `./gradlew -q buildEnvironment` instead (PLAN M0 AC3's check, same intent).

```mermaid
flowchart LR
  a["build-logic implementation(KGP 2.4.20)"] --> b{"buildEnvironment shows 2.4.20 and build green?"}
  b -->|yes| go["go: AGP 9.4.1"]
  b -->|no| c["root buildscript classpath(KGP)"]
  c --> d{"green?"}
  d -->|yes| go2["go: AGP 9.4.1 + buildscript pin"]
  d -->|no| e["agp = 9.3.3"]
  e --> f{"green?"}
  f -->|yes| fb["fallback: AGP 9.3.3, amend D4"]
  f -->|no| esc["escalate: AGP 9.3.1 (exact tested pair) or architect decision"]
```

### S2 Room 3 RawQuery returning PagingSource

- **Method:** in `:core:database`'s `commonMain`, three entities (`podcast`, `episode`, `podcast_group_member` subsets), `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)`, and `@RawQuery(observedEntities = [EpisodeEntity::class, PodcastEntity::class, PodcastGroupMemberEntity::class]) fun feed(query: RoomRawQuery): PagingSource<Int, EpisodeRowTuple>` with a query built like [D30](../PLAN.md#3-key-decisions)'s `FeedQueryBuilder`. Test with `paging-testing` (`TestPager` / `asSnapshot`) in `desktopTest` (bundled driver on the host), under Robolectric (`AndroidSQLiteDriver`) and on a GMD (bundled driver).
- **Also records** (02 depends on them, [02 Room 2 to Room 3 mapping](02-data-model.md#room-2-to-room-3-mapping)): the exact Room 3 names for the read transaction used by backup export (`useReaderConnection` + deferred transaction), `setJournalMode`, `@ColumnTypeConverters`, the `Migration.migrate` and `RoomDatabase.Callback` signatures, `@AutoMigration`, the `room3 { }` Gradle extension name, `@ConstructedBy` with the generated `actual`s, and how `androidx.sqlite.SQLiteException` exposes result codes with each driver.
- **Pass:** compiles in common code for both targets; three consecutive pages return `(sortDate, id)` order without gaps or duplicates; an insert into `episode` invalidates; a write to an unobserved table does not.
- **Fallback:** generated `@Query` per (source × order) ([D30](../PLAN.md#3-key-decisions)); 02 records which.

### S3 foreign_keys with the bundled driver

- **Method:** with `BundledSQLiteDriver` on a GMD: (a) insert an `episode` with a missing `podcastId` → expect a constraint error; (b) delete a `podcast` → its `episode` rows cascade; (c) run `PRAGMA foreign_keys` on the writer and on reader connections (`useReaderConnection`) of the WAL pool → expect 1 on each; (d) run `PRAGMA foreign_keys` inside a test `Migration.migrate` → expect 0 (02's table rebuilds need it off, [02 Writing migrations](02-data-model.md#writing-migrations)).
- **Pass:** all four hold.
- **Fallback:** 02's `ForeignKeysDriver` — a `SQLiteDriver` decorator whose `open()` runs `PRAGMA foreign_keys = ON` on every new connection, armed only after the first open completes (so migrations still run with foreign keys off); if (d) fails, parent-table rebuilds run in a pre-Room step of `DatabaseOpener` on a raw connection, bound in `SqliteDriverBindings` around the production and test drivers ([02 Conventions](02-data-model.md#conventions)); 02 records which.

### S4 Robolectric with AndroidSQLiteDriver

- **Method:** Robolectric 4.17, `sdk=36`, JDK 21: build `NeutrodyneDatabase` in memory and in a file with `AndroidSQLiteDriver`; run one DAO test and a `room3-testing` `MigrationTestHelper` create-v1 → validate test. Also try `BundledSQLiteDriver` and record the failure mode. Query `SELECT sqlite_version()`.
- **Pass:** DAO and migration tests green with `AndroidSQLiteDriver`; the reported SQLite version is recorded (02 keeps all SQL compatible with SQLite 3.18, so no minimum beyond that is needed).
- **Fallback:** if `MigrationTestHelper` fails under Robolectric, migration tests run only on GMD (09); if DAO tests fail, DAO tests move to GMD and 09 re-plans the unit-test budget.

### S5 Nav3 1.2 API names and scenes

- **Method:** on Android, in `:core:ui`'s host inside `:app`: two tabs with one list-detail pair, one sheet key, one dialog key; verify: `NavDisplay` parameter names (`entries` vs `backStack`, `sceneStrategies` list), `rememberDecoratedNavEntries` (or the equivalent) for per-tab decoration, `rememberViewModelStoreNavEntryDecorator`, `rememberSaveableStateHolderNavEntryDecorator`, `ListDetailSceneStrategy` metadata helpers, whether `DialogSceneStrategy`/a bottom-sheet strategy ship, `NavigationBackHandler`, `rememberNavBackStack(NavKeySerializers.savedStateConfiguration, …)` restoring keys declared in `:core:navigation` after "Don't keep activities" + `adb shell am kill`, `assistedMetroViewModel` per entry, and — for 08 — a custom `PaneScaffoldDirective` passed to `ListDetailSceneStrategy`, an `extraPane()` entry placed directly after a `listPane()` entry, a `PaneScaffoldDirective` built from `calculatePaneScaffoldDirective(…, HingePolicy.AvoidSeparating)` (hinge-aware panes on foldables), a sheet opened from the expanded `PlayerSheet` drawing above it (window-based sheet), and the order of `PredictiveBackHandler` (player sheet) versus `NavDisplay`'s back handling. Everything is written against the JetBrains 1.1 API that common code sees, running on androidx 1.2.0. `DialogSceneStrategy` ships since 1.1; check whether its metadata is the typed `DialogKey` and whether it accepts our `NdDialog`.
- **Pass:** process-death restore of both tabs' stacks; distinct ViewModels for two `PodcastKey`s; a tab's entry keeps its ViewModel while another tab is shown; sheet and dialog render as overlays and dismiss on back.
- **Fallback:** the bottom-sheet strategy is ours in any case (nav3-recipes pattern); if per-tab retention fails, accept ViewModel recreation on tab switch with state in `SavedStateHandle` and record it for 08; if an extra pane cannot follow a list pane, 08's documented fallback (`EpisodeKey` as `detailPane()`) applies.

### S6 sqlite-bundled 16 KB alignment and size

- **Method:** build the release APKs with and without `sqlite-bundled`; `zipalign -c -P 16 -v 4` on each ABI APK; `llvm-readelf -l lib/arm64-v8a/*.so` → every `LOAD` segment `Align 0x4000`; compare APK size per ABI.
- **Pass:** aligned; size delta recorded (budget context, N5: release `arm64-v8a` and `x86_64` APKs < 40 MB each including the engine, `armeabi-v7a` < 30 MB; PB12, PB13).
- **Fallback:** `AndroidSQLiteDriver` in production on Android — a one-line change in `SqliteDriverBindings`, cheap because 02 already restricts SQL to SQLite 3.18 features; the desktop keeps the bundled driver; still changes [D9](../PLAN.md#3-key-decisions), PLAN amendment required.

### S7 Chaquopy under AGP 9.4.1

Gates every YouTube-engine milestone ([D72](../PLAN.md#3-key-decisions), risk T14). Facts at planning time (2026-10-05): the latest Chaquopy release on Maven Central is 17.0.0 (2025-11-30, [Maven metadata](https://repo1.maven.org/maven2/com/chaquo/python/gradle/maven-metadata.xml)); its documentation names AGP 7.3–9.2, Python 3.10–3.14 and, for Python ≥ 3.12, only `arm64-v8a` and `x86_64` ([Chaquopy docs](https://chaquo.com/chaquopy/doc/current/android.html)); master's `VERSION.txt` is 17.1.0 and carries the AGP 9.x updates up to 9.4.1, Python 3.15 and target API 37, including read-only extracted `.so` files (Unverified beyond the repository history, [chaquopy](https://github.com/chaquo/chaquopy)). Chaquopy's FAQ warns that ABI splits "won't help much" for its native components and recommends a product-flavor dimension instead ([FAQ](https://chaquo.com/chaquopy/doc/current/faq.html)).

- **Method:**
  1. Check Maven Central for a Chaquopy release newer than 17.0.0 and start with the newest.
  2. Apply `com.chaquo.python` to `:youtube:ytdlp` (build-logic classpath and bare `id`, catalog rule 3; if that fails, a versioned `plugins {}` entry) with the configuration of [Build variants and ABIs](#build-variants-and-abis): Python 3.14, `abiFilters` `arm64-v8a` + `x86_64`, empty `pip`, `buildFeatures.aidl`; a hello-world `neutrodyne_ytx/selftest.py` (returns the Python and OpenSSL versions) and `YtxService` with `android:process=":ytx"` that calls `Python.start(AndroidPlatform(context))` and answers `ping` and `selftest` (kept after a go; M9a adds the engine methods).
  3. Under AGP 9.4.1, Gradle 9.7.1, built-in Kotlin 2.4.20, targetSdk 37 and the configuration cache: `./gradlew check assembleDebug assembleRelease` (`release` is minified, so missing keep rules show up; `selftest` also runs on it; the rest of the release configuration is S19's).
  4. If the released plugin fails: build master (17.1.0) at a pinned commit, publish it, together with the CPython `target` artifacts it needs (that repository then alone serves `com.chaquo.python*`), to `third_party/chaquopy-maven/` (the commented `exclusiveContent` repository in `settings.gradle.kts`; record its size) and repeat 2–3.
  5. Measure and record: the size per ABI of the release APKs (PB12/PB13; the budgets are Unverified estimates until this measurement, and a miss goes to the PO, PLAN M0 AC1) with default and with legacy native packaging (`useLegacyPackaging`); the bytes of foreign-ABI Chaquopy assets in each split and of ABI-independent Python assets in the `armeabi-v7a` APK (`unzip -l`); `Python.start` + `selftest` in `:ytx` on the API 26 GMD and on the API 37 16 KB image (no `UnsatisfiedLinkError` from read-only rules, P35); `llvm-readelf -l` 16 KB alignment of every `.so`, including those inside Chaquopy's asset zips; time from binding `YtxService` to the `selftest` result on the GMDs (first indication only; the reference-device numbers come from the M9a spike); that a host Python 3.14 (`buildPython`) works in CI via `actions/setup-python` and inside 09's pinned release container `python:3.14-slim-trixie` (the image `release.yml` and the `repro` job build in); Chaquopy's Gradle configuration names and runtime coordinates (input to `checkPythonLicences`), whether its runtime appears on `releaseRuntimeClasspath` (Licensee), whether its AAR ships consumer keep rules, the AGP 9 names of the `splits.abi` DSL, and the component versions bundled in the runtime (OpenSSL, SQLite, …) for the [lockfile](#python-and-native-components).
- **Pass (go):** green build and `selftest` on both images with a released or self-built Chaquopy, configuration cache intact (or a recorded, accepted exception); in each **64-bit** APK the foreign-ABI Chaquopy bytes (the other 64-bit ABI's `lib-dynload` and any other per-ABI assets) ≤ 5 MB; in the **`armeabi-v7a`** APK every Python and Chaquopy byte is unusable (target 0; estimate ≈ 12–13 MB: standard library 4.5 MB, yt-dlp 3.1 MB, shim, two 64-bit `lib-dynload` sets ≈ 2.5 MB each, Unverified) and is accepted only while that APK stays within PB13 (< 30 MB for the release APK), counted there ([Open questions](#open-questions) 13).
- **Fallbacks** (in this order, [D72](../PLAN.md#3-key-decisions)): foreign-ABI assets > 5 MB in a 64-bit APK, or the `armeabi-v7a` APK over PB13 because of its Python assets → go with an ABI product-flavor dimension instead of ABI splits, whose `armeabi-v7a` flavor omits `:youtube:ytdlp`'s assets as the no-engine build does (amend [D2](../PLAN.md#3-key-decisions)); no Chaquopy build works → **A2**: python.org's official Android CPython (`arm64-v8a`, `x86_64`, [Python on Android](https://docs.python.org/3/using/android.html)) as a long-lived child process of `:ytx`, started from a launcher packaged in `jniLibs` (`useLegacyPackaging = true`, so it is installed into the executable `nativeLibraryDir`, P33), JSON over stdio; A2 not viable either → the Kotlin InnerTube client ported from yt-dlp's Unlicense source. Lowering AGP to suit Chaquopy is not on the list (open question 12).
- **CI hook:** while S7 is go, `:youtube:ytdlp` keeps Chaquopy applied from M0a and the instrumented smoke test runs `selftest` in `:ytx` on the API 26 GMD and the API 37 16 KB image; Renovate puts Chaquopy bumps behind dashboard approval and every bump repeats steps 3 and 5 ([09 Dependency updates](09-quality-and-release.md#dependency-updates)).

```mermaid
flowchart LR
  a["newest Chaquopy release on Maven Central"] --> b{"builds under AGP 9.4.1 and selftest runs in ytx on API 26 and API 37 16 KB?"}
  b -->|no| m["self-built master 17.1.0 from a local Maven repository"]
  m --> b2{"builds and selftest runs?"}
  b -->|yes| c{"foreign-ABI assets at most 5 MB per 64-bit APK and armeabi-v7a APK within PB13?"}
  b2 -->|yes| c
  c -->|yes| go["go: ABI splits"]
  c -->|no| fl["go with an ABI flavor dimension, amend D2"]
  b2 -->|no| a2["fallback A2: python.org CPython child process"]
  a2 --> d{"works?"}
  d -->|yes| fa["record A2, amend D72"]
  d -->|no| kt["fallback Kotlin InnerTube port, amend D72"]
```

- **Result (2026-10-06): go, via method step 4 (self-built master).** Step 1: Maven Central still lists 17.0.0 as the newest release (metadata timestamp 2025-11-30). Step 2–3 with 17.0.0: the plugin applies by bare `id("com.chaquo.python")` and builds `assembleDebug` under AGP 9.4.1, but it is **not configuration-cache safe** — it runs `check_build_python.py` as an external process during configuration, and its tasks capture non-serializable state (`TaskBuilder$BuildPackagesTask`, `Configuration`, `Project`, `JavaCompile`). Step 4: master's `a9f7d91` "Gradle modernizations" ([#1465](https://github.com/chaquo/chaquopy/pull/1465), milestone 17.1) rewrite the plugin around configuration-cache-safe task I/O, so master was built at pinned commit `a41f0c9d309c70a39a13775acfe40fa3dc94bfdd` (`VERSION.txt` 17.1.0) and published to `third_party/chaquopy-maven/` (**4.6 MB**, layout in its README; the lead narrowed the exclusive repository to the plugin and `com.chaquo.python.runtime` on 2026-10-06, so the 19 MB of CPython `target` zips resolve unchanged from Maven Central instead of being vendored). Only `product/gradle-plugin` is built locally — upstream's wrapper is too old for JDK 21 and a real runtime build needs the CPython `target/prefix` cross-compile tree — so the released **17.0.0** runtime artifacts (`chaquopy_java`, `bootstrap`, `chaquopy`, `libchaquopy_java`) are vendored at their true bytes and republished under the version the plugin asks for (17.1.0); master's runtime delta is irrelevant to us (`AssetPath.parent`, certifi bump, test fixes). With that, `./gradlew :youtube:ytdlp:build assembleDebug assembleRelease --configuration-cache` is green and the cache is stored and reused.
  - **APK sizes (release, default packaging):** `arm64-v8a` 25,138,602 B, `x86_64` 25,089,417 B, `armeabi-v7a` 13,833,017 B — inside PB12 (< 40 MB) and PB13 (< 30 MB). Legacy packaging (`useLegacyPackaging = true`): 15,300,262 / 15,466,977 / 11,264,557 B; not needed, so the default stands ([Manifest and permissions](#manifest-and-permissions) row).
  - **Foreign-ABI Chaquopy bytes** (stored uncompressed): `arm64-v8a` APK carries 3,550,162 B of `x86_64` payloads (bootstrap-native `.so` + `stdlib-x86_64.imy` + `requirements-x86_64.imy`); `x86_64` APK carries 3,505,248 B of `arm64-v8a` payloads — both under the 5 MB fallback trigger, so the ABI splits stay (no [D2](../PLAN.md#3-key-decisions) amendment). Chaquopy packages per-ABI content as assets, not `jniLibs`, which is why the splits cannot drop them.
  - **`armeabi-v7a` APK:** every Python byte is unusable — 12,500,853 B uncompressed / 12,390,356 B in-zip (the whole `assets/chaquopy/` tree minus the 64-bit `lib/` libraries, which *are* filtered by the split); the APK stays inside PB13 at 13.83 MB, so this is accepted per the pass criteria (counted there; [Open questions](#open-questions) 13).
  - **16 KB alignment:** `llvm-readelf -l` on every `.so` in the release APK — all `lib/arm64-v8a/*`, `assets/chaquopy/bootstrap-native/<abi>/*` and all 96 `.so` inside `stdlib-*.imy` — shows every LOAD segment at `0x4000` (16 KB). The `armeabi-v7a` APK contains no Chaquopy/CPython `lib/` libraries at all.
  - **Component versions** (read from the shipped binaries and CPython 3.14.0's `Android/android.py` dep pins): CPython 3.14.0, OpenSSL 3.0.18 (2025-09-30), SQLite 3.50.4, libffi 3.4.4, expat 2.7.3, xz 5.4.6, bzip2 1.0.8, zstd 1.5.7, zlib 1.2.8 (NDK sysroot), mimalloc + HACL* + mpdecimal bundled, certifi 2026.7.22 (`cacert.pem` sha1-identical to master). Recorded in `youtube/ytdlp/python-components.lock` and the [inventory](#python-and-native-components).
  - **Other answers the method asked for:** `splits.abi` DSL names unchanged under AGP 9.4.1 (`isEnable`/`reset()`/`include(…)`/`isUniversalApk`); Chaquopy ships no AAR and no consumer rules (our `consumer-rules.pro` is the only keep-rule source); the runtime never appears on `releaseRuntimeClasspath` (detached configurations → Licensee sees nothing; the lockfile and manual AboutLibraries entries cover it); `buildPython` comes from `-Pneutrodyne.buildPython=…` or `PATH` auto-detection (CI: `actions/setup-python` 3.14; the `python:3.14-slim-trixie` container check is CI-side, no Docker locally).
  - **Host caveat found (2026-10-06):** CPython 3.14's own `venv` creates a `bin/𝜋thon` symlink (upstream Easter egg). Under a POSIX-locale Gradle daemon (`sun.jnu.encoding=ANSI_X3.4-1968`) the name mangles to `????thon` and `extractPythonBuildPackages` fails hashing it. Builds need a UTF-8 locale (`LANG=C.UTF-8`); GitHub runners and the release container are UTF-8 already.
  - **Still open (device-side):** no KVM locally, so `Python.start` + `selftest` in `:ytx` on the API 26 GMD and the API 37 16 KB image, and the bind-to-answer timing, are exercised only by CI (`YtxSelfTestInstrumentedTest` under `youtube/ytdlp/src/androidTest`, compiled and packaged here). Master's read-only-`.so` change for target 37 (P35) is verified the same way.

### S8 Metro across KMP modules

[D82](../PLAN.md#3-key-decisions), risk T22. Gates all DI code (M0a step 8). **Run 2026-10-06 on the M0a skeleton: go — Metro stays, Koin is not needed, D82/D8 unchanged.** The spike code (package `ch.lkmc.neutrodyne.spike.s8` in `:core:common`, `:core:network` + island, `:youtube:api`/`:impl`, `:feature:podcast`, `:app`, `:desktopApp`) was deleted again; what it proved is recorded here and in [Dependency injection](#dependency-injection). Metro 1.4.5 runs under AGP 9.4.1's built-in Kotlin (graphs generate in `:app`), Gradle 9.7.1 and Kotlin 2.4.20, and its Gradle plugin is configuration-cache safe (entries reused across contribution edits). Results per question:

1. **go** — `@ContributesBinding(AppScope::class)` in `commonMain`, `androidMain` and `desktopMain` of one KMP module; each shell graph sees only its own platform variant (DI rule 10) and the common one. `internal` classes aggregate, see (10).
2. **go, with one wiring rule** — the island's contributions reach a shell graph **only when the island is on the shell's compile classpath**: an `implementation` edge does not carry Metro's contribution hints (missing binding in `:app`). `:core:network` therefore exposes `:core:network:okhttp` as `api` from `androidMain`/`desktopMain` (already true in spirit: island types appear in its public signatures). Verified from both graphs; [How islands are consumed](#source-sets-and-jvm-islands) updated.
3. **go on the desktop JVM** — the exact form under 1.4.5, verified by a `desktopTest` graph test in `:feature:podcast` and by compile + an instrumented test (CI-only) on Android: `@AssistedInject` + Metro's own `@Assisted` (matched by parameter name), a nested `@AssistedFactory fun interface Factory : ManualViewModelAssistedFactory` with `@ManualViewModelAssistedFactoryKey` **without** an argument (the key class is inferred; the `(Factory::class)` spelling sketched earlier is wrong) plus `@ContributesIntoMap(AppScope::class)`, consumed as `assistedMetroViewModel<VM, VM.Factory> { create(key) }` from `dev.zacsweers.metrox.viewmodel` (no `.compose` package). The graph extends `ViewModelGraph` and needs a contributed `MetroViewModelFactory` subclass (the exact constructor-map shape is in [Feature entry installers](#feature-entry-installers)); every module whose graph aggregates the ViewModel maps needs `metrox-viewmodel` on its compile classpath (the shells get it via `ViewModelGraph`). Argument-less ViewModels use `@ViewModelKey` bare (implicit class key). The per-entry decorator is `rememberViewModelStoreNavEntryDecorator()` (`androidx.lifecycle.viewmodel.navigation3`) passed as `entryDecorators` to JB `navigation3-ui` 1.1.2's `NavDisplay(backStack =, onBack =, entryDecorators =, entryProvider = entryProvider { entry<K> { key -> … } })`; store-level semantics (one ViewModel per entry store and key, another store = another instance, `clear()` releases) were proven on the desktop JVM. *Partial:* rendering Compose UI tests (`runComposeUiTest`) need a Skiko renderer (libGL/Mesa); the CI image must provide it and any `assumeTrue` guard must sit **outside** `runComposeUiTest` (Skiko initialises before the body).
4. **go** — `@Inject lateinit var` fields plus graph-level `fun inject(target: X)` for `:app`'s Activity/Service/`BroadcastReceiver`, and a contributed injector interface `@ContributesTo(AppScope::class) interface XInjector { fun inject(target: X) }` from a library module's `androidMain` merge into the graph automatically (verified: the main graph gained the interface without declaring it). Everything compiles, dexes and is exercised by the instrumented test at minSdk 26. **Correction to rule 1:** Metro has no `Lazy<T>` — `dagger.Lazy` needs the dagger interop runtime, which the licence policy bans — so deferred dependencies use `dev.zacsweers.metro.Provider<T>` (invoke with `provider()`); rule 1 and the start-up text are updated. D8 note: instrumented-test method names with spaces (backticks) fail dexing at minSdk 26 (DEX < 040) — CI test names stay camelCase.
5. **go** — `@MapKey annotation class WorkerKey(val value: KClass<out ListenableWorker>)` over a contributed binding container's `@Provides @IntoMap @WorkerKey(X::class) fun x(deps…): (Context, WorkerParameters) -> ListenableWorker = { ctx, params -> … }`; the two-parameter function type is an ordinary binding. `MetroWorkerFactory` takes `Map<KClass<out ListenableWorker>, (Context, WorkerParameters) -> ListenableWorker>` and matches by `qualifiedName`; the spike verified creation through `work-testing`'s `TestListenableWorkerBuilder.setWorkerFactory` (instrumented, CI).
6. **go** — `@DependencyGraph(YtxScope::class)` aggregates only its own scope: a negative probe requesting an `AppScope`-only binding fails with `[Metro/MissingBinding]`, and the two graphs hold distinct instances of the doubly-bound island type. **One class cannot be `@SingleIn` in two scopes** (`[Metro/IncompatiblyScopedBindings]`): an implementation needed by two scopes stays unscoped and each scope declares `@Provides @SingleIn(Scope::class)` in its own contributed binding container — the "per process" singletons the design wants. `YtxScope` itself must live in `:core:common` (the island has to see it; it cannot depend on `:app`), like `AppScope`. Both live in `core/common/.../AppScope.kt`: `AppScope` is a typealias of Metro's own `dev.zacsweers.metro.AppScope` class and `YtxScope` a plain abstract class (amended 2026-10-06 after CI showed Metro warning that the `@Scope` annotations S8 first used are "probably not what you meant" as aggregation keys).
7. **go, with two syntax rules** — function types **with at least one parameter** (receivers included, so `EntryProviderScope<NavKey>.() -> Unit` qualifies) are ordinary multibinding element types; zero-parameter function types (`() -> T`) are intrinsic provider types under Metro 1.4's `enableFunctionProviders` and **cannot** be bindings (compile error). Classes cannot implement a receiver function type ("extension function type is not allowed as a supertype"), so installer elements are `@Provides @IntoSet` lambdas in contributed binding containers — exactly the `PodcastNavigation` shape; `@JvmSuppressWildcards` is not needed anywhere. `@Multibinds(allowEmpty = true)` properties in a `@ContributesTo` interface work for both shells.
8. **go** — `@DependencyGraph(AppScope::class, bindingContainers = [YouTubeBindingsModule::class])` is the exact parameter name; containers holding `@Binds` are interfaces, and under 1.4.5 `@Binds` is the **extension-receiver property** form (`@Binds val Impl.bind: Interface`), not the Dagger-style parameter form the earlier sketch showed — [YouTube bindings](#youtube-bindings) is rewritten. Implementation classes a shell container names must be `public` (an `internal` impl class is not visible to the shell module). The duplicate guard works as designed: a module contributing a binding for a `:youtube:api` interface itself fails **both** shells with `[Metro/DuplicateBinding]` naming both bindings.
9. **go** — `createDynamicGraph<DesktopAppGraph>(FakeBindings)` with a `@BindingContainer object` replacing exactly one binding, all others falling through to the real contributions (verified in `:desktopApp:test`). Constraint: `createGraph`/`createDynamicGraph` require a graph **without** a factory; graphs with factories are built through `createGraphFactory<T.Factory>()`.
10. **partial → go with a build-logic option** — by default `internal` contributed classes do **not** aggregate across modules (missing binding in the shell). `neutrodyne.metro` now sets `metro { generateContributionProviders.set(true) }` (kept): `internal` `@ContributesBinding`/`@ContributesIntoSet` classes aggregate cross-module; `@ContributesIntoMap` classes (the ViewModels) and contributed binding containers still have to be `public` — an `internal` container fails **silently at runtime** (its elements are simply missing from the set), so containers are `public` by convention. Trade-off (accepted): a `@ContributesBinding` class is no longer available as itself on a graph, only via its bound type — implementations never belong on graphs anyway.
11. **go** — editing a contribution (a value in `commonMain` of `:core:network`) propagates through incremental compilation into the aggregated desktop graph test without a clean build, and every run reused the configuration cache. Metro also reports through Lint/compiler warnings (`[Metro/SuspiciousUnusedMultibinding]` when a module's graph requests none of a contributed multibinding). Deleting contributed files wholesale mid-session left stale incremental state that one `clean` resolved.
12. **partial, as the design's fallback anticipated** — from a shell, the module-graph-assertion plugin sees only module-level edges: the island appears on `:app`'s `releaseCompileClasspath` once (the `api` edge of (2)) and four times on `releaseRuntimeClasspath`; per-source-set configurations (`commonMainImplementation` …) exist only inside the KMP module, so "islands only from platform code" stays with [`checkBannedApis`](#checkbannedapis-rules) rule 2. Lint's `NewApi` does **not** cover JVM islands: `:app`'s `checkDependencies` lint and the island's own `com.android.lint` lint both ignore an API-30 probe (`java.util.List.of`) in island code — the islands' Android compatibility rests on their tests running in the instrumented suite (as the JVM-island row already assumes) and on review.

Also settled while S8 ran: `platform(...)` BOM declarations in KMP source-set dependency blocks work on both targets (`:core:network`'s `api(project.dependencies.platform(libs.ktor.bom))`), and the Metro Gradle plugin artifact name in the catalog (`dev.zacsweers.metro:gradle-plugin`) is correct.

- **Question:** does Metro 1.4.5 under Kotlin 2.4.20 and AGP 9.4.1 build the graphs this document needs, across KMP modules, JVM islands and both shells?
- **Method:** on the module skeleton: (1) a `@ContributesBinding(AppScope::class)` in a KMP module's `commonMain`, one in its `androidMain` and one in its `desktopMain`, consumed by `AndroidAppGraph` and `DesktopAppGraph`; (2) a contribution from a JVM island (`:core:network:okhttp`) consumed from both graphs; (3) an assisted ViewModel per `NavKey` with `metrox-viewmodel-compose` (`assistedMetroViewModel`) under the per-entry `ViewModelStore` decorator on both platforms; (4) member injection of an Activity, a Service and a `BroadcastReceiver` at minSdk 26 through `graph.inject(this)` and a contributed injector interface from a library module; (5) a `WorkerFactory` from a `@ContributesIntoMap` worker map; (6) a second graph `YtxGraph` with its own scope in the `:ytx` process; (7) `Set` multibindings of a Kotlin function type (`EntryProviderInstaller`) and `@Multibinds` empty sets; (8) an explicitly included binding container (`bindingContainers = [YouTubeBindingsModule::class]`) and the compile error on a duplicate `:youtube:api` binding; (9) a test graph that replaces one binding (`createDynamicGraph` or `replaces`); (10) whether `internal` contributed classes aggregate across modules; (11) incremental and configuration-cache builds; (12) the module-graph-assertion plugin's handling of KMP source-set configurations, and whether `:app`'s Lint (`checkDependencies`) reports `NewApi` inside a JVM island.
- **Pass:** all of (1)–(9) compile and run on both shells (Android on the API 26 GMD); a missing binding fails at compile time with a readable message; incremental builds stay correct after editing a contribution. ✔ 2026-10-06: (1)–(9) and (11) fully; (3)'s Compose rendering and (5)'s factory creation run on the CI instrumented job (no local emulator/renderer); (12) partial per the planned fallback.
- **Fallback:** Koin 4.2.2 with its compiler plugin 1.2.1 (amend [D8](../PLAN.md#3-key-decisions)/[D82](../PLAN.md#3-key-decisions); this section is rewritten for Koin modules). Partial failures: (10) → contributed classes are `public`; (12) → the KMP edges move entirely into `checkBannedApis`' build-script scan. None needed.

### S9 Compose Multiplatform UI stack

[D6](../PLAN.md#3-key-decisions), [D7](../PLAN.md#3-key-decisions), risk T21. Runs in M0a step 15.

- **Question:** do Compose Multiplatform 1.12.1, material3 1.9.0 (desktop binary built against Compose 1.9.1), adaptive 1.3.0-rc01 and JetBrains `navigation3-ui` 1.1.2 work together on the desktop, and resolve to the androidx stable artifacts on Android?
- **Method:** the shared host and `NeutrodyneRoot` from `:core:ui` in `:desktopApp` and `:app`: `NavigationSuiteScaffold` switching rail and bar by window size, per-tab back stacks with `rememberNavBackStack(SavedStateConfiguration, …)`, a list-detail pair through `adaptive-navigation3`, a bottom sheet and a dialog above an expanded `PlayerSheet` (desktop popups' z-order), Escape as back through `NavigationBackHandler` on the desktop, `metroViewModel()` per entry, the window lifecycle mapping for `collectAsStateWithLifecycle`, the common `@Preview` artifact, graphics-shapes on the desktop, Material Symbols rendering; dependency reports for both targets (`:app:dependencies`, `:desktopApp:dependencies`) to confirm androidx `material3` 1.4.0, `adaptive` 1.3.0 and `navigation3-ui` 1.2.0 on Android; `runComposeUiTest` of one screen in `commonTest` on the desktop JVM.
- **Pass:** no `NoSuchMethodError`/`AbstractMethodError` at run time on the desktop; sheets draw above the player sheet; state survives tab switches; the Android resolution matches the version table.
- **Fallback:** a desktop material3 incompatibility → the multiplatform material3 version that matches Compose 1.12 only if it is not an alpha (an alpha needs a PO-4 change); a broken adaptive release candidate on the desktop → our own list-detail scene strategy in `:core:ui` for the desktop only; the result goes to D6/D7.

### S10 Room 3 on the desktop

[D9](../PLAN.md#3-key-decisions), risk T25. Runs in M0a step 20 (CI hosts) and M0b (every desktop runner).

- **Question:** does Room 3.0.3 with `BundledSQLiteDriver` (`sqlite-bundled-jvm` 2.7.1) run on Windows x64, macOS arm64, Linux x64 and Linux arm64, and do DAO and migration tests run in `desktopTest`?
- **Method:** the S2 database in `desktopTest` on each runner; `MigrationTestHelper` from `room3-testing` on the JVM (create v1 → validate); `PRAGMA foreign_keys` and WAL as in S3; `SELECT sqlite_version()`; open a database file under `AppDirs` with a non-ASCII user name in the path (Windows); measure the native library extraction location and whether a packaged app can load it with `--enable-native-access`.
- **Pass:** green on all four targets; `MigrationTestHelper` works without Robolectric; the SQLite version is recorded (02 keeps SQL within 3.18).
- **Fallback:** a missing or broken target native → that target waits for an androidx release or a self-built `sqlite-bundled` JNI library (an owner decision, as for Windows on Arm, [PO-40](../PLAN.md#48-further-product-owner-decisions)); `MigrationTestHelper` failing on the JVM → migration tests on GMD only (09).

### S11 Compose resources and per-app language

[D83](../PLAN.md#3-key-decisions), risk T23. Runs in M0a step 15.

- **Question:** can every string, plural and drawable live in Compose resources while Android keeps per-app language (`generateLocaleConfig`, AppCompat locales) and Weblate keeps working?
- **Method:** `composeResources/values{,-de,-b+sr+Latn}/strings.xml` with a string, a plural and an argument in `:core:ui` (`publicResClass = true`) and in one feature; `:app`'s `res/values*/strings.xml` with only `app_name`; switch the per-app language through `AppCompatDelegate.setApplicationLocales` and check that Compose resources follow without a restart and without the `updateConfiguration` workaround; check the generated `locales_config.xml`; `getString` outside composition in a worker; the desktop switch through `Locale.setDefault` plus a composition key; pseudo-locales on the `debug` build; a Weblate component mask over the Compose resource paths (dry run); the `Text("` scan and a translation-completeness task replacing Lint's `MissingTranslation`.
- **Pass:** the five destination labels switch language on Android without a restart and on the desktop after the setting changes; `locales_config.xml` lists the shipped locales; the release APK contains the resources (S19).
- **Fallback:** Android-only strings (and, if Compose resources do not follow the AppCompat locale, every string Android shows) stay in Android `res/` through a small `expect` string bridge in `:core:ui`; recorded in D83.

### S12 Ktor fetch pipeline

[D10](../PLAN.md#3-key-decisions). Runs in M0a step 13.

- **Question:** does Ktor client 3.6.0 on the OkHttp engine with a `preconfigured` client keep everything 03, 07 and 10 need?
- **Method:** in `desktopTest` against MockWebServer 5.5.0 (the matrix of [03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)): conditional GET with stored `ETag`/`Last-Modified` → `304`; a 301 → 308 → 200 chain followed manually with the FEED client (OkHttp and Ktor redirects off) and every hop recorded; `AuthInterceptor` on same-origin hops only; streaming the body with `bodyAsChannel()` into an Okio `HashingSink` temp file with the 32 MB cap counted in the copy loop; cancellation of the coroutine cancels the OkHttp `Call` (server sees the socket close); `Range`/`If-Range` with `Accept-Encoding: identity` on the DOWNLOAD client; gzip on FEED; SSE through Ktor's client plugin on the SYNC client with a 60-s heartbeat; error mapping of every [taxonomy](#network-error-taxonomy) row through `NetErrorClassifier` with Ktor's wrappers; whether the engine rebuilds the preconfigured client (pool and dispatcher shared or not, by counting connections).
- **Pass:** the matrix is green on the desktop JVM and in an Android instrumented run; one connection pool serves Ktor, Coil and Media3.
- **Fallback:** OkHttp behind a small `FeedHttp` interface implemented in the island for the paths that fail (feeds first), amend [D10](../PLAN.md#3-key-decisions); Ktor stays for the rest.
- **Result (2026-10-06): go.** The whole matrix is green on the desktop JVM — `:core:network`'s `desktopTest` (`S12FetchPipelineTest`, `KtorUserAgentTest`, `NetErrorClassifierTest`, `NetworkGraphTest`; MockWebServer 5.5.0 via `mockwebserver3`/`mockwebserver3-junit4`) plus the island's 45 `test`s. Evidence: `preconfigured` is rebuilt with `newBuilder()`, so every Ktor kind shares the island's dispatcher, pool, DNS chain and interceptors — `RecordedRequest.connectionIndex` is identical across FEED/API/SYNC requests through `NeutrodyneHttpClients`, and the LAN guard, `UserAgentInterceptor` and `AuthInterceptor` fire on Ktor calls. Conditional GET → 304, the manual 301 → 308 → 200 chain with every hop visible, `expectSuccess = false` status passthrough, `bodyAsChannel()` streaming into Okio `HashingSink`, the 32 MB cap mid-stream, coroutine cancellation closing the socket, `Range`/`If-Range`/`Accept-Encoding: identity` on DOWNLOAD, transparent gzip on FEED, and Ktor's SSE plugin on SYNC all behave as 03 needs; a 302 to a second MockWebServer carried no `Authorization` (same-origin rule holds through Ktor). The Android instrumented leg of the pass criterion defers to CI (no local KVM; every path it would exercise is shared JVM code the island `test` suite also runs). One pool serving Coil and Media3 holds by construction — both will consume the same `NetworkClients`/`CoreClients` family. `FeedFetcher` keeps the Ktor transport; `FeedHttp` is not needed.

### S19 Release build with R8 and baseline profiles

[D96](../PLAN.md#3-key-decisions). Runs in M0a step 24, with S7's packaging decision.

- **Question:** does the published `release` configuration — R8 full mode with `-dontobfuscate`, resource shrinking, the keep rules of [Release build and baseline profiles](#release-build-and-baseline-profiles), the `androidx.baselineprofile` 1.5.0 plugin with `benchmarkRelease` and `nonMinifiedRelease` — build and run under AGP 9.4.1 with KMP library modules in the graph, and how large are the APKs?
- **Method:** `assembleRelease`; install on the API 26 and API 36 GMDs and the API 37 16 KB image; run the smoke journey (five destinations, restore a back stack after process death, a localised string, open Settings › Licences) and, while S7 is go, `selftest` in `:ytx`; R8's `-printusage`/`-printconfiguration` reviewed for removed Chaquopy-reachable classes; a throwaway `:benchmark` (`com.android.test` + plugin) generating a baseline and startup profile into `app/src/release/generated/baselineProfiles/` with `saveInSrc` and checking that `benchmarkRelease` and `nonMinifiedRelease` exist, are signed with `neutrodynePublic` and match the KMP modules' single variant; a 10-iteration cold-start Macrobenchmark on `benchmarkRelease` with and without the profile (indication only; PB1 is measured on the reference device in M10/M11b); ProfileInstaller's behaviour after an `adb install` of the release APK (`adb shell dumpsys package dexopt`); APK sizes per ABI with default and legacy native packaging.
- **Pass:** the release APK starts and passes the smoke journey on all three images with no `ClassNotFoundException`/`NoSuchMethodError`; the plugin creates both build types and generates a profile; sizes recorded against PB12/PB13.
- **Fallback:** missing keep rules → added to `app.keep` or the module's consumer rules (never `-dontshrink`); the plugin failing under AGP 9.4.1 → profiles generated with Macrobenchmark's `BaselineProfileRule` output copied by hand into `src/main/baseline-prof.txt` until the plugin works (startup profile dropped), recorded in D96; sizes over budget → to the PO (PLAN M0 AC1).

### Verification log

| Date | Check | Result |
|---|---|---|
| (M0a) | `:feature:feeds → :feature:library` fails `assertModuleGraph` | pass (2026-10-06): `implementation(project(":feature:library"))` in `:feature:feeds` fails `:app:assertModuleGraph` under the `:feature:.* -X> :feature:.*` restriction |
| (M0a) | GPL artifact in `:core:data` fails `licenseeRelease` and `verifyDependencyPolicy` | pass (2026-10-06): a GPL-licensed artifact declared in `:core:data` fails `licenseeRelease` (unallowed SPDX) and `verifyDependencyPolicy` (Licensee JSON report) |
| (M0a) | GPL-licensed entry in `python-components.lock` fails `checkPythonLicences` | pass (2026-10-06): a `licence = "GPL-3.0-or-later"` component fails `:youtube:ytdlp:checkPythonLicences` |
| (M0a) | an APK containing `mutagen/__init__.py`, and one containing `libreadline.so`, each fail `check-apk.sh` (09) | **Done 2026-10-06:** `assets/chaquopy/mutagen/__init__.py` → "forbidden file"; `lib/arm64-v8a/libreadline.so` → "forbidden file" + "native library outside the allow-list" + "stored compressed" |
| (M0a) | `import java.io.File` in a `commonMain` file fails the build or `checkBannedApis`; an island in a `commonMain` dependency block fails `checkBannedApis` | pass (2026-10-06): `checkBannedApis` rule 1 flags `import java.io.File` in a `commonMain` file; rule 2 flags `project(":core:network:okhttp")` inside a `commonMain dependencies` block |
| (M0a) | `com.guardsquare:proguard-base` declared on any configuration fails `verifyDependencyPolicy`; the Compose desktop `*Release*` tasks are disabled | pass (2026-10-06): `implementation("com.guardsquare:proguard-base:7.7.0")` in `:core:model`'s `commonMain` fails `:core:model:verifyDependencyPolicy` ("configuration commonMainImplementation declares banned com.guardsquare:proguard-base:7.7.0 (GPL-2.0 (D3))"); `neutrodyne.desktop.application` disables every `*Release*` task, so Compose's ProGuard configuration never resolves |
| (M0a) | S7 outcome: Chaquopy version (release or master commit), Python version, packaging mode, foreign-ABI bytes per split, `selftest` on API 26 and API 37 16 KB | go (2026-10-06): Chaquopy 17.1.0 self-built @ `a41f0c9` + released 17.0.0 runtime payloads (`third_party/chaquopy-maven`, 4.6 MB), CPython 3.14.0, default packaging, 3.55 MB foreign-ABI in `arm64-v8a` / 3.51 MB in `x86_64`, `armeabi-v7a` carries 12.39 MB of unusable Python inside PB13; `selftest` instrumented test written for CI (no KVM locally) |
| (M0a) | S8 outcome: Metro or Koin; test-graph API; injector-interface syntax; module-graph plugin KMP behaviour | **Done 2026-10-06:** Metro (Koin unused); `createDynamicGraph<T>(FakeBindings)` (factory-less graphs only); `@ContributesTo` interfaces with `fun inject(target: X)` merge into graphs; shells see module-level edges only — source-set rules stay with `checkBannedApis` ([S8](#s8-metro-across-kmp-modules)) |
| (M0a) | S12 outcome: Ktor over `preconfigured` OkHttp keeps the island's pool, dispatcher, DNS chain and interceptors; the 03 fetch matrix green (conditional GET/304, manual 301 → 308 → 200 chain, streaming SHA-256, 32 MB cap, cancellation → socket close, Range + identity on DOWNLOAD, gzip on FEED, SSE on SYNC, status passthrough, same-origin auth) | **go 2026-10-06:** `S12FetchPipelineTest`/`KtorUserAgentTest`/`NetErrorClassifierTest`/`NetworkGraphTest` in `:core:network` `desktopTest` (MockWebServer 5.5.0); `connectionIndex` equality proves the shared pool; Android instrumented leg deferred to CI — no local KVM ([S12](#s12-ktor-fetch-pipeline)) |
| 2026-10-06 | `Outcome` in `:core:common` realigned from the single-parameter `Ok`/`Err(Throwable)` shape of the first M0a landing to the two-parameter `Success`/`Failure` of [Errors](#errors) — the shape every `Outcome<T, E>` signature (here `SettingsRepository.set`, later 03/05/10) needs; nothing else used the old shape yet | done with step 12; `:core:common` tests updated and green |
| (M0a) | the build fails when a module other than `:youtube:ytdlp` applies `com.chaquo.python`, when Hilt is applied, when KMP is combined with `com.android.library`, and when core-library desugaring is enabled | pending |
| (M0a) | predictive back from Settings animates on API 36 (manual) | pending |
| (M0a) | merged `release` permissions equal `app/policy/permissions.txt` (records the `androidx.core` receiver permission name) | pass (2026-10-06): `verifyManifestPermissions` green on the merged `release` manifest; `permissions.txt` holds `INTERNET` and `ch.lkmc.neutrodyne.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (the `androidx.core` receiver permission name) |
| (M0a) | the CI-built and a locally built `arm64-v8a` release APK carry the same certificate, equal to 09's certificate script's output, and each installs over the other with `adb install -r` keeping the app's data (PLAN M0 AC8) | pending |
| (M0a) | `aapt2 dump badging` of the CI release APKs: package `ch.lkmc.neutrodyne`, **no** `application-debuggable`, no `testOnly`; `apksigner verify --print-certs`: v2 + v3, no v1 (PLAN M0 AC7) | **Done locally 2026-10-06** (`check-apk.sh --published` runs exactly these checks on all three release APKs: PASS; signer `3808fe37…`); re-verified on CI output when `ci.yml` first runs |
| (M0a) | a local `debug` build is `ch.lkmc.neutrodyne.debug`, debuggable, contains LeakCanary and installs beside the release build; the release APKs of `ci.yml`'s `assemble` job and of `release.yml` contain no LeakCanary, no `app/src/debug/` class and no debuggable flag (`check-apk.sh --published`, PLAN M0 AC9) | Partially done 2026-10-06: debug APK is `ch.lkmc.neutrodyne.debug` + `application-debuggable` (aapt2), and `check-apk.sh --published` fails it on package/debuggable/pseudo-locales while passing the release APKs (no LeakCanary/`src/debug` classes — verified); LeakCanary lands with the debug tooling, install-beside needs a device — both pending |
| (M0a) | GitHub push protection accepts the committed `signing/neutrodyne-public.keystore`, or the documented bypass reason was needed | pending |
| (M0a while S7 is go, else M9a) | `YtxProcessStartTest`: no initializer, `AndroidAppGraph`, database, DataStore, WorkManager or ACRA in `:ytx` | pending |
| (M0b) | a desktop image containing `_dbm`, `libreadline`, `AppRun` or `libfuse` fails `check-desktop-image.sh` (09) | pending |
| (M0b) | a second desktop launch hands its arguments to the first and exits; the bundled runtime matches `runtime.lock` and the attached source tarball (`JAVA_VERSION`, the smoke line's vendor and version, byte-identical native libraries; [11 Runtime exception obligations and checks](11-desktop.md#runtime-exception-obligations-and-checks)) | pending |
| (M9a) | `assembleRelease -Pneutrodyne.youtubeEngine=false` contains no Chaquopy, CPython, yt-dlp or `YtxService`, and its Licences screen omits the engine entries | pending |
| (MD3) | the desktop image built with `-Pneutrodyne.youtubeEngine=false` contains no python-build-standalone runtime and no yt-dlp | pending |
| (MS2) | with `ACCESS_LOCAL_NETWORK` granted for a LAN sync server on API 37, sync connects while a LAN feed still fails with `LocalNetworkUnsupported` | pending |
| (M11a) | with the update check in place, the merged `release` permissions still equal `permissions.txt` and contain no install permission (`REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; PLAN M11 AC6) | pending |

---

## Testing

Test infrastructure, runners and CI wiring: [09 Test strategy](09-quality-and-release.md#test-strategy). Foundation-specific tests (all from M0a unless stated). "Common" means `commonTest`, run on the desktop JVM; "JVM" means `desktopTest` or an island's `test`.

| Area | Level | Cases |
|---|---|---|
| `Redactor` | common (table-driven; TestParameterInjector in `desktopTest` where needed) | user-info masked; query values masked, names kept; long digit-bearing path segment masked (`/rss/a8F3kq09ZpLm2xQ` → `/rss/…xQ`); `/feed/podcast` kept; fragment dropped; `http`, `https`, IPv6 literal host, port kept; unparsable input; free text with two URLs and a trailing period; idempotence (`url(url(x)) == url(x)`); results identical to the pre-KMP `java.net.URI` reference cases |
| `suspendRunCatching`, `Outcome` | common | `CancellationException` rethrown (cancel parent while block suspends); other throwables captured; `map`/`getOrNull` |
| `SettingKey` registry | common | names match the pattern; unique; `ui.*` and `desktop.*` keys are `DEVICE`; `synced` only on allowed `PORTABLE` keys; `sync.server_url` portable and not synced; `Choice` with an unknown stored name returns the default |
| DataStore stores | JVM (`PreferenceDataStoreFactory.createWithPath` on a temp dir) | round trip per type; corrupted file → defaults + WARN; two files independent |
| `NavKeySerializers` | common | every canonical `NavKey` (the list of the canonical key table) is registered, serializes and restores through `savedStateConfiguration` |
| `IntentRouter` | common; Android adapter in Robolectric; desktop adapter (11) in `desktopTest` | every row of the [routing table](#intent-routing); upper-case scheme; 1 MB of text truncated to 4 KB; non-numeric IDs → `None`; unknown settings page → `None`; a non-internal input never reaches a `neutrodyne://open/…` route; no route performs a write (verified with fakes recording calls) |
| `KtorUserAgentTest` (2026-10-05) | JVM, MockWebServer, `NeutrodyneHttpClients` on the island | a Ktor feed, download and sync request carries the Neutrodyne User-Agent, never `ktor-client`; an explicit per-request UA is kept |
| `UserAgentInterceptor` | JVM, MockWebServer | UA format per platform; explicit UA preserved; UA kept on a redirect hop; non-ASCII version name sanitised |
| `AuthInterceptor` | JVM, MockWebServer (two servers = two origins) | header added for same origin; not added after redirect to another host; not added after `https → http` redirect on the same host; not added when `Authorization` already set; lookup returning null; a `sync:` origin never matches |
| `IdentityEncodingInterceptor` | JVM, MockWebServer | MEDIA and DOWNLOAD requests carry `Accept-Encoding: identity`; FEED requests carry OkHttp's default gzip |
| `LocalNetworkGuard` | JVM with a fake `Dns`, an SDK-level parameter and a platform parameter; MockWebServer for the interceptor | all-private on Android 37 → throws; mixed → passes; loopback → passes; all-private on 36 → passes; IPv6 ULA and link-local; IP-literal `http://192.168.1.5/feed` and `nas.local` on 37 → `LocalNetworkUnsupportedException` without a connect attempt; same URLs on 36 → request proceeds; `http://127.0.0.1:{port}` passes on 37; the SYNC mode passes a LAN address only while `LocalNetworkAccess.syncAllowed` is true and every other mode never does; the desktop platform passes everything |
| `DnsFamilyHints`, `FamilyHintDns`, `pinnedToFamily` | JVM with a fake `Dns` returning A + AAAA | no hint → all addresses; `V4` hint for `googlevideo.com` → only A records for `rr1---sn-x.googlevideo.com`, not for `notgooglevideo.com`; `V6` hint with no AAAA → all addresses; `null` clears; `pinnedToFamily(V6)` → only AAAA for every host, shares the original's connection pool and dispatcher, and is the same instance on a second call |
| Client family | JVM | every client of `NetworkClients` shares one dispatcher and pool; FEED has redirects off; YOUTUBE and SYNC carry no `AuthInterceptor`; `CoreClients` is constructible without any `CredentialLookup` binding (the `YtxGraph` compiles without one) |
| Ktor factory | JVM, MockWebServer (S12's matrix, kept as a regression suite) | conditional GET, manual redirect chain, streaming SHA-256, 32 MB cap, cancellation closes the socket, SSE on SYNC, status codes passed through (`expectSuccess = false`) |
| `NetErrorClassifier` | JVM | every row of the [taxonomy table](#network-error-taxonomy), including Ktor wrappers, with `FakeNetworkMonitor` connected and disconnected; `CancellationException` rethrown |
| `ConnectivityNetworkMonitor` | Robolectric (`ShadowConnectivityManager`) | initial value seeded without a collector; `status.value` follows a default-network change with no subscriber (eager sharing); metered/unmetered/VPN mapping |
| `DesktopNetworkMonitor` | JVM with a fake interface source | up non-loopback interface with a global address → connected; only loopback or link-local → disconnected; `isMetered` always false; VPN name heuristics; a resume event triggers an immediate re-check; no outbound connection is ever opened (a `SocketFactory` that throws proves it) |
| Graphs | Android: Robolectric in `:app`; desktop: `desktopTest` in `:desktopApp`; both also run by the nightly `no-engine-build` with `-Pneutrodyne.youtubeEngine=false` | the graph builds (Metro already fails compilation on a missing or duplicate binding); every canonical `NavKey` (and 08's `SettingsHomeKey`) has exactly one installer entry; every `AppInitializer.order` lies in a defined band; desktop: every lane of [11 Background work](11-desktop.md#background-work) is bound; `YouTubeCapabilitiesSource` reports `NOT_YET_AVAILABLE` before M9a (Android) / MD3 (desktop) and `NOT_IN_THIS_APK` in the no-engine builds (and `YouTubeEngine` is `AbsentYouTubeEngine` there); `TestSqliteDriverBindings` replaces the driver (Android) |
| Start-up ordering (M1a; each framework component from the milestone it lands in) | Robolectric in `:app` with a `DatabaseOpener` whose open completes only when the test releases it | constructing the full `Set<AppInitializer>` and member-injecting every framework component (`NeutrodynePlaybackService`, `ManualDownloadJobService`, `DownloadActionReceiver`, `YouTubeAlertActionReceiver`, `SnapshotNowReceiver`) on the main thread while the open is pending neither throws nor blocks (lazy rule, [Application start-up](#application-start-up)); the runner completes within 5 s after release (no initializer below 100 waits for the database). The desktop equivalent is 11's |
| Navigation | `runComposeUiTest` in common (desktop JVM) and Robolectric + Compose v2 rule + `StateRestorationTester` (Android) | push/pop per tab; back from a non-start root returns to Feeds; two `PodcastKey`s get different ViewModels; Library → Podcast, then Downloads, then Up next, then Library again: the podcast entry's `rememberSaveable` state and ViewModel instance survive (every tab decorated every composition); `LocalNavTab` inside an entry equals its tab; stacks restored after state restoration (Android); sheet key renders as overlay; `pushDetail` replaces a same-class top entry only when `LocalPaneLayout.partitions ≥ 2`; Escape pops on the desktop |
| `ProcessRole` | JVM (process name as a parameter) | `ch.lkmc.neutrodyne` → `MAIN`; `ch.lkmc.neutrodyne:ytx` and `ch.lkmc.neutrodyne.debug:ytx` → `YTX`; `…:acra` → `ACRA`; a `/proc/self/cmdline` buffer with a trailing NUL and padding parses like the plain name |
| `:ytx` start (M0a while S7 is go, else M9a) | instrumented (`YtxProcessStartTest`, API 26 and API 36 GMDs; API 37 16 KB image nightly) | binding `YtxService` starts the `:ytx` process; in it no `AndroidAppGraph` is created, no `AppInitializer` runs, `NeutrodyneDatabase` is never opened, no DataStore file is opened, WorkManager is never initialised, ACRA is not installed and no default-process `ContentProvider` is created (observed through an inert probe in `main` that records only after the instrumentation test has armed it — the pattern of 04's `YtxTestHooks`); killing `:ytx` leaves the main process and a running playback untouched (04's `YtxIsolationTest` covers the engine side) |
| Initializer runner | common (`runInitializers` is a plain suspend function) | runs in ascending `order`; a throwing initializer is logged and later ones still run; cancellation propagates. The `:acra` early return is checked manually once (ACRA crash dialog appears, no WorkManager or session start in its process) |
| Startup gate | Robolectric | splash condition clears when `device_settings` emitted and the database is `Ready`; clears after 400 ms with a database still `Pending`; clears after 1 s when `device_settings` never emits; a deep-link route received while `Pending` is shown after the gate opens; `StartupGate` is shown and no ViewModel (feature or `PlayerViewModel`) is created while `Pending`; `NavDisplay` appears on `Ready` |
| Build types | CI `assemble` job and 09's `check-apk.sh --published`; nightly `release-build-smoke` | release APKs: package `ch.lkmc.neutrodyne`, not debuggable, no `testOnly`, signer equal to the committed keystore's certificate, no LeakCanary, `PreviewActivity`, test activity or `app/src/debug/` class, the R8 mapping produced; `debug` APK: `ch.lkmc.neutrodyne.debug`, debuggable; the release APK starts on the API 36 GMD |
| Source-set and build policy | Gradle (CI `static` job) | `assertModuleGraph` on the three shells and `:core:testing`, `licenseeRelease`, `licensee` (desktop, server), `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis` (every rule with a fixture source set in build-logic's own tests), both `checkPythonLicences`, `checkNativeLicences` (from MD0), `verifyBundledYtDlp`, `checkBrandAssets`, KGP assertion; negative checks once per the [verification log](#verification-log) |
| `PythonLicencePolicy`, `NativeLicencePolicy`, lockfile parsers, `OpenPgpSignatureCheck` | JVM in `build-logic` | allowed and rejected SPDX expressions (`Python-2.0` and `Unicode-3.0` accepted; `GPL-2.0-only`, `LGPL-2.1-or-later` in a Python lock, `AGPL-3.0-only`, `Sleepycat`, `MPL-2.0` on code vs data, `BSD-3-Clause OR GPL-2.0-only` with and without `elected`); PBS named case, desktop lock only (PO-48 proposed default): an exact pinned `kind = "pbs-patches"` entry whose `pbsSource` names the lock's PBS release tag with a SHA-256, plus its notices entry, passes, while MPL-2.0 code outside it, a `pbs-patches` entry whose `pbsSource` has another tag or no SHA-256, a missing notices entry, and PBS patches recorded as `kind = "data"` all fail (the attached source asset is `check-runtime-sources.sh --release`'s check); an LGPL native entry without `dynamic` linking or source, and with `--enable-gpl` in its configure line; lockfile/build mismatches; a good, a tampered and a wrong-key detached signature over a fixture `SHA2-256SUMS` |
| Brand-asset generator | JVM in `build-logic` | ICO and ICNS writers produce files that the JDK and `iconutil`-free readers parse (header, entry count, sizes); generating twice yields identical bytes; the soft-alpha extraction keeps the navy background transparent at the safe-zone edge |
| Smoke | GMD API 26, 36, 37 (16 KB), `debug` and release APKs; desktop packaged-app smoke start on every runner (11) | launch, five labelled destinations, rotation, dark-mode switch, Settings → About shows version and build identity, Licences non-empty; while S7 is go, `selftest` returns from `:ytx` on API 26 and 37; the desktop smoke mode exits 0 |

Fixtures: none beyond inline tables; `FakeNetworkMonitor` and `TestClock` in `:core:testing`.

---

## Delivery by milestone

| Milestone | Foundation work |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) — M0a | Steps 1–28 of the [M0 scaffold checklist](#m0-scaffold-checklist): toolchain, catalog, convention plugins with guards, every module stub on its targets (`:youtube:ytdlp` with Chaquopy, `YtxService` in `:ytx` (`ping`, `selftest`), the `ProcessRole.YTX` branch and `YtxProcessStartTest` if S7 is go; no `:update:*` modules), dependency and source-set rules and policy tasks (both Python locks, `verifyBundledYtDlp` as a no-op), build types `release` (published, R8, `neutrodynePublic`) and `debug` (`.debug`, `app/src/debug/`, `DebugToolsInitializer`, initializer 0) without flavors, the committed `signing/neutrodyne-public.keystore` and `signing/README.md`, the baseline-profile consumer wiring (no profiles yet), ABI splits, version `0.1.0`/`10095`, `:core:common`, `:core:model` basics, `:core:navigation` with `NavKeySerializers` and `IntentRouter`, `:core:datastore`, the OkHttp island and the Ktor factory with both network monitors, Metro graphs (`AndroidAppGraph`, `YtxGraph`, a compiling `DesktopAppGraph`) with an empty `YouTubeBindingsModule`, the shared Nav3 host and root layout in `:core:ui`, Compose resources, process-aware `NeutrodyneApplication` and initializer runner, manifest subset, M0a backup rule files, About/Licences, ACRA wiring, CONTRIBUTING/PR template, spikes S1–S12 and S19, `v0.1.0` |
| M0 — M0b | Steps 29–37: the desktop shell on Metro with `AppDirs`, single instance, smoke mode and file logging; desktop DataStore paths; `DesktopYouTubeBindingsModule` (empty); the brand-asset generator and committed brand outputs; `neutrodyne.desktop.application` packaging with `runtime.lock` and the macOS `0.x` ZIP; Licensee and AboutLibraries on `:desktopApp`, the runtime's Licences entries; the `:sync:server` skeleton with `fatJar`, Licensee and the logback ban; desktop CI and release jobs (09); S13 (11) |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | **M1a:** `:core:database` on both platforms with `SqliteDriverBindings`, `AndroidDatabaseFactory`, `DesktopDatabaseFactory` and the S2–S4/S10 outcomes applied; database-open initializer (100) and the start-up gates wired to `DatabaseOpener`; FEED and IMAGE clients in use through Ktor and Coil; `:feeds:jvm` bound on both platforms (kxml2 at run time on the desktop); `SettingsRepository` implementation (landed early with M0a step 12, 2026-10-06); `refresh-periodic` initializer (200) and the desktop refresh lane (11, 03); start-up ordering test; router: `AddPodcastKey` for direct feed URLs and `feed:`/`pcast:`/`podcast:`/`itpc:` (Android filters by 03; desktop URL schemes registered in MD2). **M1b:** `SecretStore` implementations replace `CredentialLookup.None` (initializer 120; `KeystoreCredentialStore`, `DesktopSecretStore` with JNA DPAPI) |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | Channel initializers for `new_episodes` and `grp_new_episodes` (10) and per-group channel sync (140); router `SelectFeed`; both YouTube binding containers provide `StaticYouTubeCapabilitiesSource(NOT_YET_AVAILABLE)` |
| [M3](../PLAN.md#m3-import-export-and-backup) | `ExternalImportActivity` and `FileProvider` manifest entries; the desktop's `OPEN_FILE` route inputs through `DesktopOpenHandler` (11); final backup rule XML (05); restore-check initializer (110); `backup-auto-snapshot` scheduling; `import_backup` channel |
| [M4](../PLAN.md#m4-playback-core) | `:playback:core` in use by `:playback:impl`; playback service, permissions and `ArtworkProvider` manifest entries; MEDIA client; `kotlinx-coroutines-guava`, `lifecycle-process`, `kotlinx-serialization-json` in `:playback:impl`; `playback`/`alerts` channels; `:playback:impl` lint config for `@UnstableApi`; both YouTube binding containers bind `YouTubeStreamResolver` → `ExternalOnlyYouTubeStreamResolver` |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | `MediaButtonReceiver`, Auto meta-data and `automotive_app_desc.xml`; `media3-inspector` |
| [M6](../PLAN.md#m6-downloads) | **M6a:** UIDT service, `SystemForegroundService` override, `DownloadActionReceiver`, `RUN_USER_INITIATED_JOBS`/`FOREGROUND_SERVICE_DATA_SYNC`/`RECEIVE_BOOT_COMPLETED`; DOWNLOAD client under the Ktor transfer core; `download-reconcile` and `download-cleanup` initializers; the desktop download lanes (07, 11); `hasFragileUserData` confirmed; `permissions.txt` updated. **M6b:** `include(":benchmark")` (`com.android.test` + `androidx.baselineprofile` producer) for 09's out-of-process system tests |
| [M7](../PLAN.md#m7-discovery) | API client in use; exported VIEW/SEND filters complete (03); `PODCASTINDEX_*` plumbing via `BuildInfo` on both apps |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | Both YouTube binding containers bind `NoOpYouTubeEnricher`, `UnsupportedYouTubeChannelSearch`, `NoExtractorChannelLookup` (every APK and desktop image in external mode until M9a/MD3) |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) | **M9a:** the host-independent engine classes in `:youtube:engine`; `YtxService` gains the engine methods (its manifest entry, the `:ytx` start-up branch and `YtxProcessStartTest` are live since M0a while S7 is go; on a fallback host they arrive here); `YouTubeAlertActionReceiver` manifest entry (04's breaker notice); `YouTubeBindingsModule` split into `app/src/youtubeEngine/` and `app/src/noYouTubeEngine/` with the `neutrodyne.youtubeEngine` switch and the nightly `no-engine-build` (09); YOUTUBE client in `:ytx` for `PyHttp` with `pinnedToFamily`, `DnsFamilyHints` in use in the main process; initializer 150; vendored yt-dlp with `verifyBundledYtDlp` active, the Android lock complete for the engine stack, AboutLibraries engine entries and `THIRD_PARTY_NOTICES.md`; `youtube/ytdlp/consumer-rules.pro` checked on the release build; packaging mode per S7 and the M9a spike. **M9b:** `tink-android` and, if the JS provider passed the spike, `quickjs-kt-android` (lockfile entry for QuickJS); `engine-update` in initializer 200; `engineManifestUrl` in use |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `material-color-utilities` in `:core:designsystem`/`:core:artwork`; `benchmark-macro-junit4` in `:benchmark` for 09's Macrobenchmarks against `benchmarkRelease`; `app/src/benchmarkRelease/` (`BenchmarkSeedReceiver`); desktop goldens with `roborazzi-compose-desktop` (09) |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | **M11a:** the update check in existing modules (`AppUpdateChecker`/`UpdateNotices` in `:core:domain`, state types incl. `UpdateDesktopAsset` in `:core:model`, implementation with `DesktopAssetSelector` in `:core:data`'s `commonMain`, `UpdateCheckWorker`/`UpdateNotifier` in `androidMain`, `DesktopUpdateCheckLane`/`DesktopUpdateNotifier` in `desktopMain`, fakes in `:core:testing`); channel `updates` (10); `app-update-check` (200; only while `updates.check_enabled` is on, never in debug builds); `updates.*` keys; routes `…/open/settings/updates` and `…/open/help/install`; no manifest, permission or module change. **M11b:** committed baseline and startup profiles (`baselineProfile(project(":benchmark"))`, `saveInSrc`), regenerated before each MINOR release; `db-maintenance` initializer and the desktop maintenance lane; `RingBufferLogSink`; final merged-manifest audit against [Platform compliance](#platform-compliance) (including P33–P35 and P41 on an API 37 device; P36–P40 no longer apply) |
| [MD0](../PLAN.md#md0-desktop-audio-engine-spike) | `neutrodyne.desktop.native` builds on the four runners; `playback/native/native-components.lock`, `NativeLicencePolicy.kt` and `checkNativeLicences`; the FFM rules of `checkBannedApis` exercised by real code |
| [MD1](../PLAN.md#md1-desktop-playback) | **MD1b:** `assembleFfmpegSource` wired into `release.yml`'s `sources` job (09); `buildFfmpeg`'s licence assertions as release blockers |
| [MD2](../PLAN.md#md2-desktop-shell-behaviours-and-os-integration) | Desktop URL schemes and file associations feeding `IntentRouter` through `DesktopOpenHandler` (11); [Desktop compliance](#desktop-compliance) DC7–DC9 verified |
| [MD3](../PLAN.md#md3-desktop-youtube-engine) | `:youtube:ytdlp-desktop` content; the desktop Python lock complete and its `checkPythonLicences`, `verifyBundledYtDlp`, `fetchPythonStandalone`, `trimPythonStandalone`; `DesktopYouTubeBindingsModule` split into `desktopApp/src/youtubeEngine/` and `desktopApp/src/noYouTubeEngine/`; the desktop image in the nightly `no-engine-build` |
| [MD5](../PLAN.md#md5-desktop-packaging-and-release) | Final [Desktop compliance](#desktop-compliance) audit; the desktop Licences screen complete (runtime, FFmpeg, miniaudio, CPython stack) |
| [MS0](../PLAN.md#ms0-sync-groundwork) | `:sync:protocol` content (its `Hlc` and `OrderKey` from M1a); conformance vectors run in `commonTest` and in `:sync:server`'s tests |
| [MS1](../PLAN.md#ms1-sync-server-core) | `:sync:server` content on the server catalog entries; server image sources and `check-server-image.sh` (09, 10) |
| [MS2](../PLAN.md#ms2-client-sync) | `ACCESS_LOCAL_NETWORK` in `:sync:impl`'s manifest and `permissions.txt`; the `SYNC` client in use with `LocalNetworkAccess`; `sync.*` setting keys; `:feature:sync` entries; route `…/open/settings/sync` |

---

## New names introduced here

| Name | Kind | Module |
|---|---|---|
| `BuildInfo` (`versionName`, `versionCode`, `debug`, `platform`, `repoUrl`, `updateManifestUrl`, `engineManifestUrl`, `youTubeEngineBundled`, `apkAbi`, `desktop`, `shippedLocales`, `podcastIndexKey`, `podcastIndexSecret`; `devTools` retired 2026-10-05, `isDebug` and `releasesAtomUrl` removed earlier) | data class | `:core:model` |
| `DesktopOs`, `DesktopArch`, `InstallKind` (values owned by 11) | enums | `:core:model` |
| `NetError`, `TlsKind` | sealed interface, enum | `:core:model` |
| `ExternalReason` (values owned by 04; M2) | enum | `:core:model` |
| `SettingsFile`, `SettingKey` (`Bool`, `Int32`, `Int64`, `Float32`, `Text`, `TextSet`, `Choice`; `synced`), `AllSettingKeys` | settings typing | `:core:model` |
| `SettingsError` | sealed error type for `SettingsRepository.set` | `:core:domain` |
| `AppInitializer`, `PlatformInfo`, `StoragePaths` (`expect`), `Nfc` (`expect`), `DateFormatter` (`expect`), `CredentialLookup`, `Origin`, `HttpClientKind`, `LocalNetworkAccess`, `GraphHolder` (`androidMain`) | interfaces, shims, types | `:core:common` |
| `LogSink`, `LogLevel`, `Redactor`, `LogcatSink`, `RollingFileSink`, `ConsoleSink`, `RingBufferLogSink` (M11b), `DebugHttpLogInterceptor` (debug builds only) | logging | `:core:common` (`LogcatSink`: `:app`; `RollingFileSink`, `ConsoleSink`: `:desktopApp`; `DebugHttpLogInterceptor`: `app/src/debug/`) |
| `NetworkStatus` | data class (interface `NetworkMonitor` placed in `:core:common`) | `:core:common` |
| `DeviceClock`, `DesktopClock` | `Clock` implementations | `:app`, `:desktopApp` |
| `CoreClients`, `NetworkClients`, `@DebugInterceptors` (empty multibound set outside Android debug builds), `IdentityEncodingInterceptor`, `LocalNetworkGuard` (`Mode.STRICT`, `Mode.SYNC`; `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor`), `LocalNetworkUnsupportedException`, `DnsFamilyHints`, `FamilyHintDns`, `pinnedToFamily`, `JvmNetErrors` | networking | `:core:network:okhttp` |
| `NeutrodyneHttpClients`, `NetErrorClassifier`, `ConnectivityNetworkMonitor`, `DesktopNetworkMonitor`, `OkHttpNeutrodyneHttpClients`, `PlatformNetErrorClassifier` | networking | `:core:network` |
| `@SettingsDataStore` | qualifier | `:core:datastore` |
| `SqliteDriverBindings`, `TestSqliteDriverBindings` | Metro binding containers | `:core:database`, `:app/src/test` |
| `AndroidAppGraph`, `YtxGraph`, `YtxScope`, `CoreBindings`, `YouTubeBindingsModule`, `MetroWorkerFactory`, `@WorkerKey` | Metro graphs, scope, bindings | `:app` |
| `DesktopAppGraph`, `DesktopCoreBindings`, `DesktopYouTubeBindingsModule` | Metro graph and bindings | `:desktopApp` |
| `ArtworkProviderGraph` | contributed graph interface | `:core:artwork` (`androidMain`) |
| `ProcessRole` (`MAIN`, `YTX`, `ACRA`) | enum + classifier | `:app` |
| `StaticYouTubeCapabilitiesSource`, `AbsentYouTubeEngine` | external-only implementations (names proposed here; 04 owns the classes) | `:youtube:impl` |
| `TopLevelKey`, `LocalAppNavigator`, `NdSceneMetadata`, `NavKeySerializers`, `IntentRouter`, `RouteInput`, `Route` | navigation | `:core:navigation` |
| `NavigationState`, `NeutrodyneNavHost`, `rememberTabLocalNavEntryDecorator`, `NdBottomSheetSceneStrategy`, `NdDialogSceneStrategy` | navigation host | `:core:ui` |
| `StartupViewModel`, `StartupState`, `StartupGate` | start-up | `:app` (`StartupGate` visuals: 08) |
| `RootUiState`, `RootActions`, `RootSlots`, `RootNotice`, `HeldChangesBanner`, `RemoteSessionCard`, `PlayerSlotState` *(2026-10-05)*; `DesktopStartupViewModel` | root contract; desktop start-up | `:core:ui`; `:desktopApp` |
| `UiText` (`Res`, `Plural`, `Raw`; `resolve()`), `UserMessage` | UI-state helpers (used by feature ViewModels, screens, notifications and lanes) | `:core:ui` |
| `assertModuleGraph` rules file `ModuleRules.kt`; tasks `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, `generateBrandAssets`, `checkBrandAssets`; `PythonLicencePolicy`, `NativeLicencePolicy`, `OpenPgpSignatureCheck`; the plugin guards | build | `build-logic` |
| Tasks `checkPythonLicences`, `verifyBundledYtDlp`, `shimTest` (Gradle side; test content 04) | build | `:youtube:ytdlp` |
| Tasks `checkPythonLicences`, `verifyBundledYtDlp` (desktop lock), `shimTestStdio` (Gradle side); `fetchPythonStandalone`, `trimPythonStandalone` (content 11) | build | `:youtube:ytdlp-desktop` |
| Tasks `buildNdmedia`, `buildFfmpeg`, `assembleFfmpegSource`, `checkNativeLicences` (content 11) | build | `:playback:native` |
| Convention plugins `neutrodyne.kmp.library`, `neutrodyne.kmp.compose`, `neutrodyne.kmp.feature`, `neutrodyne.jvm.island`, `neutrodyne.desktop.library`, `neutrodyne.desktop.native`, `neutrodyne.desktop.application`, `neutrodyne.server.application`, `neutrodyne.metro` | build | `build-logic` |
| Gradle properties `neutrodyne.youtubeEngine`, `neutrodyne.engineManifestUrl`; `BuildConfig.YOUTUBE_ENGINE`, `BuildConfig.ENGINE_MANIFEST_URL` (`neutrodyne.devTools` and `BuildConfig.DEV_TOOLS` retired 2026-10-05) | build | `gradle.properties`, `:app` |
| Build types `release` (published) and `debug` (`ch.lkmc.neutrodyne.debug`); `benchmarkRelease` and `nonMinifiedRelease` (from the baseline-profile plugin); signing config `neutrodynePublic` (the `benchmark` build type, the dev-tools build and `neutrodyneDebug` retired 2026-10-05) | build | `:app` |
| `DebugToolsInitializer` (initializer order 0, debug builds only) | start-up | `:app` (`app/src/debug/`) |
| `compose-stability.conf`, `app/policy/permissions.txt`, `app/src/main/keepRules/app.keep`, `app/src/youtubeEngine/`, `app/src/noYouTubeEngine/`, `app/src/debug/`, `app/src/release/generated/baselineProfiles/`, `desktopApp/src/youtubeEngine/`, `desktopApp/src/noYouTubeEngine/`, `desktopApp/runtime.lock`, `signing/neutrodyne-public.keystore` and `signing/README.md` (content and generation: 09), `media-sources/neutrodyne-mono.svg`, `youtube/ytdlp/consumer-rules.pro`, `youtube/ytdlp/python-components.lock`, `youtube/ytdlp-desktop/python-components.lock`, `playback/native/native-components.lock`, `third_party/chaquopy-maven/` (S7 fallback only), `playback/impl/lint.xml`, `THIRD_PARTY_NOTICES.md`, `CONTRIBUTING.md` (`signing/neutrodyne-debug.keystore` and `app/src/devTools/` retired 2026-10-05) | files | repo |
| Platform-compliance rows P33–P41 (P36–P40 not applicable since 2026-10-05); desktop-compliance rows DC1–DC10 | checklist IDs | this document |
| Spikes S8, S9, S10, S11, S12, S19 | spike IDs | this document |

---

## Open questions

1. Resolved by [D68](../PLAN.md#3-key-decisions): option (b) — rule 8 stands, `:feeds` produces `ShowNotesDocument` and `:core:data` maps it 1:1 into the `:core:model` mirror `ShowNotes`.
2. Resolved: rules 10–12 confirmed by PLAN [5.1](../PLAN.md#51-module-graph); rule 13 states the test split (09). Since the scope revision rules 14–18 expand PLAN rules 5–8.
3. Resolved (PLAN [5.1](../PLAN.md#51-module-graph)): the `NetworkMonitor` interface lives in `:core:common`, `ConnectivityNetworkMonitor` and `DesktopNetworkMonitor` in `:core:network`'s platform source sets.
4. Resolved (PLAN [5.1](../PLAN.md#51-module-graph)): `YouTubeCapabilitiesSource` (M2) and the `YouTubeStreamResolver` contract with `ExternalOnlyYouTubeStreamResolver` (M4) land early and are bound in the shells' [YouTube bindings](#youtube-bindings).
5. Resolved (PLAN [5.1](../PLAN.md#51-module-graph)): `IpFamily` lives in `:core:model`, and so does `ExternalReason` (M2; used by `:youtube:api`, `:playback:api` and `:core:ui`; 04 owns the values).
6. Obsolete (2026-10-05): core-library desugaring left with NewPipe Extractor ([D3](../PLAN.md#3-key-decisions) amended); the build now fails if it is enabled.
7. **Safer Intents (`intentMatchingFlags`).** Opt-in on Android 16 and not a target-37 change ([Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)); not adopted in v1. Under enforcement, explicit `VIEW neutrodyne://open/…` intents to `MainActivity` (notifications, 05's `ExternalImportActivity` hand-off) would no longer match its filters. Planned fix when it becomes default: add `<intent-filter><action VIEW/><category DEFAULT/><data scheme="neutrodyne" host="open"/></intent-filter>` to `MainActivity`; this exposes nothing new because `MainActivity` is exported anyway and routes only navigate (non-internal inputs never reach `…/open/…` routes, [Intent routing](#intent-routing)). Revisit at the first targetSdk bump after 37.
8. Obsolete (2026-10-05): no app bundles are built (GitHub Releases ships APKs only, [PO-2](../PLAN.md#po-2-distribution-channels)); `bundle.language.enableSplit` was removed.
9. Resolved as a [PO-18](../PLAN.md#48-further-product-owner-decisions) follow-up: the PO names the GitHub owner; `OWNER` stays a placeholder until then.
10. Obsolete (2026-10-05): there is no `play` build; every build's About links to `BuildInfo.repoUrl`.
11. **Mechanical uncertainties** resolved by M0a/M0b/M9a checks above: Chaquopy on the build-logic classpath vs a versioned `plugins {}` entry (catalog rule 3); Chaquopy's Gradle configuration names and runtime coordinates for `checkPythonLicences`, and whether its runtime reaches `releaseRuntimeClasspath` (Licensee); library-module `abiFilters` under the app's ABI splits; Chaquopy consumer keep rules and configuration-cache compatibility; AGP 9 names of the `splits.abi` DSL, of `sourceSets…kotlin.srcDir` under built-in Kotlin, of the Android-KMP plugin's consumer-keep-rule DSL and of its compile tasks; the AGP 9 names of the release split outputs; that a command-line `assembleRelease` sets no `testOnly`; conditional AboutLibraries config for the engine entries; AboutLibraries plugin ID, its use on a plain-JVM Compose application, and `aboutlibraries-core` having no Compose dependency; the `room3 { }` extension name for `schemaDirectory`; the `androidx.core` receiver-permission name; whether GitHub push protection flags the committed PKCS12 keystore; KMP source-set dependencies accepting BOM platforms (catalog rule 4); the Compose Multiplatform `@Preview` coordinate; the Metro Gradle-plugin artifact name. Items about the disabled `release` variant, `initWith`/`matchingFallbacks` for the former `benchmark` build type and `SingleArtifact.MERGED_MANIFEST` of a debuggable published build no longer apply (scope revision, release builds). (Obtainium's package IDs do not matter: the app has no `<queries>` entry for them.)
12. **AGP 9.2.x as a Chaquopy fallback** (architect, after S7). If released Chaquopy 17.0.0 fails under AGP 9.4.1 but a self-built master is undesirable, AGP 9.2.x sits inside Chaquopy 17.0.0's documented range (7.3–9.2), Compose 1.12's minimum (9.2) and Kotlin 2.4.20's tested range (to 9.3.1). It would need a [D4](../PLAN.md#3-key-decisions) amendment and gives up AGP 9.3+ features this document uses (`optimization {}` DSL, `keepRules` source set). Default: no — self-built master first, per [D72](../PLAN.md#3-key-decisions)'s order.
13. **Python assets in the `armeabi-v7a` APK.** ABI splits filter only `lib/<abi>/`; Chaquopy's assets (stdlib `.pyc`, the vendored yt-dlp, the shim, and the `lib-dynload` sets of both 64-bit ABIs) probably also land in the `armeabi-v7a` APK, which cannot run them (≈ 12–13 MB, Unverified). S7 measures it against its `armeabi-v7a` criterion. Options: accept (only while the release APK stays within PB13's 30 MB; the dead weight is counted there), an ABI flavor dimension ([D2](../PLAN.md#3-key-decisions) fallback), or a variant-API transform that strips the assets from that split (Unverified feasibility). Default: accept if within budget.
14. Resolved 2026-10-05 in 09 (its open question 24), **superseded by the scope revision (2026-10-05)**: `debug` is no longer the published build type, so `ui-test-manifest` and `ui-tooling` are `debugImplementation` dependencies of `:app` again, and `verifyDependencyPolicy` keeps them off `releaseRuntimeClasspath` ([Debug build type](#debug-build-type)). Library modules' Robolectric Compose tests keep `testImplementation(ui-test-manifest)` where they need it; shared Compose tests use `runComposeUiTest` in `commonTest`.
15. Resolved 2026-10-05 (PO-31, PO-35) and **re-resolved by the scope revision (2026-10-05)**: there are no `:update:*` modules (the update check lives in `:core:domain`, `:core:model` and `:core:data`, [D13](../PLAN.md#3-key-decisions)), no install permission, and no private key or key ceremony; the published build type is `release` (R8, not debuggable) with the committed `signing/neutrodyne-public.keystore` ([Build variants and ABIs](#build-variants-and-abis), [D96](../PLAN.md#3-key-decisions)). Hilt is replaced by Metro ([D82](../PLAN.md#3-key-decisions)); the Hilt-specific questions of earlier drafts are closed.
16. **Metro test graphs** — resolved by S8 (2026-10-06): `createDynamicGraph<T>(FakeBindings)` replaces individual bindings and lets the rest fall through; factory-bearing graphs use `createGraphFactory`. Dynamic graphs with fake binding containers are the way ([Test overrides](#test-overrides)).
17. **Navigation 3 version skew** (architect). Common code compiles against JetBrains `navigation3-ui` 1.1.2 while Android resolves androidx 1.2.0 (`:app`'s pin). Risk: behaviour differences between the two runtimes in back handling or scene metadata (risk T21). Default: keep the pin, use only 1.1 API, run the desktop UI smoke test on every PR, and move common code to JetBrains 1.2.x in one Renovate group once it is stable.
18. **`jvmTarget` 25 with Kotlin 2.4.20** (S13). Unverified that Kotlin 2.4.20 emits and tests bytecode 25 for the desktop-only modules. Default: `jvmTarget` 25; fallback `jvmTarget` 21 compiled against the JDK 25 toolchain, which still allows the final FFM API at run time ([D4](../PLAN.md#3-key-decisions)).
19. **Home of the shared root** (architect; PLAN 5.1). The navigation host and `NeutrodyneRoot` must be shared by `:app` and `:desktopApp`; PLAN 5.1 names no module for them. This document places them in `:core:ui` and adds the edge `:core:ui` → `:core:navigation` (rule 7), with everything feature- or domain-specific passed in as parameters and slots by the shells. Alternative: a new KMP module `:core:shell` that may depend on features' public entry points (a D13 change). Default: `:core:ui`; PLAN 5.1's graph should show the edge. **Resolved 2026-10-05 (scope revision):** `:core:ui` with the explicit [root contract](#appnavigator-and-per-tab-back-stacks) (`RootUiState`, `RootActions`, `RootSlots` built by the shells); PLAN 5.1's graph shows `:core:ui` → `:core:navigation`.
20. **`:core:artwork` → `:core:network:okhttp`** (architect; PLAN 5.1). Coil's OkHttp fetcher on the island's IMAGE client ([D10](../PLAN.md#3-key-decisions): Coil uses OkHttp directly) needs the island in `:core:artwork`'s platform source sets, an edge PLAN 5.1's graph does not draw. Alternative: `coil-network-ktor3` on the common Ktor IMAGE client (no island edge; one more Coil artifact and a D10 wording change). Default: the island edge.
21. **Application ID of the profiling build types.** `benchmarkRelease` and `nonMinifiedRelease` carry the published `ch.lkmc.neutrodyne` and key, so a Macrobenchmark or profile run on a personal phone replaces the user's Neutrodyne ([Debug build type](#debug-build-type)). Alternative: `applicationIdSuffix ".benchmark"` on both (Unverified that the generated profile and the Macrobenchmark target still work for the unsuffixed release). Default: no suffix; emulator or dedicated device only.
22. **`BuildInfo` on the desktop** (11). 01 defines one common `BuildInfo` data class in `:core:model`; 11's `DesktopAppGraph` sketch declares an `object BuildInfo` in `:desktopApp`. Default: 11's object becomes the loader that produces `:core:model`'s `BuildInfo` (its `desktop` field carries `os`, `arch`, `installKind`, `runtime`), and `DesktopOs`, `DesktopArch` and `InstallKind` live in `:core:model` because `:core:data`'s `DesktopAssetSelector` needs them.
23. **jsoup in Layer A** (04). `:youtube:impl` is common code now, so its former jsoup dependency cannot stay; jsoup lives only in `:feeds:jvm`. Default: Layer A reads the few channel-page fields it needs without an HTML parser, or behind an interface implemented in an island; 04 decides.
24. **Licensee and dual-licensed POMs** (M0b). Unverified whether Licensee accepts a dependency whose POM lists several licences when one is allowed. Default: the scoped `allowDependency` entries for JNA in [Licensee allow-list](#licensee-allow-list); removed if unnecessary.

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise (entries marked 2026-10-05 were checked or re-checked for the product owner's decisions of that day, including the scope revision).

Toolchain and build:
- Kotlin releases — https://kotlinlang.org/docs/releases.html
- Kotlin Gradle plugin compatibility (KGP 2.4.20 tested to AGP 9.3.1 / Gradle 9.7.0) — https://kotlinlang.org/docs/gradle-configure-project.html
- Kotlin Multiplatform (checked 2026-10-05): compatibility guide (KMP plugin 2.4.20 with Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1) https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html · AGP 9 migration (KMP plugin no longer combines with `com.android.application`/`com.android.library`; separate app module) https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html · Android-KMP library plugin (one variant; no build types, flavors, `BuildConfig`, AIDL or NDK; resources and tests opt-in) https://developer.android.com/kotlin/multiplatform/plugin · hierarchy ("Kotlin doesn't currently support sharing a source set for … JVM + Android targets") https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html · `kotlin.time.Instant`/`Clock` stable in 2.3 https://kotlinlang.org/docs/whatsnew23.html
- Compose Multiplatform (checked 2026-10-05): compatibility and versioning (1.12.1 platforms: macOS 13 arm64, Windows 10 x64/arm64, Ubuntu 20.04 x64/arm64; JDK requirements) https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html · changelog (1.12.1 on Jetpack Compose 1.12.1; desktop does not depend on `kotlinx-coroutines-swing`; 1.13.0-alpha01 AOT) https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md · native distributions (jpackage, no cross-compilation, ProGuard `*Release*` tasks, version rules) https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html · Navigation 3 (`rememberNavBackStack(SavedStateConfiguration, …)` with a `SerializersModule`) https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html · resources usage (`getString` outside composition, `publicResClass`) https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html · resources setup (`androidResources.enable` on the Android-KMP target) https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html · resource environment (desktop locale) https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html · testing (`runComposeUiTest`; common tests not as Android local tests) https://kotlinlang.org/docs/multiplatform/compose-test.html · module metadata: material3 1.9.0 → androidx 1.4.0 https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3/1.9.0/material3-1.9.0.module · adaptive-navigation3 https://repo1.maven.org/maven2/org/jetbrains/compose/material3/adaptive/adaptive-navigation3/maven-metadata.xml · JetBrains navigation3-ui https://repo1.maven.org/maven2/org/jetbrains/androidx/navigation3/navigation3-ui/maven-metadata.xml · JetBrains lifecycle https://repo1.maven.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-viewmodel-navigation3/maven-metadata.xml · Google navigation3-runtime 1.2.0 (KMP) https://dl.google.com/android/maven2/androidx/navigation3/navigation3-runtime/1.2.0/navigation3-runtime-1.2.0.module
- Compose compiler Gradle plugin — https://developer.android.com/develop/ui/compose/compiler
- AGP releases and requirements — https://developer.android.com/build/releases/gradle-plugin · https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- AGP 9.0 breaking changes (built-in Kotlin, new DSL, KGP 2.2.10 runtime dependency, targetSdk default, R8 defaults) — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- AGP 9.3 / 9.2 / 9.1 release notes — https://developer.android.com/build/releases/agp-9-3-0-release-notes · https://developer.android.com/build/releases/agp-9-2-0-release-notes · https://developer.android.com/build/releases/agp-9-1-0-release-notes
- Built-in Kotlin and kapt — https://developer.android.com/build/migrate-to-built-in-kotlin
- AGP 9.4 `CommonExtension` — https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CommonExtension
- R8 / shrink code (AGP 9.3+ `optimization {}` DSL, `keepRules` source set with `.keep` files, default rules included), past AGP notes — https://developer.android.com/build/shrink-code · https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes · R8 licence BSD-3-Clause (checked 2026-10-05) https://r8.googlesource.com/r8/+/refs/heads/main/LICENSE
- Android Studio releases — https://developer.android.com/studio/releases · https://developer.android.com/build/releases/about-agp
- Gradle releases — https://gradle.org/releases/ · https://services.gradle.org/versions/current · build environment (property precedence) https://docs.gradle.org/current/userguide/build_environment.html
- KSP — https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml · https://github.com/google/ksp/releases
- Build types and signing (checked 2026-10-05): build variants (`debug` debuggable with a generic per-machine debug keystore by default, signing configs per build type, `initWith`, `matchingFallbacks`, library resources and manifests have the lowest merge priority) https://developer.android.com/build/build-variants · app signing https://developer.android.com/studio/publish/app-signing · ABI splits (`modulename-ABI-buildvariant.apk`, universal APK only on request) https://developer.android.com/build/configure-apk-splits · `<profileable>` https://developer.android.com/guide/topics/manifest/profileable-element
- Release build and profiles (checked 2026-10-05): Macrobenchmark (non-debuggable, profileable target; ProfileInstaller ≥ 1.3) https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview · Baseline Profiles overview (ProfileInstaller, startup profiles) https://developer.android.com/topic/performance/baselineprofiles/overview · `androidx.baselineprofile` Gradle plugin 1.5.0 https://dl.google.com/android/maven2/androidx/baselineprofile/androidx.baselineprofile.gradle.plugin/maven-metadata.xml · AndroidX Test runner (`clearPackageData`) https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner · connected tests uninstall the app after the run https://discuss.gradle.org/t/how-can-i-run-espresso-tests-without-uninstalling-apk-after/15492

Libraries:
- Compose BOM mapping — https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Compose UI 1.12 requirements — https://developer.android.com/jetpack/androidx/releases/compose-ui
- Material 3 releases and Expressive status — https://developer.android.com/jetpack/androidx/releases/compose-material3 · https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29.pom
- Material 3 Adaptive — https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Material icons deprecation — https://developer.android.com/develop/ui/compose/graphics/images/material
- Navigation 3 (`entries` overload, `sceneStrategies` list since 1.1, `DialogSceneStrategy` and typed metadata since 1.1, `NavigationBackHandler`, 1.2 deep-link API; checked 2026-10-05) — https://developer.android.com/jetpack/androidx/releases/navigation3 · https://developer.android.com/guide/navigation/navigation-3/custom-layouts · https://developer.android.com/guide/navigation/navigation-3/animate-destinations · https://github.com/android/nav3-recipes
- Navigation 2 maintenance mode — https://developer.android.com/jetpack/androidx/releases/navigation
- Lifecycle 2.11 — https://developer.android.com/jetpack/androidx/releases/lifecycle
- Activity 1.13 — https://developer.android.com/jetpack/androidx/releases/activity
- core 1.19.1 — https://developer.android.com/jetpack/androidx/releases/core
- Metro (checked 2026-10-05) — overview and Gradle plugin `dev.zacsweers.metro`, Apache-2.0 https://zacsweers.github.io/metro/latest/ · compatibility (Kotlin 2.4.20 from 1.2.0) https://zacsweers.github.io/metro/latest/compatibility/ · dependency graphs (`@DependencyGraph`, factories, member injection, graph extensions, dynamic test graphs) https://zacsweers.github.io/metro/latest/dependency-graphs/ · aggregation (`@ContributesTo`, `@ContributesBinding`, `@ContributesIntoSet`/`IntoMap`, `replaces`, `excludes`) https://zacsweers.github.io/metro/latest/aggregation/ · injection types (member injection, assisted injection) https://zacsweers.github.io/metro/latest/injection-types/ · MetroX ViewModel https://zacsweers.github.io/metro/latest/metrox-viewmodel/ · MetroX ViewModel Compose (`metroViewModel`, `assistedMetroViewModel`) https://zacsweers.github.io/metro/latest/metrox-viewmodel-compose/ · MetroX Android (minSdk 28) https://zacsweers.github.io/metro/latest/metrox-android/ · releases https://repo1.maven.org/maven2/dev/zacsweers/metro/runtime/maven-metadata.xml · Koin 4.2.2 (fallback) https://repo1.maven.org/maven2/io/insert-koin/koin-core/maven-metadata.xml
- Room 3 — https://developer.android.com/jetpack/androidx/releases/room3 · Room KMP (`@ConstructedBy`, KSP per target; multi-instance invalidation Android-only; checked 2026-10-05) https://developer.android.com/kotlin/multiplatform/room · Room migrations https://developer.android.com/training/data-storage/room/migrating-db-versions
- SQLite drivers — https://developer.android.com/kotlin/multiplatform/sqlite · https://developer.android.com/jetpack/androidx/releases/sqlite · `sqlite-bundled-jvm` 2.7.1 / 2.8.0-alpha01 natives (no `windows_arm64`, no `osx_x64`; checked 2026-10-05) https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled-jvm/maven-metadata.xml · https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled-jvm/2.7.1/sqlite-bundled-jvm-2.7.1.jar · framework SQLite by API https://developer.android.com/reference/android/database/sqlite/package-summary
- DataStore — https://developer.android.com/jetpack/androidx/releases/datastore · DataStore KMP (Preferences, `createWithPath`; checked 2026-10-05) https://developer.android.com/kotlin/multiplatform/datastore
- WorkManager — https://developer.android.com/jetpack/androidx/releases/work
- Media3 — https://developer.android.com/jetpack/androidx/releases/media3 · https://github.com/androidx/media/blob/release/RELEASENOTES.md
- Media3 `@UnstableApi` lint opt-in — https://developer.android.com/reference/androidx/media3/common/util/UnstableApi
- AndroidX default minSdk — https://developer.android.com/jetpack/androidx/versions
- OkHttp — https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml · https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md · no `Dns` lookup for IP literals (checked 2026-10-05) https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RouteSelector.kt
- Ktor (checked 2026-10-05) — client engines (OkHttp engine, `preconfigured`; CIO without HTTP/2) https://ktor.io/docs/client-engines.html · client SSE https://ktor.io/docs/client-server-sent-events.html · server SSE https://ktor.io/docs/server-server-sent-events.html · releases https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml
- kotlinx — kotlinx-datetime https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml · kotlinx-coroutines (incl. `-swing`) https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-swing/maven-metadata.xml
- Coil (`coil-network-okhttp` publishes Android and JVM variants) — https://coil-kt.github.io/coil/changelog/ · https://repo1.maven.org/maven2/io/coil-kt/coil3/coil-network-okhttp/3.6.3/coil-network-okhttp-3.6.3.module
- MaterialKolor — https://github.com/jordond/MaterialKolor · https://repo1.maven.org/maven2/com/materialkolor/
- Reorderable — https://github.com/Calvin-LL/Reorderable/blob/main/LICENSE
- Licensee (KMP and JVM support, variant tasks) — https://github.com/cashapp/licensee
- module-graph-assertion (KMP section) — https://github.com/jraska/modules-graph-assert
- detekt compatibility — https://detekt.dev/docs/introduction/compatibility/
- ACRA — https://www.acra.ch/docs/Setup · https://www.acra.ch/docs/Senders
- Robolectric — https://github.com/robolectric/robolectric/releases
- Roborazzi (incl. `roborazzi-compose-desktop`) — https://github.com/takahirom/roborazzi
- Desktop libraries (checked 2026-10-05): JNA licence (Apache-2.0 OR LGPL-2.1-or-later) https://github.com/java-native-access/jna/blob/master/LICENSE · dbus-java (MIT) https://github.com/hypfvieh/dbus-java · miniaudio https://github.com/mackron/miniaudio · Sonic https://github.com/waywardgeek/sonic · quickjs-kt-jvm 1.0.15 natives https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt-jvm/1.0.15/
- Server libraries (checked 2026-10-05): sqlite-jdbc https://github.com/xerial/sqlite-jdbc · Bouncy Castle licence https://www.bouncycastle.org/about/license/ · logback licence (EPL-2.0/LGPL-2.1, banned) https://logback.qos.ch/license.html · slf4j releases https://repo1.maven.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml
- GitHub Actions releases (checked 2026-10-05) — https://github.com/actions/setup-python/releases · https://github.com/actions/attest/releases · https://github.com/actions/deploy-pages/releases

YouTube engine and signatures (checked 2026-10-05):
- Chaquopy — repository, licence (MIT) and master `VERSION.txt` 17.1.0 https://github.com/chaquo/chaquopy · https://raw.githubusercontent.com/chaquo/chaquopy/master/VERSION.txt · documentation (17.0: AGP 7.3–9.2, one module per app, Python ≥ 3.12 64-bit only, `buildPython` minor version, `pyc`, `chaquopy {}` DSL) https://chaquo.com/chaquopy/doc/current/android.html · FAQ (ABI splits "won't help much") https://chaquo.com/chaquopy/doc/current/faq.html · Maven metadata (latest 17.0.0, 2025-11-30) https://repo1.maven.org/maven2/com/chaquo/python/gradle/maven-metadata.xml · runtime builds (3.14.0-0, 3.13.9-0, …) https://repo1.maven.org/maven2/com/chaquo/python/target/maven-metadata.xml
- CPython on Android, licence and versions — https://docs.python.org/3/using/android.html · https://www.python.org/downloads/android/ · https://docs.python.org/3/license.html · https://devguide.python.org/versions/
- python-build-standalone (libedit instead of readline, `_gdbm` disabled, glibc ≥ 2.17; checked 2026-10-05) — https://github.com/astral-sh/python-build-standalone/blob/main/docs/running.rst · https://github.com/astral-sh/python-build-standalone/blob/main/docs/technotes.rst
- yt-dlp — licensing https://github.com/yt-dlp/yt-dlp#licensing · release files and channels https://github.com/yt-dlp/yt-dlp#release-files · signing key https://github.com/yt-dlp/yt-dlp/blob/master/public.key · embedding https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp · stable 2026.08.19 https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19 · PyInstaller licences (why the executables are never shipped) https://github.com/yt-dlp/yt-dlp/blob/master/THIRD_PARTY_LICENSES.txt · PyInstaller licence https://github.com/pyinstaller/pyinstaller/blob/develop/COPYING.txt · yt-dlp-ejs https://github.com/yt-dlp/ejs
- Tink — https://github.com/tink-crypto/tink-java · `tink-android` 1.23.0 https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/maven-metadata.xml · Ed25519 in `java.security.Signature` from API 33 https://developer.android.com/reference/java/security/Signature
- quickjs-kt — https://github.com/dokar3/quickjs-kt · 1.0.15 https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt-android/maven-metadata.xml · QuickJS https://bellard.org/quickjs/
- GPL components that must never ship — youtubedl-android https://github.com/yausername/youtubedl-android · bgutil-ytdlp-pot-provider https://github.com/Brainicism/bgutil-ytdlp-pot-provider · mpv-skipsilence (GPL, do not copy) https://codeberg.org/ferreum/mpv-skipsilence

Platform:
- Android 15 behaviour changes (targeting / all apps: no installs below targetSdk 24, checked 2026-10-05) — https://developer.android.com/about/versions/15/behavior-changes-15 · https://developer.android.com/about/versions/15/behavior-changes-all
- Android 10 behaviour changes (no `execve()` from the app's home directory; checked 2026-10-05) — https://developer.android.com/about/versions/10/behavior-changes-10
- Android 16 behaviour changes (targeting / all apps) — https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android 17 behaviour changes (targeting / all apps; read-only `System.load` checked 2026-10-05), background audio — https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/changes/bg-audio
- Android 17 release — https://en.wikipedia.org/wiki/Android_17 · https://developer.android.com/about/versions/17
- Android 14 behaviour changes (network-state permission for job constraints; read-only dynamic code loading, checked 2026-10-05) — https://developer.android.com/about/versions/14/behavior-changes-14
- FGS service types and timeouts — https://developer.android.com/develop/background-work/services/fgs/service-types · https://developer.android.com/develop/background-work/services/fgs/timeout
- Background FGS-start restrictions — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- UIDT jobs — https://developer.android.com/develop/background-work/background-tasks/uidt · https://developer.android.com/reference/android/app/job/JobService
- Data-transfer guidance — https://developer.android.com/about/versions/15/changes/datasync-migration
- Battery: restricted bucket, standby, doze exemption policy — https://developer.android.com/develop/background-work/background-tasks/optimize-battery · https://developer.android.com/topic/performance/appstandby · https://developer.android.com/training/monitoring-device-state/doze-standby
- 16 KB pages — https://developer.android.com/guide/practices/page-sizes · https://developer.android.com/16kb-page-size
- Notification permission — https://developer.android.com/develop/ui/views/notifications/notification-permission
- Per-app languages — https://developer.android.com/guide/topics/resources/app-languages
- Network security config (cleartext, user CAs, `<certificateTransparency>` opt-in/opt-out per domain, `<debug-overrides>` applied whenever `android:debuggable` is true; checked 2026-10-05) — https://developer.android.com/privacy-and-security/security-config
- Local network permission (`ACCESS_LOCAL_NETWORK`, `NEARBY_DEVICES` group; TCP to LAN hosts typically times out, UDP `EPERM`, `.local`, LAN DNS exemption; checked 2026-10-05) — https://developer.android.com/privacy-and-security/local-network-permission
- Auto Backup — https://developer.android.com/identity/data/autobackup
- `hasFragileUserData`; `android:debuggable` (debuggable even on user builds) and `android:testOnly` (adb-only installs; added by Android Studio on Run), checked 2026-10-05 — https://developer.android.com/guide/topics/manifest/application-element
- `<data>` matching rules — https://developer.android.com/guide/topics/manifest/data-element
- Per-source "install unknown apps" grant (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, P36; checked 2026-10-05) — https://developer.android.com/reference/android/provider/Settings
- API distribution — https://apilevels.com/
- Desktop platform facts (checked 2026-10-05): JEP 454 (FFM final in JDK 22) https://openjdk.org/jeps/454 · XDG Base Directory https://specifications.freedesktop.org/basedir/latest/ · `FOLDERID_LocalAppData` https://learn.microsoft.com/en-us/windows/win32/shell/knownfolderid · macOS local network privacy (TN3179) https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- The debuggable-build sources of the earlier revision (ART safe mode, CheckJNI, `run-as`, `adb backup`) were dropped on 2026-10-05 with the release builds (P40); the `PackageInstaller` sources were dropped earlier with the in-app installer (PO-31)

Licensing and policy:
- Unlicense — https://en.wikipedia.org/wiki/Unlicense
- SPDX licence identifiers (expressions with `OR`, `WITH LLVM-exception`, `blessing`) — https://spdx.org/licenses/
- Runtime exception (checked 2026-10-05): OpenJDK GPL-2.0 with the Classpath Exception https://openjdk.org/legal/gplv2+ce.html · GPL-2.0 https://www.gnu.org/licenses/old-licenses/gpl-2.0.html · FSF GPL FAQ https://www.gnu.org/licenses/gpl-faq.html · Adoptium FAQ https://adoptium.net/docs/faq/ · Temurin 25 binaries and source tarballs https://github.com/adoptium/temurin25-binaries/releases
- LGPL (checked 2026-10-05): FFmpeg legal checklist https://ffmpeg.org/legal.html · FFmpeg releases https://ffmpeg.org/releases/ · LGPL-2.1 https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
