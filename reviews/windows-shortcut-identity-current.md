# Windows shortcut/process identity — implementation report

Date: 2026-11-08
Branch: `impl/windows-shortcut-identity-current`
Base: `48eff13d07dfc680687bc8618bb2540f2be54b6c` ("Add the isolated desktop AOT training mode")
Scope: the Windows AppUserModelID half of `docs/design/11-desktop.md` (Start-up step 4, "Windows MSI and ZIP"). No packaging/AOT work.

## Root cause

`playback/native/ndmedia/session_win.cpp` hardcoded
`SetCurrentProcessExplicitAppUserModelID(L"ch.lkmc.neutrodyne.debug")` inside the
media-session worker. Packaged builds therefore always ran under the development
AppUserModelID, and the call — which runs when the session opens, not at shell
start-up — could also reset a correctly chosen identity. `DesktopShell` carried a
deferred comment instead of the step-4 call the design requires.

## What changed

- `session.h` / `session_win.cpp`: new exports `nd_win_process_set_aumid` and
  `nd_win_shortcut_set_aumid` (design names, Windows-only symbols). The process
  call validates the ID (≤128 UTF-16 units, no spaces), calls
  `SetCurrentProcessExplicitAppUserModelID`, and stores the value under an
  SRWLOCK; the SMTC worker now only re-asserts that stored value — the hardcoded
  debug ID is gone. The shortcut call owns the whole COM boundary
  (`CoInitializeEx` STA + `COINIT_DISABLE_OLE1DDE`, `CLSID_ShellLink` →
  `IShellLinkW` → `IPersistFile::Load` → `IPropertyStore` →
  `PKEY_AppUserModel_ID` → `Commit` → `IPersistFile::Save(NULL, TRUE)`), refuses
  anything but an absolute, existing `.lnk` path, and skips the write entirely
  when the shortcut already carries the ID (no timestamp churn, no repair
  prompt bait).
- `build.sh`: `propsys.lib` on the link line (`InitPropVariantFromString`,
  `PropVariantClear`); no other new dependency. New Windows-only fixture
  `tests/identity_test.c` runs in the existing windows-x64 leg — real COM
  objects against a throwaway `.lnk` in its own temp dir, plus the
  process-AUMID bounds and read-back. It was **not executed** on this Mac.
- `NdmediaLibrary`: `optionalDowncall` (null instead of throw on a missing
  export — "newexportoptional detection").
- `NativeWindowsIdentity` (new, `:playback:native`): FFM wrapper; `supported`
  is false when the exports are absent, so an older ndmedia degrades with an
  actionable warning instead of a broken call.
- `WindowsKnownFolders` (new, `:desktop:system`): `FOLDERID_Programs` through
  JNA `SHGetKnownFolderPath` — the official known-folder API, no guessed or
  localized `Start Menu` path (same seam shape as `AppDirs`'
  `WindowsLocalAppData`).
- `WindowsAppIdentity` (new, `:desktopApp/identity`): selects
  `ch.lkmc.neutrodyne` vs `ch.lkmc.neutrodyne.debug` from `BuildInfo.debug` and
  applies it; degrades to a logged outcome, never throws.
- `WindowsShortcutIdentity` + `WindowsShortcutIdentityInitializer` (order 60,
  band 0–99): writes `HKCU\Software\Classes\AppUserModelId\<id>`
  `DisplayName`=`Neutrodyne` and `IconUri`=`<launcher dir>\Neutrodyne.ico`
  (launcher-embedded icon fallback) on **every** packaged kind; stamps
  `<FOLDERID_Programs>\Neutrodyne\Neutrodyne.lnk` **MSI only**. ZIP fabricates
  nothing; a dev run skips entirely; the path is bounds-checked (absolute
  drive/UNC, no `..`) so no unrelated shortcut can be touched.
- `DesktopShell.runOwner`: calls `applyProcessIdentity` in step 4, before the
  graph (`createDesktopGraph`) and before `application {}` — i.e. before any
  window or OS surface can exist; the stale "remains in its desktop milestone"
  comment was corrected.
- `docs/design/11-desktop.md`: dated implementation note in "Windows MSI and
  ZIP".

## Primary Microsoft references used

- `SetCurrentProcessExplicitAppUserModelID` — must be called during process
  start-up before any UI or jump-list manipulation:
  https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nf-shobjidl_core-setcurrentprocessexplicitappusermodelid
- AppUserModelIDs — same explicit ID on process, windows and shortcuts;
  `System.AppUserModel.ID` on the shortcut; ≤128 chars, no spaces:
  https://learn.microsoft.com/en-us/windows/win32/shell/appids
- `System.AppUserModel.ID` / `PKEY_AppUserModel_ID`
  (`9F4C2855-9F79-4B39-8D0D-E1D42DE1D5F3`, pid 5):
  https://learn.microsoft.com/en-us/windows/win32/properties/props-system-appusermodel-id
- `SHGetKnownFolderPath` — `FOLDERID_Programs`, `hToken = NULL`, result freed
  via `CoTaskMemFree`:
  https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath
- `IPropertyStore` (`GetValue`/`SetValue`/`Commit`) and
  `IPersistFile::Save(NULL, TRUE)` semantics via the SDK headers; jpackage's
  `main.wxs` (OpenJDK jdk25u) for the per-user `ProgramMenuFolder\<menuGroup>\<launcher>.lnk` layout.

## RED → GREEN

- RED: `WindowsAppIdentityTest` (8 tests) and `WindowsShortcutIdentityTest`
  (11 tests) were authored first; they reference the then-missing
  `WindowsAppIdentity`/`WindowsShortcutIdentity` types, so the test compile was
  broken by construction until the implementation landed.
- GREEN: after the implementation, `:desktopApp:test` (identity + links + di
  filters), `:desktop:system:test`, `:playback:native:test` — BUILD SUCCESSFUL;
  test results show 8/8 and 11/11, 0 failures, 0 skipped.
  `DesktopAppGraphTest` (initializer band assertions) also passes with the new
  order-60 initializer.

## Checks actually run (macOS arm64 host)

- `gradle-local.sh :desktop:system:compileKotlin :playback:native:compileKotlin :desktopApp:compileKotlin :desktopApp:compileTestKotlin` — SUCCESS
- `gradle-local.sh :desktopApp:test --tests "ch.lkmc.neutrodyne.desktop.identity.*" --tests "ch.lkmc.neutrodyne.desktop.links.*" --tests "ch.lkmc.neutrodyne.desktop.di.*" :desktop:system:test :playback:native:test` — SUCCESS
- `gradle-local.sh spotlessCheck checkSpdxHeaders checkBannedApis` — SUCCESS
- `ndmedia/build.sh macos-arm64` — builds `libndmedia.dylib` (327,392 B);
  json-check + power-check fixtures pass.
- `python3 tools/doccheck/checkdocs.py .` — 0 problems across 14 files
  (note: the task named `docs/design/checkdocs0`; that script does not exist in
  this checkout — the repo's documented checkdocs entry point was run instead)
- `node /Users/lkm/nd-wt/md4/tools/doccheck/mmdcheck.mjs .` — 0 invalid of 78

## Windows proof gap (honest)

- No Windows VM, MSI install, registry or real `.lnk` write exists or ran here.
  `session_win.cpp` and `identity_test.c` are compiled only by the windows-x64
  CI leg; nothing in this report claims local Windows validation.
- Unresolved installer proof: the shortcut path
  `FOLDERID_Programs\Neutrodyne\Neutrodyne.lnk` is derived from the packaging
  config (`menuGroup = "Neutrodyne"`, launcher `Neutrodyne`, per-user
  `ProgramMenuFolder` in jpackage's `main.wxs`). Whether a real MSI produces
  exactly that file — and whether rewriting it makes Windows Installer offer a
  repair (the design's own open question) — needs the real-install check the
  design already schedules.
- Manual-test gap: process attribution in the media flyout/toasts, the registry
  display metadata, and the second-launch identity path are all unverified on
  real Windows; root owns that CI/install proof.
