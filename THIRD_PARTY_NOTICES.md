<!-- SPDX-License-Identifier: Unlicense -->

# Third-party notices

Neutrodyne is Unlicense. This file lists the third-party components the shipped products bundle
(the Android APKs, the desktop installers and the server JAR), grouped by how they reach the
artefact, mirroring `app/config/libraries`, `desktopApp/config/libraries` and the lockfiles
`youtube/ytdlp/python-components.lock`, `youtube/ytdlp-desktop/python-components.lock` and
`playback/native/native-components.lock`. Gradle dependencies' licences are verified by Licensee
(`:app:licenseeAndroidRelease`, `:desktopApp:licensee`, `:sync:server:licensee`); the entries below
cover what those dependency reports cannot see: bundled runtimes, native code, data and copied files.

## Gradle dependencies (visible to Licensee)

Permissive licences only (Apache-2.0, MIT, BSD-2/3-Clause, Unlicense, CC0-1.0 plus the JNA dual
licence used as Apache-2.0). No bundled component beyond what `licensee*` reports, except:

- **OkHttp Public Suffix List** — `okhttp3.internal.publicsuffix.PublicSuffixList` embeds the
  [publicsuffix.org](https://publicsuffix.org) list, MPL-2.0, unmodified data, in `okhttp`'s JAR.
  HTTP cookie/eTLD handling only; the file ships unmodified ([01](docs/design/01-foundation.md)).

## Python components (Android, `youtube/ytdlp/python-components.lock`)

The `:app` APKs bundle the Chaquopy-hosted CPython engine stack; each entry has a manual
AboutLibraries definition under `app/config/libraries/`:

- **CPython 3.14.0** — Python-2.0 (the full PSF/BeOpen/CNRI/CWI stack is in
  `app/config/licenses/python_2_0.json`), runtime, `maven:com.chaquo.python:target:3.14.0-0`.
- **OpenSSL 3.0.18** — Apache-2.0 — **SQLite 3.50.4** — blessing (public domain) —
  **libffi 3.4.4** — MIT — **expat 2.7.3** — MIT — **zstd 1.5.7** — BSD-3-Clause OR GPL-2.0-only
  (BSD-3-Clause elected) — **xz (liblzma) 5.4.6** — 0BSD — **bzip2 1.0.8** — bzip2-1.0.6 —
  **zlib 1.2.8** — Zlib — **mimalloc** — MIT — **HACL\*** — MIT — **mpdecimal** — BSD-2-Clause —
  all bundled inside the CPython target artifact or compiled against the NDK sysroot.
- **Unicode Character Database extract** — Unicode-3.0 — data inside CPython.
- **Chaquopy runtime 17.0.0 payloads** (published as 17.1.0 from `third_party/chaquopy-maven`,
  self-built master `a41f0c9`, 01 S7) — MIT — Java/JNI bridge and bootstrap.
- **LLVM libc++** — Apache-2.0 WITH LLVM-exception — statically linked into Chaquopy's JNI libraries.
- **certifi CA bundle 2026.7.22** — MPL-2.0 — unmodified data (`kind = "data"`).
- **neutrodyne_ytx shim** — Unlicense — our own Python code (`youtube/engine/python/neutrodyne_ytx`).

M9a adds yt-dlp (Unlicense, vendored zipimport release under `youtube/ytdlp/engine/`, checked by
`verifyBundledYtDlp`), yt-dlp-ejs (Unlicense; meriyah ISC, astring MIT) and, if the JS provider
ships, QuickJS inside quickjs-kt (MIT).

## Python components (desktop, `youtube/ytdlp-desktop/python-components.lock`)

Empty until MD3 lands the python-build-standalone stack. Will hold CPython and its bundled
libraries per PYTHON.json SPDX ids, certifi CA data (MPL-2.0, `kind = "data"`), and — subject to
PO-48 — the pinned PBS build patches as `kind = "pbs-patches"` with MPL-2.0, whose corresponding
source is the `python-build-standalone-<tag>-src.tar.gz` asset attached to the release
(`scripts/ci/check-runtime-sources.sh --release`).

## Native components

None yet. MD0 records the FFmpeg build (LGPL-2.1, dynamically linked, notices + corresponding
source attached to each release) and miniaudio in `playback/native/native-components.lock`, and the
C++/WinRT headers under `playback/native/src/`.

## Bundled runtimes

- **OpenJDK** (desktop installers and the server container image) — GPL-2.0 with the Classpath
  Exception and the GCC Runtime Library Exception; bundled unmodified from M0b/MS1 on, exact source
  attached to each release ([01](docs/design/01-foundation.md) D3 exception, 11 runtime.lock).

## Copied or ported code

None yet. Files copied or ported under the contribution rule (permissive or Unlicense sources only)
are listed here as `- \`path/in/repo\`: origin and licence`; `checkSpdxHeaders` requires each listed
file to keep its original SPDX line or credit header.

## Data

- OkHttp's Public Suffix List (MPL-2.0, unmodified) — see the Gradle section.
- certifi CA bundle (MPL-2.0, unmodified) — see the Android Python section.
