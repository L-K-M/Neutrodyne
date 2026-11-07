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

- **Native code inside Gradle dependencies (desktop)**, which the POMs do not describe; each has a
  manual entry in `desktopApp/config/libraries/` with its upstream licence text, and
  `:desktopApp:checkEmbeddedNatives` fails on a runtime JAR with native libraries but no entries:
  - Skiko 0.150.1's native library (`org.jetbrains.skiko:skiko-awt-runtime-<os>-<arch>`) links
    Skia m150-1f14f1166a (BSD-3-Clause) and, at the commits Skia's `DEPS` pins: FreeType 2.14.2
    (FTL, Linux only; "Portions of this software are copyright © 2026 The FreeType Project
    (https://freetype.org). All rights reserved."), libpng 1.6.56 (libpng-2.0), libjpeg-turbo 3.1.0
    (IJG, BSD-3-Clause and Zlib; "This software is based in part on the work of the Independent
    JPEG Group."), HarfBuzz 13.1.0 (MIT-Modern-Variant), ICU 74 (Unicode-3.0), libwebp
    (BSD-3-Clause), Expat 2.7.4 (MIT), zlib 1.3.0.1 (Zlib), Wuffs (Apache-2.0) and, on Linux,
    the Vulkan Memory Allocator (MIT). Skiko's own NOTICE (Apache-2.0) rides on its entry.
  - JNA 5.19.1's `libjnidispatch` links libffi (MIT). JNA itself is dual-licensed
    (LGPL-2.1-or-later or Apache-2.0) and used under Apache-2.0.
  - `sqlite-bundled`'s JNI library compiles in SQLite (public domain).
- **XmlPull API inside kxml2** (desktop) — public domain, next to kxml2's own MIT-style licence.

## Radio fonts (Android and desktop)

The shared radio bundles fonts from the [Google Fonts repository](https://github.com/google/fonts),
under SIL Open Font License 1.1 (OFL-1.1):

- **Barlow 400/500/600/700**, Copyright 2017 The Barlow Project Authors
  (https://github.com/jpt/barlow).
- **Courier Prime 400/700**, Copyright 2015 The Courier Prime Project Authors
  (https://github.com/quoteunquoteapps/CourierPrime).
- **Dosis 300**, Copyright 2011 The Dosis Project Authors
  (https://github.com/googlefonts/dosis-vf). The static 300-weight instance is
  generated from the Google Fonts variable font with FontTools.

Each family's complete upstream OFL text, including its copyright statement,
is shipped in the Licences screen through `app/config/licenses/OFL-1.1-*.json`
and `desktopApp/config/licenses/OFL-1.1-*.json`; manual entries in both libraries
roots identify the font bundles. The owner-supplied wood, paper, metal and
transparent valve deck are project assets and need no third-party notice.
Reference photographs are not bundled.

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
- **Unicode Character Database extract** — Unicode-3.0 — Copyright © 1991-2024 Unicode, Inc. —
  data inside CPython.
- **Chaquopy runtime 17.0.0 payloads** (published as 17.1.0 from `third_party/chaquopy-maven`,
  self-built master `a41f0c9`, 01 S7) — MIT — Copyright (c) 2017-2025 Chaquo Ltd and contributors —
  Java/JNI bridge and the `bootstrap.imy` host. Chaquopy's bootstrap archive
  (`assets/chaquopy/bootstrap.imy`, `com.chaquo.python.runtime:bootstrap`) also carries third-party
  Python code, each with its own Licences entry:
  - **pyelftools 0.26** (`java/_vendor/elftools`) — public domain (Unlicense text) — by Eli
    Bendersky and contributors; it bundles **Construct 2.06** (`elftools/construct`) — MIT —
    Copyright (C) 2009 Tomer Filiba, 2010-2011 Corbin Simpson.
  - **setuptools 68.2.2** (`pkg_resources`) — MIT — Python Packaging Authority (its licence file
    carries no copyright line); its `pkg_resources/_vendor` wheels (versions per setuptools
    v68.2.2's `vendored.txt` and `*-dist-info` directories): **importlib_resources 5.10.2** —
    Apache-2.0 — author Barry Warsaw and contributors; **jaraco.context 4.3.0**,
    **jaraco.functools 3.6.0**, **jaraco.text 3.7.0** and **zipp 3.7.0** — MIT — Copyright Jason
    R. Coombs; **more-itertools 9.1.0** — MIT — Copyright (c) 2012 Erik Rose; **packaging 23.1** —
    Apache-2.0 OR BSD-2-Clause — Copyright (c) Donald Stufft and individual contributors;
    **platformdirs 2.6.2** — MIT — Copyright (c) 2010-202x The platformdirs developers;
    **typing_extensions 4.4.0** — PSF-2.0 — Copyright (c) Python Software Foundation.
- **LLVM libc++** — Apache-2.0 WITH LLVM-exception — statically linked into Chaquopy's JNI
  libraries (part of the LLVM Project).
- **certifi CA bundle 2026.7.22** — MPL-2.0 — unmodified data (`kind = "data"`); certificate data
  from Mozilla.
- **neutrodyne_ytx shim** — Unlicense — our own Python code (`youtube/engine/python/neutrodyne_ytx`).

- **yt-dlp 2026.08.19** and **yt-dlp-ejs 0.8.0**: Unlicense: unmodified official
  zipimport release in `youtube/ytdlp/engine/yt-dlp`; signed checksums verified at build time.
  The ejs data includes **meriyah 6.1.4**: ISC: Copyright (c) 2019 and later, KFlash and
  others; and **astring 1.9.0**: MIT: Copyright (c) 2015, David Bonnet
  <david@bonnet.cc>. Exact upstream notices from the bundled `lib.min.js` header are in
  `app/config/engine/licenses/isc-meriyah.json` and `mit-astring.json`. No JS provider
  or additional Python packages ship in M9a.

## Python components (desktop, `youtube/ytdlp-desktop/python-components.lock`)

Every desktop installer and ZIP bundles the python-build-standalone CPython child process
(release `20261003`, CPython 3.14.8, `install_only_stripped`, trimmed by
`scripts/engine/trim-python.sh`); each entry has a manual AboutLibraries definition under
`desktopApp/config/libraries/`, whose licence text is the component's own upstream notice at the
pinned version:

- **CPython 3.14.8** — Python-2.0. Its licence text in
  `desktopApp/config/licenses/python_2_0.json` is CPython's full licence verbatim: the
  PSF/BeOpen/CNRI/CWI stack plus the "Licenses and Acknowledgements for Incorporated Software"
  appendix of CPython 3.14.8's `Doc/license.rst`.
- **OpenSSL 3.5.9** — Apache-2.0 — Copyright The OpenSSL Project Authors.
- **SQLite 3.53.1** — public domain (the "blessing" dedication).
- **libffi 3.4.8** — MIT — Copyright (c) 1996-2022 Anthony Green, Red Hat, Inc and others.
- **expat 2.8.5** — MIT — Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark
  Cooper; Copyright (c) 2001-2025 Expat maintainers.
- **mpdecimal 4.0.0** (`Modules/_decimal/libmpdec`) — BSD-2-Clause — Copyright (c) 2008-2020
  Stefan Krah.
- **zstd 1.5.7** — BSD-3-Clause — Copyright (c) Meta Platforms, Inc. and affiliates.
- **xz (liblzma) 5.8.4** — public domain ("liblzma is in the public domain", xz COPYING; Lasse
  Collin and the XZ Utils authors), recorded as 0BSD.
- **bzip2 1.0.8** — bzip2-1.0.6 — Copyright (C) 1996-2019 Julian R Seward.
- **zlib 1.3.2** — Zlib — Copyright (C) 1995-2013 Jean-loup Gailly and Mark Adler.
- **libedit 20240808-3.1** — BSD-3-Clause — Copyright (c) 1992, 1993 The Regents of the
  University of California; derived from software contributed to Berkeley by Christos Zoulas of
  Cornell University.
- **ncurses 6.5** — X11 (MIT-style) — Copyright 2018-2020,2021 Thomas E. Dickey; Copyright
  1998-2017,2018 Free Software Foundation, Inc.
- **python-build-standalone build patches** (`kind = "pbs-patches"`) — MPL-2.0 — the project's
  own build patches, compiled into the CPython it ships, unmodified by us; the pinned source is
  the `python-build-standalone-20261003-src.tar.gz` asset attached to every release
  (`scripts/ci/check-runtime-sources.sh --release`).
- **certifi CA bundle 2026.06.17** — MPL-2.0 — unmodified data (`kind = "data"`); certificate
  data from Mozilla, extracted from the archive's pip-vendored certifi and shipped as
  `common/engine/cacert.pem`.
- **neutrodyne_ytx shim** — Unlicense — our own Python code
  (`youtube/engine/python/neutrodyne_ytx`).

- **yt-dlp 2026.08.19** and **yt-dlp-ejs 0.8.0**: Unlicense: unmodified official
  zipimport release in `youtube/ytdlp/engine/yt-dlp`; signed checksums verified at build time.
  The ejs data includes **meriyah 6.1.4**: ISC: Copyright (c) 2019 and later, KFlash and
  others; and **astring 1.9.0**: MIT: Copyright (c) 2015, David Bonnet
  <david@bonnet.cc>. Exact upstream notices from the bundled `lib.min.js` header are in
  `desktopApp/config/licenses/isc-meriyah.json` and `mit-astring.json`. No JS provider
  or additional Python packages ship in MD3.

## Native components

- **FFmpeg 9.0.2**: LGPL-2.1-or-later, built from the verified upstream
  archive as four separate, replaceable shared libraries loaded by the
  desktop app. Every release carries `ffmpeg-9.0.2-neutrodyne-src.tar.xz`
  with the pristine tarball, its signature, the configure line and the build
  scripts, and the desktop Licences screen shows FFmpeg's own
  `COPYING.LGPLv2.1`. AAC and HE-AAC may be covered by patents in your
  jurisdiction. The build enables no GPL, version3 or nonfree components and
  no network protocol.
- **miniaudio 0.11.22**: MIT-0 elected from its dual licence. Copyright 2025
  David Reid. Its unchanged header is checksum-verified at build time and
  compiled into `ndmedia`, never into FFmpeg. The desktop Licences screen
  carries the upstream licence text.

## Bundled runtimes

- **OpenJDK** (desktop installers and the server container image) — GPL-2.0 with the Classpath
  Exception and the GCC Runtime Library Exception; bundled unmodified from M0b/MS1 on, exact
  source attached to each release as `openjdk-{jdk}-temurin-sources.tar.gz` (this release's
  exact name follows `desktopApp/runtime.lock`; [01](docs/design/01-foundation.md) D3
  exception, 11 runtime.lock). The desktop Licences screen lists it with the full GPL-2.0,
  Classpath-exception-2.0 and GCC-Runtime-exception-3.1 texts and the source-asset name
  (`desktopApp/config/libraries/openjdk-runtime.json`, M0 AC5).
- **WiX Toolset components** (Windows MSI only) — MS-RL: WiX Util's custom action behind
  `RemoveFolderEx` and the WixUI dialog resources jpackage embeds in every MSI, unmodified;
  the `wix-{version}-src.tar.gz` source archive is attached to each release that ships an MSI
  (`desktopApp/wix.lock`, PO-48's named case).
- **jpackage launcher** — GPL-2.0 with the Classpath Exception, part of the bundled OpenJDK
  runtime above (`wixhelper.dll` in the MSI likewise).

## Modified third-party code

- **Room runtime 3.0.3** (`androidx.room3:room3-runtime-rebuild`, in the APKs and the desktop
  builds) — Apache-2.0 — © The Android Open Source Project. Rebuilt from Google's published
  sources jars with Neutrodyne's patches `0001`-`0003` (connection-pool acquisition, connection
  hand-off, index inspection; [`third_party/room3`](third_party/room3/README.md)). Each patched
  file carries a "Modified by Neutrodyne" line under its copyright header, and the published POM,
  which the Licences screens show, names the artifact "Room-Runtime (modified by Neutrodyne)".

## Copied or ported code

- `playback/engine/src/main/kotlin/ch/lkmc/neutrodyne/playback/engine/Sonic.kt`:
  Media3 1.11.1 androidx.media3.common.audio.Sonic, Apache-2.0; copyright
  2017 The Android Open Source Project and 2010 Bill Cox, Sonic Library.
- `playback/engine/src/main/kotlin/ch/lkmc/neutrodyne/playback/engine/SilenceSkipper.kt`:
  Media3 1.11.1 SilenceSkippingAudioProcessor, Apache-2.0; copyright
  2018 The Android Open Source Project.

Both files are modified Kotlin ports for the desktop. Their source headers
and desktop AboutLibraries entries retain the original licence and credits.
The Media3 source archives contain no separate NOTICE file.

## Data

- OkHttp's Public Suffix List (MPL-2.0, unmodified, in every build incl. the emergency one) — see
  the Gradle section.
- certifi CA bundle (MPL-2.0, unmodified) — see the Android Python section.
- xz's liblzma and SQLite (public domain) — see the Android Python section.
