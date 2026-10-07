<!-- SPDX-License-Identifier: Unlicense -->

# Third-party notices

Neutrodyne is Unlicense. This file lists the third-party components the shipped products bundle
(the Android APKs, the desktop installers and the server JAR), grouped by how they reach the
artefact, mirroring `app/config/libraries`, `app/config/engine/libraries`,
`desktopApp/config/libraries` and the lockfiles
`youtube/ytdlp/python-components.lock`, `youtube/ytdlp-desktop/python-components.lock` and
`playback/native/native-components.lock`. Gradle dependencies' licences are verified by Licensee
(`:app:licenseeAndroidRelease`, `:desktopApp:licensee`, `:sync:server:licensee`); the entries below
cover what those dependency reports cannot see: bundled runtimes, native code, data and copied files.

## Gradle dependencies (visible to Licensee)

Permissive licences only (Apache-2.0, MIT, BSD-2/3-Clause, Unlicense, CC0-1.0 plus the JNA dual
licence used as Apache-2.0). No bundled component beyond what `licensee*` reports, except:

- **OkHttp Public Suffix List** — `okhttp-android` 5.5.0 embeds Mozilla's
  [Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat), MPL-2.0, as
  unmodified compiled data in `assets/PublicSuffixDatabase.list` (the JVM artifact carries it at
  `okhttp3/internal/publicsuffix/PublicSuffixDatabase.list`). HTTP cookie/eTLD handling only
  ([01](docs/design/01-foundation.md)). Upstream notice: OkHttp's
  [`okhttp3/internal/publicsuffix/NOTICE`](https://github.com/lysine-dev/okhttp/blob/parent-5.5.0/okhttp/src/jvmTest/resources/okhttp3/internal/publicsuffix/NOTICE).
  Its Licences entry is `app/config/libraries/okhttp-public-suffix-list.json` (and the same file
  under `app/config/engine/libraries/` and `desktopApp/config/libraries/`).

## Python components (Android, `youtube/ytdlp/python-components.lock`)

The `:app` APKs bundle the Chaquopy-hosted CPython engine stack; each entry has a manual
AboutLibraries definition under `app/config/engine/libraries/`, whose licence text is the
component's own upstream notice at the pinned version:

- **CPython 3.14.0** — Python-2.0 (`maven:com.chaquo.python:target:3.14.0-0`). Its licence text in
  `app/config/engine/licenses/python_2_0.json` is CPython's full licence verbatim: the
  PSF/BeOpen/CNRI/CWI stack plus the "Licenses and Acknowledgements for Incorporated Software"
  appendix of CPython 3.14.0's `Doc/license.rst` (Mersenne Twister — Makoto Matsumoto and Takuji
  Nishimura; SipHash24 — Marek Majkowski; strtod/dtoa — David M. Gay, Lucent Technologies; cfuhash —
  Don Owens; Global Unbounded Sequences — Jeffrey Roberson/FreeBSD; the pyzstd-derived Zstandard
  bindings — Ma Lin; and the other notices listed there).
- **OpenSSL 3.0.18** — Apache-2.0 — Copyright The OpenSSL Project Authors.
- **SQLite 3.50.4** — public domain (the "blessing" dedication).
- **libffi 3.4.4** — MIT — Copyright (c) 1996-2022 Anthony Green, Red Hat, Inc and others.
- **expat 2.7.3** — MIT — Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark
  Cooper; Copyright (c) 2001-2025 Expat maintainers.
- **zstd 1.5.7** — BSD-3-Clause OR GPL-2.0-only (BSD-3-Clause elected) — Copyright (c) Meta
  Platforms, Inc. and affiliates.
- **xz (liblzma) 5.4.6** — public domain ("liblzma is in the public domain", xz COPYING; Lasse
  Collin and the XZ Utils authors).
- **bzip2 1.0.8** — bzip2-1.0.6 — Copyright (C) 1996-2019 Julian R Seward.
- **zlib 1.2.8** (NDK r28.2 sysroot) — Zlib — Copyright (C) 1995-2013 Jean-loup Gailly and
  Mark Adler.
- **mimalloc** (bundled with CPython) — MIT — Copyright (c) 2018-2021 Microsoft Corporation,
  Daan Leijen.
- **HACL\*** (bundled with CPython, `Modules/_hacl`) — MIT — Copyright (c) 2016-2022 INRIA, CMU and
  Microsoft Corporation; Copyright (c) 2022-2023 HACL\* Contributors.
- **mpdecimal** (bundled with CPython, `Modules/_decimal/libmpdec`) — BSD-2-Clause — Copyright (c)
  2008-2020 Stefan Krah.
- **Unicode Character Database extract** — Unicode-3.0 — Copyright © 1991-2023 Unicode, Inc. —
  data inside CPython.
- **Chaquopy runtime 17.0.0 payloads** (published as 17.1.0 from `third_party/chaquopy-maven`,
  self-built master `a41f0c9`, 01 S7) — MIT — Copyright (c) 2017-2025 Chaquo Ltd and contributors —
  Java/JNI bridge and bootstrap.
- **LLVM libc++** — Apache-2.0 WITH LLVM-exception — statically linked into Chaquopy's JNI
  libraries (part of the LLVM Project).
- **certifi CA bundle 2026.7.22** — MPL-2.0 — unmodified data (`kind = "data"`); certificate data
  from Mozilla.
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

- OkHttp's Public Suffix List (MPL-2.0, unmodified, in every build incl. the emergency one) — see
  the Gradle section.
- certifi CA bundle (MPL-2.0, unmodified) — see the Android Python section.
- xz's liblzma and SQLite (public domain) — see the Android Python section.
