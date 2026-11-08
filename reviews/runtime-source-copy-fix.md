# Review: runtime-source-copy-fix — macOS Mach-O runtime comparison

Branch `impl/runtime-source-copy-fix`, pinned HEAD `abb225b2e3d9a678a1153f69577d4718121d9764`
(2026-11-08). Base: `5ce6e8443` (local `impl/desktop-jar-verification` tip, the
release-candidate tree that owns `scripts/ci/check-runtime-sources.sh`; the file
does not exist on `origin/main` `a3f2eba`, so a main-based fix was not possible.
The file is byte-identical on `impl/m11b-release-2` / PR #341, so both commits
cherry-pick cleanly onto either branch).

## Two confirmed defects in `image_compare_file` (scripts/ci/check-runtime-sources.sh)

1. **Copy-step abort (the reported bug).** The Mach-O leg ran
   `cp "$ref_file" "$work/ref" "$img_file" "$work/img"` — four arguments, so cp
   treats the last as a destination *directory*. It does not exist, cp exits 1,
   and under `set -euo pipefail` the whole `--image` check aborts on the first
   re-signed Mach-O file: exit 1, no `FAIL:` line, no violation summary.
   Confirmed on the real candidate
   (`m11r/desktopApp/build/compose/binaries/main/app/Neutrodyne.app`, real pinned
   JDK `m11r/desktopApp/build/bundled-runtime/macos-arm64/jdk`, fixture smoke
   file): `cp: …/img: Not a directory`, exit 1.

2. **Signature-size residue (found while confirming impact).**
   `codesign --remove-signature` fixes `__LINKEDIT.filesize` but leaves
   `__LINKEDIT.vmsize` at the *signed* value — the removed blob's page-rounded
   extent. Adoptium ships ~20 KB signatures; jpackage's ad-hoc seal is ~0.4 KB.
   On the candidate: after removing both signatures the pair is equal except one
   `vmsize` byte (0x4000 vs 0x8000), so the strip-compare failed **all 33
   Mach-O files** (`33 violation(s)`). An ad-hoc re-sign cannot rescue it — the
   residue is hashed into the new cdhash (3 blob bytes differ). The design doc
   already marked this "Unverified … fallback: compare `__TEXT`"; it is now
   verified negative and recorded as such.

## Fix (two commits)

- `9eb132681` — copy the reference and image files with two separate `cp`
  commands (same pattern as the ELF leg). Nothing else about
  `runtime_dir`/`runtime_home`/release-file/legal/smoke/counterpart/release-mode
  rules touched.
- `abb225b2e` — compare Mach-O files by canonical content:
  `python3 canonical-jar-hash.py --content` on `.dylib`-named work copies (the
  `--content` mode normalizes by filename extension; `.dylib` keeps it applied
  to extensionless natives like `jspawnhelper`). Same normalization that binds
  the image scan's re-signed skiko natives — signature slots removed,
  `__LINKEDIT` extents rebound. Still fail-closed: missing python3/tool →
  empty digest → `fail`; normalized-content difference → `fail`. Script header
  and `docs/design/11-desktop.md` record the mechanism change (Kotlin
  `CanonicalJarHash` parity untouched — no tool change).

## RED → GREEN

RED (buggy script, 4 new tests):
`test_macos_resigned_native_passes`, `test_macos_resigned_code_tamper_fails`,
`test_macos_real_signed_native_passes`, `test_macos_real_code_tamper_fails` —
all fail with `cp: …/img: Not a directory` (the tamper tests also show the
abort masked the real comparison: no `FAIL:` diagnostic).

GREEN (pinned HEAD):
- `python3 scripts/ci/tests/check-desktop-image.test.py` — 37 tests, OK
  (emulated fixtures use real `LC_CODE_SIGNATURE` slots with deliberately
  different blob sizes — the residue case strip-compare cannot express; the two
  real-toolchain tests compile and ad-hoc sign a tiny dylib with clang/codesign
  under different identities — a locally built fixture, not the shipped
  artifact; skipped off-Darwin).
- Real artifact: `check-runtime-sources.sh --image <candidate Neutrodyne.app>
  --jdk <pinned Temurin tree> --smoke <fixture>` → `check-runtime-sources: OK`
  (96 runtime files; every file has a pinned counterpart; all 33 Mach-O bound).
- Real artifact tamper: same run against a copied app with one byte flipped in
  `libnet.dylib` → `FAIL: runtime file differs … libnet.dylib`, `1 violation(s)`,
  exit 1.

## Checks

- `bash -n` on the script: OK.
- `python3 tools/doccheck/checkdocs.py .` — 0 problems across 14 files.
- `node tools/doccheck/mmdcheck.mjs .` — 0 invalid of 78 diagrams (run via the
  m11r checkout's `tools/doccheck/node_modules`; this tree has none installed).
- Diff vs `5ce6e8443`: 3 files, +139/−11, 15,930 bytes
  (`check-runtime-sources.sh` ±28 lines, test file +120, doc ±1 row).

## Gaps / caveats

- The `--smoke` file used in both fixture and real-artifact runs is a **fixture**
  (the SMOKE line synthesized from `runtime.lock` values), not output of a real
  packaged-app smoke execution — current main has no smoke mode, so
  `smoke-start.sh` would open a GUI. The smoke leg itself is unchanged.
- The real-artifact run validates the whole `--image` check on the real
  candidate; it did not run `--release` mode (different inputs) and did not
  exercise the `locate_jdk` auto-discovery path (`--jdk` was passed).
- The candidate app in m11r is owned by another process and was rebuilt during
  this session — runs were repeated against the then-current tree; no files
  under m11r were modified.
- Extensionless natives rely on the `.dylib` copy names (commented in the
  script); if `canonical-jar-hash.py`'s normalization contract changes, the
  Mach-O leg changes with it — that is the intended coupling (one normalization
  definition across the image gates).
