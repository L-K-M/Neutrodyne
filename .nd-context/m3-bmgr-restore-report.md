# M3 AC6 — positive `bmgr` local-transport restore gate (implementation report)

Owner: Devin swe-2-max, sole positive-transport owner.
Tree: `/home/paseo/nd-wt/m3-bmgr-restore`, branch `test/m3-bmgr-restore`.
Base: `89fded372a115b0a9637f23d9783db952d6fe2f3` (fresh main).
Matrix override honored: `/home/paseo/nd-wt/reviews/owner-backup-matrix-20261020.md` —
positive local backup/restore legs are API 31 + API 36; API 28/29 negative probes and
shared input-scope description/registry belong to sibling owners and are untouched.

## Status

Implemented, locally gated, **not device-proven**. No emulator/device run is possible
in this workspace (local gate); the positive leg's device proof is the parent's run of
the exact committed SHA on API 31/36 x86_64 default emulators plus independent raw
artifact inspection. Nothing here is a claim of hosted restore, physical acceptance,
or Python 3.14/runtime acceptance. The existing `firstLaunchSeed=restore` logical gate
(API 26/36 XML path) is unchanged and still green.

## What the slice does

Host (`scripts/ci/bmgr-check.sh <api> <evidence-dir>`, one leg per emulator):

1. Verifies the rooted default-image x86_64 emulator matches the requested API, `adb
   root`s, records original transport/enable/autorestore/`backup_local_transport_parameters`
   state (all restored in a trap) and starts a whole-run logcat capture.
2. Gates the release APK through the existing `check-apk.sh --published` plus aapt
   `application-debuggable` absence and apksigner SHA-256 recording; installs the real
   APK (no `-t`/`-r`) and the test APK (`-t`, separately). Resolves the runner package
   from `pm list instrumentation`.
3. `prepare` phase instrument (`bmgrPhase=prepare`, `bmgrNonce`): real cold start
   asserts `OpenResult.created`, waits for band-200 `backup-auto-snapshot` arm (proving
   band 110 already evaluated adoption on the empty tree), seeds the shared
   `FirstLaunchRestoreSeed.seedLibrary` fixture through the real DAOs, sets
   `backup.opml_layout=GROUPED`, enables `auto_snapshot`, publishes through the real
   DUMP-protected `SnapshotNowReceiver` (`result=-1` + `ok at=` line required;
   `pending`/`guarded` retried ≤5 inside its budget), then sets
   `backup.opml_layout=FLAT` — the transported portable PB must keep FLAT while
   `settings.json` in the archive still carries GROUPED, proving the PB file itself
   travelled and SETTINGS replay was skipped. Writes nonce-named canaries across
   `databases/`, `files/downloads|artwork|other`, `cache/`, `no_backup/`, external
   Podcasts, plus the real DB and device-PB as recorded (not corrupted) canaries.
   Cancels the debounced `backup-auto-snapshot-now` so no write interleaves with the
   backup window. Emits one `bmgrExpected` instrumentation status = base64url JSON
   (nonce, package, API, installationId, snapshot/portable SHA-256+bytes, fixture
   syncIds/groupUuids, canary records, receiver line, timestamp).
4. Eligibility: checks `dumpsys package` for `stopped=true`; only then performs one
   normal `am start .MainActivity` launch before backup (never force-stop, never
   `pm clear`, never a launch after reinstall).
5. Encryption-off control: `bmgr init` (fresh dataset), params
   `is_encrypted=false,...`, `backupnow` — the LocalTransport `_full/<pkg>` payload
   for the current set token must not exist; the `backupnow` diagnosis line is kept.
   Params back to `is_encrypted=true,is_device_transfer=false,fake_encryption_flag=false`,
   `bmgr init` again.
6. Positive backup: `bmgr backupnow` must report both `Package <pkg> with result:
   Success` and overall `Backup finished with result: Success`. The raw transport TAR
   is pulled read-only from `/data/data/com.android.localtransport/files/<current
   token>/_full/<pkg>` (token read live from `bmgr list sets`, never hardcoded) and
   verified in-place by the checker (`--bmgr-tar`): exactly
   `apps/<pkg>/_manifest` + `apps/<pkg>/f/backup/auto-snapshot.zip` +
   `apps/<pkg>/f/datastore/settings.preferences_pb`, regular files only, no dupes,
   no traversal, exact expected bytes/SHA-256, and the snapshot ZIP revalidated
   against the format contract (format/version/kind=AUTO_SNAPSHOT/installationId/
   declared entry hashes + the archived portable value = GROUPED).
7. Real lifecycle: `pm uninstall --user 0` (no `-k`), package path and `data/data`
   absence asserted, byte-identical APK reinstalled, install-time restore waited on
   (bounded 120 s poll for both payloads). `restored-files.txt` records both payload
   hashes and a full `find` of the data dir.
8. `verify` phase instrument (`bmgrExpected` handed back): the test runner's
   `callApplicationOnCreate` verifies — before `super` — exactly the two restored
   files with expected hashes, `files/` containing nothing else, no `databases/`,
   no `no_backup/`, no device PB, no canary (no graph, no seed, no deletion there).
   After the real `onCreate`: `OpenResult.created`, `backup-restore` work SUCCEEDED
   via its `restore-session-<id>` tag (RETRY/FAILED/CANCELLED/hang all fail), session
   = NEUTRODYNE_BACKUP + fromAndroidBackup + MERGE(HISTORY, UP_NEXT), full logical
   fixture assertions (shared `RestoredLibraryAsserts`), portable PB still FLAT,
   adopted `cache/import/<session>.bin` hashing to the transported snapshot, snapshot
   retirement/replacement end-state, fresh installation ID, no canary/DB/device-PB.
9. Empty control: `bmgr init`, reinstall, bounded poll asserts `files/` stays empty,
   then the `empty` phase instrument asserts `OpenResult.created`, no `backup-restore`
   work ever appears (20 s window), no fixture rows, defaults only.

Strict aggregate: `check-first-launch-restore-results.py --bmgr <root>` re-verifies
both legs from raw evidence — run.json schema/api/abi/nonce, all eight selector
markers `pass`, per-phase instrument streams (exact class#method, start/end codes,
no skip/assumption/failure, exactly one `bmgrExpected` status whose decode equals
`expectation.json`), backupnow success lines, enc-off diagnosis, `transport.tar`
hash = run.json + full member/payload/ZIP checks, restored-file map = expected
hashes with no extra `files/` content, no canary hash restored — then requires
identical sourceSha/apkSha256/testApkSha256 across legs. ADB exit codes or runner
finish codes alone are never accepted.

CI: `nightly.yml` gains one `bmgr` job — scope `''|full|bmgr`, pinned
checkout/setup-java/setup-python/setup-gradle/emulator-runner actions, builds both
release APKs before boot, API 31 and API 36 legs with `continue-on-error` so both
always produce evidence, `if: always()` strict aggregate gating report-nightly,
always-uploaded `bmgr-results` artifact. The shared input-scope description line,
PLAN/05/07/09 docs, and the sibling's legacy 28/29 probe are untouched.

## Files

- `app/src/androidTest/kotlin/ch/lkmc/neutrodyne/BmgrTransportDeviceTest.kt` (new):
  prepare/verify/empty phases, receiver broadcast via `uiAutomation.executeShellCommand`,
  bounded polls, canary writer.
- `app/src/restoreTest/kotlin/ch/lkmc/neutrodyne/BmgrTransportExpectation.kt` (new):
  expectation encode/decode (base64url JSON v1), `Roots`, pre-`onCreate`
  `verifyRestoredBeforeLaunch` / `verifyNothingRestored`.
- `app/src/androidTest/kotlin/ch/lkmc/neutrodyne/RestoredLibraryAsserts.kt` (new):
  shared restore-work wait, fixture equality, snapshot end-state — used by both tests.
- `app/src/androidTest/.../FirstLaunchRestoreDeviceTest.kt`: now delegates to the
  shared asserts; behavior unchanged.
- `app/src/androidTest/.../FirstLaunchRestoreSeed.kt`: `seedLibrary` widened to
  `internal` (test-only reuse by the transport fixture).
- `app/src/androidTest/.../NeutrodyneTestRunner.kt`: `bmgrPhase` arms pre-`onCreate`
  verification for verify/empty legs; `firstLaunchSeed` path untouched.
- `app/src/deviceSelectionTest/.../DeviceTestSelection.kt`: `BmgrTransportPhases` +
  `bmgrArmedMethod` — the class is excluded unless `bmgrPhase` + exact
  `class=…#<method>` + API 31/36 all agree; wrong combos are exclusions, not skips.
- `app/src/test/.../DeviceTestSelectionTest.kt`, `BmgrTransportExpectationTest.kt`
  (new): selection and encode/decode regressions.
- `scripts/ci/bmgr-check.sh` (new): host lifecycle above.
- `scripts/ci/check-first-launch-restore-results.py`: `--bmgr` / `--bmgr-tar` modes;
  the GMD XML path is unchanged.
- `scripts/ci/tests/first-launch-restore-results.test.py`: +13 fixtures covering
  happy path plus false positives (wrong API/class/method, dup members, extra TAR
  members, bad hashes, missing legs, mismatched nonce, fabricated expectations).
  Mocked/stdlib fixtures are offline-only — no emulator claim.
- `.github/workflows/nightly.yml`: the `bmgr` job only.

## Local gates run (all on this tree, JDK 21.0.12.1, ANDROID_HOME=/home/paseo/android-sdk, C.UTF-8, 37 GiB free before each heavy step)

- `:app:compileDebugAndroidTestKotlin` — pass.
- `:app:testDebugUnitTest` (DeviceTestSelectionTest 11, BmgrTransportExpectationTest
  10, FirstLaunchRestoreChecksTest, SnapshotNowReceiverTest, backup.*) — pass.
- `:app:assembleRelease :app:assembleReleaseAndroidTest -PtestBuildType=release` —
  pass; x86_64 APK 29,224,215 B, non-debuggable (aapt), signer `CN=Neutrodyne
  (public key)`, cert SHA-256 `3808fe37…451b`, R8 shrunk (minifyReleaseWithR8 ran).
- `spotlessApply` then `spotlessCheck`, `checkSpdxHeaders`, `checkBannedApis`,
  `assertModuleGraph`, `:app:verifyManifestPermissions` — all clean.
- `bash -n` + `shellcheck -S warning` on bmgr-check.sh — clean.
- `python3 scripts/ci/tests/first-launch-restore-results.test.py` — 23 tests pass.

Not run (owner gates): emulators/devices, CI, pushes. No schema/manifest/module/
dependency/locale changes needed; no doc validators required (no docs touched).

## Evidence

`build/review-m3-bmgr-restore/`: `base.txt`, `head.txt`, `full.patch`, `status.txt`,
`apk-sha256.txt`, `apk-listing.txt`, `test-results/` XMLs, `checker-tests.txt`,
`checker-help.txt`. The task's published-Artifact/provenance fact record lives in
`facts`/`run.json` produced by the script itself on real legs — none exist yet.

## Known limits / parent follow-ups

- No leg has run on a device; API 31/36 behavior (LocalTransport token layout,
  `bmgr list sets` shape, `stopped` semantics, restore timing) is per AOSP contract
  and bounded waits, but the parent must run the exact committed SHA and read the
  raw artifacts (`transport.tar`, `restored-files.txt`, instrument logs, logcat,
  dumpsys) before closing AC6.
- `files/` strict equality pre-`onCreate` assumes no platform/provider component
  writes there before `Application.onCreate` on the stock images; if a real leg
  shows a benign platform file, the failure names it — decide then, don't weaken
  now.
- `verify` accepts session state COMMITTED/FETCHING/DONE (a fetch may legitimately
  start post-restore); all other states fail.
- Empty leg keeps `bmgrExpected` out of status (checker asserts none emitted there).
- report-nightly label `bmgr` is new; parent resolves the shared input-scope
  description/registry when integrating with the sibling probe branch.
