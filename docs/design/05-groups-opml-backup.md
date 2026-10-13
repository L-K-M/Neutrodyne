# 05 — Groups, OPML and backup

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the owner decisions (YouTube engine and external mode, GitHub-only distribution); revised 2026-10-05 for PO-31–PO-35 (the update check replaces the in-app updater in the settings whitelist and the exclusions; Auto Backup restore follows the committed public key; shell-only `SnapshotNowReceiver` for the `bmgr` job); scope revision 2026-10-05 (S0–S13): groups, group feeds, effective settings, play contexts, OPML, the import pipeline and backup become common Kotlin Multiplatform code for Android and the desktop (OPML reader and ZIP container in the `:feeds:jvm` island; `GroupNames` and the backup models in common `:feeds`, shared with the sync server), group and member order move to `orderKey`, groups and memberships become sync records and override fields are classified synced or device-local, backups gain optional `syncId`/`orderKey` fields and restore across platforms, `RestoreMerger` becomes the one merge routine of restores and of the first sync link, a restore on a linked device asks how far it reaches, the desktop imports and exports through file dialogs, drag and drop and the `.opml` association and runs imports and restores in its `import-backup` lane, Auto Backup stays Android-only, and published APKs are release builds; **final cross-document review 2026-10-05:** undo kept by the outbox hold-back on both platforms, fresh presence clocks for records a linked Merge restore re-creates, a crash-safe order for Replace on all synced devices, `GroupChannelSync` moved to `:core:domain`, PO-46 for the desktop recovery snapshot · Implements: R1.1–R1.9, R2.1–R2.9, R3.4, R3.7 (play-context and auto-download side), R4.4–R4.5 (policy resolution), R7.3 (groups, memberships, override and setting classification), R7.6 (merge rules), R7.9, R8.1 and R8.11 (shared library code, cross-platform backup), R8.3 and R8.9 (file inputs) / N1, N3, N5, N9 · Milestones: M1 (M1a), M2, M3, M4, M6, M8, M9 (M9a, M9b), M11 (M11a, M11b), MD2, MS2, M15 · Honours: D13, D16, D20, D24, D29, D30, D31, D32, D33, D34, D35, D36, D44, D45, D61, D66, D67, D70, D77, D78, D79, D81, D83, D84, D85, D91, D92, D93, D96; PO-11, PO-15, PO-25, PO-37 defaults; PO-31, PO-32, PO-33 (resolved), PO-35 (re-resolved 2026-10-05) · Owns: group semantics and lifecycle, group feeds and counts, effective-settings resolution and the synced/device-local classification of override fields, play-context membership, OPML export and import, the import pipeline for every file format on both platforms, backup and restore, `RestoreMerger` and its merge rules (restores and the first sync link), restore while linked, Auto Backup (Android) and file intents, the work of the desktop `import-backup` lane

Contents: [Scope](#scope) · [Group model and lifecycle](#group-model-and-lifecycle) · [Group feeds](#group-feeds) · [Effective settings resolution](#effective-settings-resolution) · [Playing a group](#playing-a-group) · [OPML export](#opml-export) · [OPML import](#opml-import) · [Other import formats](#other-import-formats) · [Full backup and restore](#full-backup-and-restore) ([Restore while linked](#restore-while-linked)) · [Auto Backup](#auto-backup) · [Receiving files](#receiving-files) · [Settings](#settings) · [Testing](#testing) · [Error handling and failure modes](#error-handling-and-failure-modes) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

Serves R1, R2, R3.4, R7.3, R7.6, R7.9, R8.1, R8.3, R8.11, N1, N9. Delivered in M1 (All and Podcast feeds), M2 (groups, `orderKey`), M3 (files and backup on both platforms), M4 (play contexts, playback settings), M6 (auto-download resolution), M8 (YouTube formats), M9a (YouTube rules that depend on the engine), M11a (the update-check setting in backups), MD2 (desktop file association and hand-off, 11), MS2 (`RestoreMerger`'s link policies, restore while linked, "Reconnect"); see [Delivery by milestone](#delivery-by-milestone).

A group is a user-defined, many-to-many set of podcasts and YouTube channels with its own episode feed and defaults ([D29](../PLAN.md#3-key-decisions)); it is the product's differentiator. This document also owns every way subscriptions enter or leave the app as a file: OPML, the YouTube interchange formats (parsers specified by 04), the backup archive and the Auto Backup snapshot.

Since the scope revision ([D81](../PLAN.md#3-key-decisions)) all of it is common Kotlin Multiplatform code that the Android app and the desktop app share — groups, feeds, resolution, play contexts, OPML, the import pipeline, backup writing and restore — except Auto Backup and Android's file intents (Android only) and the small platform hosts listed in [Modules and public API](#modules-and-public-api). Groups, memberships, group settings and the synced override fields are sync records ([10 What syncs](10-sync.md#what-syncs)); a backup restore and a device's first sync link run the same merge routine and rules table ([RestoreMerger](#restoremerger)). Sync itself — protocol, capture, apply, linking — is 10's.

| Owned here | Not here (link instead) |
|---|---|
| Group semantics: name rules, palette, icon keys, order, membership edits, delete with undo, notification-channel lifecycle (Android) | Tables, columns, indices and every SQL statement — [02 podcast_group](02-data-model.md#podcast_group), [02 Key queries](02-data-model.md#key-queries); group and member sync fields — [10 Group and member fields](10-sync.md#group-and-member-fields) |
| `FeedSource`/`FeedFilters`/`FeedOrder` behaviour, the `FeedRepository` contract, counts window, "new since last visit", `includeInAll` | Tabs, pager and screen visuals — [08 Group feed pager](08-ui-ux.md#group-feed-pager), [08 Screens](08-ui-ux.md#screens); row overlay — [08 Live row state](08-ui-ux.md#live-row-state) |
| `EffectiveSettingsResolver` rules and attribution; `ScopeSettingsRepository`; which override fields sync | Capture and apply of synced overrides — [10 Per-scope overrides](10-sync.md#per-scope-overrides); applying values to the player — [06 Per-scope playback settings](06-playback.md#per-scope-playback-settings); planner — [07 Auto-download policy](07-downloads.md#auto-download-policy); posting notifications — [03 New-episode notifications](03-feeds-and-discovery.md#new-episode-notifications) |
| Which episodes a play context contains and where "Play" starts (`PlayContextResolver`) | Projection, transitions, positions — [06 Queue and play context](06-playback.md#queue-and-play-context) |
| OPML writer and reader; the import pipeline (acquire, sniff, parse, classify, preview, commit, fetch, report) for every format, on Android and the desktop | Desktop OS registration of `.opml`, the drop target and `DesktopOpenHandler` — [11 Links and files from the OS](11-desktop.md#links-and-files-from-the-os); refresh engine — [03 Refresh scheduling](03-feeds-and-discovery.md#refresh-scheduling); YouTube classification and NewPipe/LibreTube/Takeout/URL-list specs — [04 Import and export formats](04-youtube.md#import-and-export-formats) |
| Backup archive format, writer, validation, Merge/Replace restore; `RestoreMerger` and its rules for restores and the first sync link; restore while linked | Link flows, first-link choices and the mapping of server records — [10 Linking and first merge](10-sync.md#linking-and-first-merge); identity-key computation — [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff); key storage and versions — [02 Identity keys](02-data-model.md#identity-keys) |
| (Android) Auto Backup rules XML, `AutoSnapshotWorker`, first-launch restore | Fresh-install detection — [02 Error handling and recovery](02-data-model.md#error-handling-and-recovery); start-up order — [01 Application start-up](01-foundation.md#application-start-up) |
| (Android) `ExternalImportActivity` filters, copy-on-receipt, `FileProvider` path `cache/export/` | `MainActivity` subscribe filters — [03 Deep links and share targets](03-feeds-and-discovery.md#deep-links-and-share-targets); merged manifest — [01 Manifest and permissions](01-foundation.md#manifest-and-permissions) |
| The portable-settings whitelist used by backups; the sync classification of this document's keys | `SettingKey` registry and DataStore files — [01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys); which portable keys sync — [10 Settings](10-sync.md#settings) |

### Modules and public API

| Module | Contents from this document |
|---|---|
| `:core:model` (common) | `Group`, `GroupDraft`, `GroupEdit`, `GroupPalette`, `GroupIcons`, `FeedTab`, `FeedCounts`, `VirtualCounts`, `FeedPrefs`, `DownloadAllEstimate`, `PlayContextSpec`, `SettingOverrides`, `SettingSource`, `Effective`, `EffectivePlayback`, `EffectiveAutoDownload`, `ImportOptions`, `SourceGroup`, `ImportSessionView`, `ImportItemView`, `GroupProposal`, `ExportRequest`, `PreparedExport`, `ExportLayout`, `BackupPreview`, `RestoreRequest`, `LinkedRestoreReach`, `RestoreProgress`, `SnapshotStatus`, `PrivacyCounts`, `MergePolicy`, `MergeSummary` |
| `:core:domain` (common) | Interfaces `FeedRepository`, `GroupRepository`, `ScopeSettingsRepository`, `EffectiveSettingsResolver`, `PlayContextResolver`, `ImportRepository`, `ExportRepository`, `BackupRepository`, `LibraryMerger` (the port through which 10's `FirstLinkMerger` reaches `RestoreMerger`), `GroupChannelSync` (the channel port 10's `SyncApplier` calls; moved here 2026-10-05 so `:sync:impl` never depends on `:core:data`); errors `GroupError`, `ImportError`, `BackupError`, `ExportError` |
| `:feeds` (common only; its JVM variant also runs in the sync server) | `GroupNames` (moved from `:core:model`), the OPML model, `OpmlWriter` and the `OpmlReader` interface, `ImportSourceSniffer`, `ImportDocuments`, `BackupCodec`, the backup DTOs, the archive interfaces `ArchiveReaderFactory`, `ArchiveReader`, `ArchiveWriterFactory`, `ArchiveWriter`, `ArchiveCaps`, `ArchiveException` (packages `ch.lkmc.neutrodyne.feeds.groups`, `.opml`, `.importing`, `.backup`); 04's YouTube parsers sit beside them (`.youtube`) |
| `:feeds:jvm` (JVM island, [D81](../PLAN.md#3-key-decisions)) | `XmlPullOpmlReader` with `MarkupGapGuard` and the salvage scanner, `ZipGuard` (the `ArchiveReaderFactory`), `ZipArchiveWriter` (the `ArchiveWriterFactory`) (packages `ch.lkmc.neutrodyne.feeds.jvm.opml`, `.archive`); bound in `:core:data`'s `androidMain` and `desktopMain` |
| `:core:data` `commonMain` | All repository implementations; `ImportClassifier`, `PayloadStore`, `OpmlExporter`, `BackupWriter`, `RestoreMerger`, `StagedLibrary`, `LibraryMergerImpl`, `RestoreRunner`, `ImportFetchRunner`, `GroupMerger`, `ExportFilesCleaner`; platform ports `PayloadSource`, `ExportDestination`, `ImportWorkScheduler`, `SnapshotScheduler`, `GroupChannelSync` |
| `:core:data` `androidMain` | `ImportFetchWorker`, `RestoreWorker`, `AutoSnapshotWorker`, `FirstLaunchRestoreInitializer`, `GroupNotificationChannels` (`GroupChannelSync`), `ContentPayloadSource`, `SafExportDestination`, `WorkManagerImportWorkScheduler`, `WorkManagerSnapshotScheduler`, `ExportShareUris` (`FileProvider` URIs) |
| `:core:data` `desktopMain` | `DesktopImportBackupLane` (lane `import-backup`), `FilePayloadSource`, `FileExportDestination`, `LaneImportWorkScheduler`, `NoSnapshotScheduler`, `NoGroupChannels` |
| `:feature:feeds`, `:feature:groups`, `:feature:importexport`, `:feature:podcast` (common) | ViewModels and screens for `FeedsKey`, `GroupEditKey`, `GroupsManageKey`, `GroupSettingsKey`, `AddToGroupsKey`, `AllGroupsKey`, `ImportKey`, `BackupKey`, `ExportKey`, `PodcastSettingsKey` (visuals: 08); file pickers, save dialogs, the share sheet and "Show in folder" through `:core:ui`'s `PlatformActions` (`FilePicker`, `FileSaver`, `ShareSheet`, `RevealInFolder`; implementations 08) |
| `:app` (Android only) | `ExternalImportActivity`, its manifest entries, the shell-only `SnapshotNowReceiver` and its manifest entry ([Testing with bmgr](#testing-with-bmgr)), `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `res/xml-v28/backup_rules.xml`, the `cache/export/` entry of `res/xml/file_paths.xml` |
| `:desktopApp` | nothing of its own: OS hand-offs and drops reach the import pipeline through 11's `DesktopOpenHandler` and the shared `IntentRouter` ([Desktop inputs](#desktop-inputs)) |

```mermaid
flowchart LR
  subgraph FEAT["Feature modules (common)"]
    FF[":feature:feeds"]
    FG[":feature:groups"]
    FIE[":feature:importexport"]
  end
  subgraph DOM[":core:domain interfaces"]
    FR["FeedRepository"]
    GR["GroupRepository"]
    SSR["ScopeSettingsRepository"]
    ESR["EffectiveSettingsResolver"]
    PCR["PlayContextResolver"]
    IR["ImportRepository"]
    ER["ExportRepository"]
    BR["BackupRepository"]
    LM["LibraryMerger"]
  end
  subgraph DATA[":core:data commonMain"]
    IMPL["Impl classes, ImportClassifier, OpmlExporter, BackupWriter"]
    RUN["RestoreMerger, RestoreRunner, ImportFetchRunner"]
  end
  subgraph ANDR[":core:data androidMain"]
    IFW["ImportFetchWorker, RestoreWorker"]
    ASW["AutoSnapshotWorker"]
  end
  subgraph DESK[":core:data desktopMain"]
    LANE["DesktopImportBackupLane"]
  end
  subgraph FEEDS[":feeds (common)"]
    OPML["GroupNames, OpmlWriter, OpmlReader interface"]
    SN["ImportSourceSniffer, archive interfaces"]
    BC["BackupCodec and DTOs"]
    YP["YouTube format parsers (04)"]
  end
  subgraph ISL[":feeds:jvm (JVM island)"]
    XOR["XmlPullOpmlReader"]
    ZG["ZipGuard, ZipArchiveWriter"]
  end
  EIA["ExternalImportActivity (Android)"] --> IR
  DOH["DesktopOpenHandler and drops (11)"] --> FIE
  FEAT --> DOM
  DOM -.->|"Metro binds"| IMPL
  LM -.-> RUN
  IMPL --> FEEDS
  ANDR -->|"binds"| ISL
  DESK -->|"binds"| ISL
  IFW --> RUN
  LANE --> RUN
  RUN --> FRF["FeedRefresher (03)"]
  FLM["FirstLinkMerger (10)"] --> LM
  ESR --> CONS["playback core (06, 11), 07 planner, 03 scheduler and notifier"]
  PCR --> P06["PlayStarter (06)"]
```

### Threading and coroutines

All repository functions are main-safe (`suspend` or `Flow`), follow [01 Coroutines and threading](01-foundation.md#coroutines-and-threading) and never call `runCatching` in suspend code. The rules hold on both platforms; "main" is the Android main thread or the desktop's Swing EDT.

| Work | Context | Rule |
|---|---|---|
| DAO calls | Room query context (`@Dispatcher(IO)`) | Batch sizes and transaction rules of [02 Conventions](02-data-model.md#conventions): import commit 500 items, restore 1,000 lines per transaction |
| Copying payloads, OPML/JSON/CSV parsing, ZIP reading and writing | `@Dispatcher(IO)` (blocking streams) | Caps enforced while streaming; no `ContentResolver` or other platform file call inside a transaction |
| Group proposals of a 10,000-item preview, OPML document building | `@Dispatcher(Default)` | Debounced 100 ms on item changes |
| Manual OPML and backup export | `@ApplicationScope` | Leaving the dialog does not cancel; result reported as a `UserMessage` |
| Delete-group undo window | `@ApplicationScope`, `delay(10_000)` | Process death within the window makes the delete final |
| `ImportFetchWorker`, `RestoreWorker`, `AutoSnapshotWorker` (Android) | `CoroutineWorker` created by `MetroWorkerFactory` ([01 Dependency injection](01-foundation.md#dependency-injection)) | Idempotent, resumable, 8-min soft deadline ([N2](../PLAN.md#22-non-functional-requirements)); the first two host the common `ImportFetchRunner` and `RestoreRunner` |
| `import-backup` lane (desktop) | `DesktopJobRunner` lane on `Dispatchers.IO` ([11 Background work](11-desktop.md#background-work)) | The same runners without a soft deadline; a quit stops the lane and the next start resumes from the persisted session state |
| `ExternalImportActivity` copy (Android) | retained `ViewModel` scope | Survives rotation; must finish before the activity finishes (grant lifetime) |
| Backup and snapshot writing | one process-wide `Mutex` in `BackupWriter` | A manual backup waits for a running snapshot (Android) and vice versa |
| Credential put→reference sequences | `CredentialCommitCoordinator` (`:core:domain` port owned by 03, `:core:data` impl, its own process-wide `Mutex`, not reentrant; from M1b) | Subscribe, `setCredentials`, import commit, restore and 10's sync password installs (pulled `auth` and first-link password effects) hold it from `SecretStore.put` until the referencing write commits; the credential deletes hold it too — `db-maintenance`'s orphan sweep and the last-reference delete in unsubscribe's and merge's cascade (02, 03); acquired before entering any Room transaction, never while holding the writer, and never by a holder again |
| Desktop file dialogs and drops | `PlatformActions` on the Swing EDT (08) | The ViewModel awaits the chosen path; copying, sniffing and parsing run on IO as on Android |
| `SnapshotNowReceiver` (Android, shell-only `bmgr` trigger) | `goAsync()`, `writeSnapshotNow()` on `@ApplicationScope` | Waits at most 8 s for the result; the write itself is never cancelled ([Testing with bmgr](#testing-with-bmgr)) |

### Platform constraints

Android rows first, then the shared-code and desktop rows.

| Constraint | Consequence here | Source |
|---|---|---|
| URI grants of VIEW/SEND intents "remain in effect while the stack of the receiving Activity is active" | Copy the payload before `finish()`; workers never read the original URI | [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) |
| `ContentResolver.openOutputStream(uri)` uses mode `"w"`, which "may or may not truncate" | Every SAF write uses `"wt"` | [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) |
| Neither Android MIME table maps `.opml`; file-system providers keep the requested display name only when the MIME type matches or has no mapping | `CreateDocument("text/x-opml")` (with `text/xml` the file becomes `x.opml.xml`) | [mime.types](https://android.googlesource.com/platform/external/mime-support/+/refs/heads/main/mime.types), [android.mime.types](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/mime/java-res/android.mime.types), [FileUtils](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/FileUtils.java) |
| `<data>` path attributes need scheme and host; `pathSuffix` exists from API 31; MIME and scheme matching is case-sensitive | `pathSuffix=".opml"` only on an alias enabled on API 31+ | [data element](https://developer.android.com/guide/topics/manifest/data-element) |
| Android 16 Safer Intents is opt-in and planned to become default | Our filters are fully specified; adoption is 01's decision (P27) | [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-16) |
| Auto Backup: 25 MB per app, over quota "doesn't back up data to the cloud"; `<include>` disables the defaults; restore happens at install; a `BackupAgent` runs in restricted mode | Include-only rules for a small snapshot; no custom `BackupAgent` | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| `disableIfNoEncryptionCapabilities` (Android 12+ rules); `requireFlags="clientSideEncryption"` (Android 9+) "prevents backups from working" on 8.1 and lower; a missing `<cloud-backup>`/`<device-transfer>` section means that mode is "fully enabled for all content"; `<cross-platform-transfer platform="ios">` (Android 16 QPR2) requires `<platform-specific-params bundleId teamId contentVersion>` and transfers data only to that iOS app | PO-15 rules per API level; both Android sections always present; no cross-platform section, because there is no iOS app to name ([Auto Backup](#auto-backup), 01 P28) | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| Auto Backup "excludes files in directories returned by `getCacheDir()`, `getCodeCacheDir()`, and `getNoBackupFilesDir()`"; data "is restored whenever the app is installed, whether from the Play Store, during device setup …, or by running `adb` install", before the app can be launched | The YouTube engine's versions (`noBackupFilesDir/ytdlp/`), the update check's cache (`noBackupFilesDir/updates/last-check.json`) and yt-dlp's cache never travel, independent of our include-only rules; a user who installs the GitHub APK on a new device gets the restore at install like any other install ([Auto Backup](#auto-backup)) | [Auto Backup](https://developer.android.com/identity/data/autobackup) (checked 2026-10-05) |
| The restore mechanism "checks the signature block of the package which uploaded the restore data against the signature of the package on-device"; permission restore accepts a certificate found in the other side's signing history (rotation) | Auto Backup data reaches only an app signed with the same certificate. Every Neutrodyne build is signed with the public keystore committed to the repository ([D61](../PLAN.md#3-key-decisions)), so every published APK receives the data of every other, and so does any APK that someone else signs with that key under `ch.lkmc.neutrodyne`, a spoofed one included ([PLAN P10](../PLAN.md#8-risks-and-mitigations)): install only from the GitHub release page. Local debug builds (`ch.lkmc.neutrodyne.debug`) are another package and never receive it. A later move to a private release key ends Auto Backup restore across the switch ([09 Public key trade-offs](09-quality-and-release.md#public-key-trade-offs)); the manual backup ZIP, which is signer-independent, is the path. Unverified, and not planned (D61): whether data restore (not only permissions) would honour a v3 rotation lineage | [AOSP commit 78dd4a7](https://android.googlesource.com/platform/frameworks/base/+/78dd4a7%5E%21/), [CTS commit 8282ae5](https://android.googlesource.com/platform/cts/+/8282ae54186%5E%21/) |
| Published APKs are non-debuggable release builds ([D2](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions)): `run-as` refuses non-debuggable packages, and on Android 12+ `adb backup` excludes the data of apps targeting 31+ unless they set `android:debuggable="true"` | ADB cannot copy the published app's data directory; only Auto Backup's include-only set leaves the device (the earlier debuggable-build exposure, risk P11, is retired 2026-10-05). The local `debug` build is debuggable, but it is a separate package holding a developer's test data | [AOSP run-as](https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp), [Android 12 behaviour changes](https://developer.android.com/about/versions/12/behavior-changes-12) (both checked 2026-10-05) |
| Apps targeting 34+: the `ZipFile` constructor and `ZipInputStream.getNextEntry()` throw `ZipException` for entry names containing `..` or starting with `/` (not on the JVM) | `ZipGuard` maps that exception to `Hostile("zip_path")` and also rejects such names itself, so JVM tests and devices agree | [Android 14 behaviour changes](https://developer.android.com/about/versions/14/behavior-changes-14#zip-path-traversal) |
| The local backup transport must be marked encrypted (`backup_local_transport_parameters 'is_encrypted=true'`) | Test procedure | [Test backup and restore](https://developer.android.com/identity/data/testingbackup) |
| Expedited work before API 31 runs as a foreground service and needs `getForegroundInfo()`; Android 16 applies job quotas to work running beside an FGS | `import-{sessionId}` is expedited only on API 31+; every worker is resumable | [Define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [Android 16 behaviour changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all) |
| `dataSync` FGS covers "Import or export operations" but has the 6 h/24 h cap | Not used for imports or backups | [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types) |
| Deleting a notification channel and recreating the same ID "un-deletes" it with its old settings; `createNotificationChannel` renames an existing channel | Channel IDs use the never-reused group `uuid` | [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager) |
| `NotificationChannel`: importance, sound, lights, vibration and badge are "only modifiable before the channel is submitted"; lockscreen visibility and (without Do Not Disturb access) "bypass DND" are "only modifiable by the system and notification ranker" | A channel re-created for a group whose `uuid` a sync merge changed copies the first five from the old channel and loses the rest ([Notification channels](#notification-channels)) | [NotificationChannel source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/NotificationChannel.java) (checked 2026-10-05) |
| `XmlPullParser`, `java.util.zip` and `java.text.Collator` are JVM APIs, banned in `commonMain` ([D81](../PLAN.md#3-key-decisions), 01's `checkBannedApis`) | The OPML reader and the ZIP container live in the `:feeds:jvm` island behind `:feeds` interfaces; NFC goes through `:feeds`' `expect` helper; the OPML export sorts with a common comparator ([Structure](#structure)) | [01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands) |
| Desktop: AWT `FileDialog` — "Filename filters do not function in Sun's reference implementation for Microsoft Windows" | Open dialogs filter nothing on any OS; the content is sniffed, as on Android ([Receiving files](#receiving-files)) | [FileDialog](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/FileDialog.html) (checked 2026-10-05) |
| Desktop (Linux): the XDG portal's `FileChooser.SaveFile` answers with exactly one `file://` URI | Desktop saves receive a path and write it themselves with an atomic replace ([Destinations](#destinations)) | [portal FileChooser](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html) (checked 2026-10-05) |
| Desktop: Room has no multi-instance invalidation off Android, so the desktop app is one process ([D85](../PLAN.md#3-key-decisions)) | Imports and restores run in that process's `import-backup` lane; a second launch hands its files to the running instance (11) | [Room KMP](https://developer.android.com/kotlin/multiplatform/room) |

---

## Group model and lifecycle

Serves R2.1, R2.2, R5.6, R7.3. Delivered in [M2](../PLAN.md#m2-groups-and-group-feeds) on both platforms. Honours [D29](../PLAN.md#3-key-decisions), [D36](../PLAN.md#3-key-decisions), [D92](../PLAN.md#3-key-decisions). Storage: [02 podcast_group](02-data-model.md#podcast_group), [02 podcast_group_member](02-data-model.md#podcast_group_member).

```kotlin
// :core:model — canonical type Group; shape owned here
data class Group(
    val id: Long, val uuid: String, val name: String, val orderKey: String,
    val colorArgb: Int?, val iconKey: String?,
    val feedOrder: FeedOrder, val playOrder: FeedOrder,
    val filterFlags: Int, val mediaFilter: MediaFilter, val hideOlderThanDays: Int?,
    val showAsTab: Boolean, val lastViewedAt: Long?, val createdAt: Long, val memberCount: Int,
)
data class GroupDraft(val name: String, val colorArgb: Int? = null, val iconKey: String? = null,
                      val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST)  // colour null → next free palette colour
sealed interface GroupEdit {
    data class Rename(val name: String) : GroupEdit
    data class Look(val colorArgb: Int?, val iconKey: String?) : GroupEdit
    data class Orders(val feedOrder: FeedOrder, val playOrder: FeedOrder) : GroupEdit
    data class View(val filterFlags: Int, val mediaFilter: MediaFilter, val hideOlderThanDays: Int?) : GroupEdit
    data class ShowAsTab(val show: Boolean) : GroupEdit
}
```

`kind` is always `MANUAL` and `ruleJson` always `null` in v1 (smart groups reserved, [02 Reserved tables](02-data-model.md#reserved-tables)). `uuid` = `Uuid.random().toString()` (`kotlin.uuid`, lowercase, common code), generated once and never reused ([02 Group uuid and nameKey](02-data-model.md#group-uuid-and-namekey)); notification channels (Android), the mosaic artwork key `g-{groupUuid}`, backups and sync depend on it. It is the group's sync record ID and changes on a device only when a sync merge of two equally named groups redirects it ([10 Redirects on clients](10-sync.md#redirects-on-clients)), when a first sync link or a Replace restore matches the group by `nameKey` and adopts the other side's `uuid` ([Merge and Replace rules](#merge-and-replace-rules)); `GroupChannelSync.onUuidChanged` then carries the notification channel over ([Notification channels](#notification-channels)).

**Sync** ([D29](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions)). Groups and memberships are sync records ([10 Group and member fields](10-sync.md#group-and-member-fields)): name, colour, icon, `orderKey`, both orders, view filters, `hideOlderThanDays`, `showAsTab`, `createdAt`, the synced overrides ([Synced and device-local overrides](#synced-and-device-local-overrides)) and each membership with its `orderKey` travel; `lastViewedAt`, `kind`/`ruleJson`, the notification channel, `podcast_group_member.source` and the device-local overrides stay on the device. This document's writes need no sync code: 02's capture triggers record them while a server is linked ([02 Sync capture triggers](02-data-model.md#sync-capture-triggers)), and 10's `SyncApplier` writes received changes into the same tables with capture suspended, so every flow below re-emits as for a local edit. Received names are re-validated with `GroupNames` (too long → truncated, empty → "Group"), and equal `nameKey`s merge ([Names](#names)).

### Names

`GroupNames` (`:feeds`, package `ch.lkmc.neutrodyne.feeds.groups`, pure common code; moved from `:core:model` by the scope revision so that the sync server computes the same `nameKey` as the apps, [D94](../PLAN.md#3-key-decisions), [10 Groups with equal names](10-sync.md#groups-with-equal-names)) is the single implementation, used by the editor, import, restore, LibreTube group mapping, sync apply and the server.

1. Normalise to NFC (`:feeds`' `expect` NFC helper, `java.text.Normalizer` on Android, the desktop and the server alike, [03 Package layout](03-feeds-and-discovery.md#package-layout)).
2. Replace tabs, line and paragraph separators and every other `Cc` character with U+0020; delete the bidi controls U+202A–U+202E and U+2066–U+2069. Other format characters stay (ZWJ U+200D and variation selector U+FE0F build emoji).
3. Collapse runs of whitespace (`Char.isWhitespace` plus U+00A0, U+2007, U+202F) to one U+0020 and trim.
4. Length = code points, counted in common code (a surrogate pair counts once; `String.codePointCount` is JVM-only): 0 → `GroupError.NameEmpty`; > 40 → `GroupError.NameTooLong(40)` (code points, not graphemes, so every platform and Android version agrees).
5. `nameKey = NFC(normalized.lowercase())` — Kotlin's `lowercase()` uses the invariant locale, the same as `toLowerCase(Locale.ROOT)` on the JVM ([lowercase](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html)); a `nameKey` held by another group → `GroupError.NameTaken(groupId)`. The final NFC pass only matters where lowercasing decomposes (`İ` → `i̇`).

Examples (M2 acceptance 5): "Tech" is rejected when "tech" exists; a 41-code-point name is rejected; "🎧 Commute" is accepted; "Café" typed as `e + U+0301` collides with precomposed "Café". `GroupNames.suggestsOldestFirst(name)` is true when `nameKey` contains `fiction`, `audiobook`, `audio book`, `drama`, `serial`, `story`, `stories`, `novel`, `hörbuch` or `hörspiel`; the editor then offers "Play oldest first" ([PO-11](../PLAN.md#48-further-product-owner-decisions)), it never switches silently. Two devices that create "News" and "news" offline converge on one group: the server keeps the older one (`createdAt`, then `uuid`) and unites the memberships, as a restore does ([10 Groups with equal names](10-sync.md#groups-with-equal-names)); 08 names the merged groups in a notice.

### Palette

`colorArgb` stores a seed colour; 08 renders it through fixed `ArtColors` tones per theme (CIE L\*, equal to HCT tone), never raw ([08 ArtColors tones for monograms and groups](08-ui-ux.md#artcolors-tones-for-monograms-and-groups)). `GroupPalette.COLORS` (order and values are stable forever):

| # | Key (content description) | ARGB | # | Key | ARGB |
|---|---|---|---|---|---|
| 0 | `red` | `0xFFF44336` | 6 | `blue` | `0xFF2196F3` |
| 1 | `deep_orange` | `0xFFFF5722` | 7 | `indigo` | `0xFF3F51B5` |
| 2 | `amber` | `0xFFFFC107` | 8 | `deep_purple` | `0xFF673AB7` |
| 3 | `green` | `0xFF4CAF50` | 9 | `pink` | `0xFFE91E63` |
| 4 | `teal` | `0xFF009688` | 10 | `brown` | `0xFF795548` |
| 5 | `cyan` | `0xFF00BCD4` | 11 | `blue_grey` | `0xFF607D8B` |

- A new group (editor, import, suggested groups) without a colour gets the first palette entry no existing group uses, in palette order; if all 12 are used, `COLORS[groupCount % 12]`.
- Colours from OPML (`nd:groupColor`) or backups that are not in the palette are kept (alpha forced to `0xFF`); the editor shows them as a "Custom" swatch. `null` (legacy) renders with the theme's primary colour.

### Icons

`iconKey` is a stable string (a Material Symbols name rendered by `:core:designsystem`), never a resource ID. Unknown keys from newer versions are stored and exported unchanged and render without an icon. Valid format `^[a-z0-9_]{1,40}$`; `null` = no icon. `GroupIcons.KEYS` (v1, 32 keys, new keys may be appended):

| Theme | Keys |
|---|---|
| News and society | `newspaper`, `public`, `account_balance`, `gavel` |
| Tech and science | `memory`, `computer`, `science`, `rocket_launch`, `psychology` |
| Stories and learning | `auto_stories`, `menu_book`, `history_edu`, `school`, `theater_comedy` |
| Culture and play | `music_note`, `movie`, `palette`, `sports_esports`, `sports_soccer` |
| Daily life | `fitness_center`, `restaurant`, `travel_explore`, `child_care`, `self_improvement`, `bedtime`, `directions_car`, `work` |
| General | `podcasts`, `smart_display`, `favorite`, `star` |

### Order

`orderKey` (a fractional index from `:sync:protocol`'s `OrderKey`, [D92](../PLAN.md#3-key-decisions)) defines tab order, the Library groups view, OPML folder order and the "primary group" of hybrid OPML; every list sorts by `orderKey, id` ([02 Group and member ordering](02-data-model.md#group-and-member-ordering)). Create assigns `OrderKey.after(last)`. `reorder(ids)` (drag reorder with `reorderable`, 08; "Move up/down" custom actions and desktop context menus) compares the requested order with the current one, keeps the longest run of groups that are already in relative order (a longest increasing subsequence of their current positions) and writes `OrderKey.between(previous, next)` only for the others, in one write transaction: one drag or one move writes one row (M2 acceptance 14, 02's `GroupOrderTest`), and two devices that move different groups both keep their moves after sync ([10 Ordered lists](10-sync.md#ordered-lists)). A key longer than 64 characters, or equal neighbour keys after concurrent inserts on two devices, makes 02 rewrite the list. Delete removes the row; nothing is renumbered. Backups still write `sortOrder` as each group's rank for older readers ([Archive](#archive)). Tested to 50 groups ([N5](../PLAN.md#22-non-functional-requirements)); there is no cap.

### Membership

Many-to-many ([R2.2](../PLAN.md#21-functional-requirements)); rows are `source = MANUAL`, `addedAt = now`, `orderKey = OrderKey.after(last)` within the group (02). Manual order inside a group is not exposed in v1 — group grids sort by title — but the key exists and syncs, so a later member-order feature needs no migration.

| Entry point | Navigation key | Call |
|---|---|---|
| Podcast screen group chips | `AddToGroupsKey(listOf(podcastId))` | `applyMembership(podcastIds, add, remove)` |
| Group editor cover-grid picker | `GroupEditKey(groupId)` (`null` = new) | `create(draft, memberIds)` / `setMembers(groupId, memberIds)` |
| Library multi-select "Add to group…" | `AddToGroupsKey(ids)` | `applyMembership` with tri-state chips: checked for all → `add`, cleared → `remove`, indeterminate untouched |
| Subscribe and import | — | 03's `SubscribeUseCase(groupIds)`; [Commit](#6-commit) |

Every membership write is one write transaction (`INSERT OR IGNORE` / `DELETE … WHERE groupId = ? AND podcastId IN (…)`), then: `RefreshController.reschedulePeriodic()` (the minimum refresh interval over groups may change, [03 Periodic tick](03-feeds-and-discovery.md#periodic-tick); on the desktop it re-reads the refresh lane's due times) and `SnapshotScheduler.requestSoon()` (Android; a no-op on the desktop, [Auto Backup](#auto-backup)). Auto-download and notification policies follow automatically through the resolver flows ([Effective settings resolution](#effective-settings-resolution)).

```kotlin
// :core:domain
interface GroupRepository {
    fun observeGroups(): Flow<List<Group>>                                   // ordered by orderKey, id
    fun observeGroup(groupId: Long): Flow<Group?>
    fun observeGroupsOf(podcastId: Long): Flow<List<Group>>
    fun observeMemberIds(groupId: Long): Flow<Set<Long>>
    fun observeMemberships(): Flow<Map<Long, Set<Long>>>                      // podcastId → groupIds; podcasts in no group absent
    fun observeMosaics(): Flow<Map<Long, List<ArtworkRef>>>                   // 02 GroupDao.observeMosaics
    suspend fun checkName(name: String, excludingGroupId: Long? = null): Outcome<String, GroupError>
    suspend fun create(draft: GroupDraft, memberIds: Set<Long> = emptySet()): Outcome<Long, GroupError>
    suspend fun update(groupId: Long, edit: GroupEdit): Outcome<Unit, GroupError>
    suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError>  // writes only the moved rows (Order)
    suspend fun setMembers(groupId: Long, podcastIds: Set<Long>): Outcome<Unit, GroupError>
    suspend fun applyMembership(podcastIds: Set<Long>, add: Set<Long>, remove: Set<Long>): Outcome<Unit, GroupError>
    suspend fun delete(groupId: Long): Outcome<DeletedGroupToken, GroupError>
    suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError>
}
@JvmInline value class DeletedGroupToken(val value: String)
sealed interface GroupError {
    data object NameEmpty : GroupError
    data class NameTooLong(val max: Int) : GroupError
    data class NameTaken(val groupId: Long) : GroupError
    data object NotFound : GroupError
    data object UndoExpired : GroupError
}
```

`create` and `Rename` validate with `GroupNames`; a `nameKey` unique violation inside the transaction (race) maps to `NameTaken` ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)), as does an `undoDelete` whose `nameKey` was taken meanwhile ([Delete and undo](#delete-and-undo)). `reorder` with a list that is not a permutation of the current IDs returns `NotFound` and writes nothing. `observeMemberships()` is one Room flow over `podcast_group_member` (`SELECT podcastId, groupId`, grouped in Kotlin); 08 uses it for the Library "Ungrouped" chip and the tri-state chips of `AddToGroupsKey` instead of one `observeGroupsOf` flow per selected podcast. Every write sets `podcast_group.updatedAt = now` on the rows it changes (also the timestamp that stamps group fields at a first sync link, [10 Merge](10-sync.md#merge)).

`GroupMerger` (`:core:data`, internal) merges one group into another in one write transaction — memberships united (`INSERT OR IGNORE` with the loser's `orderKey`s), the survivor's own settings row kept, the loser row deleted, a GROUP play context on the loser moved to the survivor — and then calls `GroupChannelSync.sync()`. Restore uses it for backup groups that share a `nameKey`; 10's `SyncApplier` uses it when a merged group's survivor already exists locally ([10 Redirects on clients](10-sync.md#redirects-on-clients)).

### Delete and undo

Deleting never deletes podcasts and can be undone for 10 s ([R2.1](../PLAN.md#21-functional-requirements)).

```mermaid
sequenceDiagram
  participant UI as Group menu (08)
  participant GR as GroupRepositoryImpl
  participant DB as Room
  participant CH as GroupChannelSync (channels on Android)
  UI->>GR: delete(groupId)
  GR->>DB: read snapshot (group row, settings row, member ids, session context)
  GR->>DB: one write transaction to delete the group and clear a GROUP context
  GR-->>UI: DeletedGroupToken, snackbar with Undo for 10 s
  alt Undo within 10 s
    UI->>GR: undoDelete(token)
    alt nameKey still free
      GR->>DB: reinsert same id, uuid and orderKey, settings, members that still exist
      GR->>DB: restore session context if play_session.generation is unchanged
    else another group holds the nameKey
      GR-->>UI: NameTaken(replacementId), nothing written, token kept until the window ends
    end
  else window ends or process dies
    GR->>CH: delete channel new_episodes_uuid (startup sweep covers process death)
  end
```

1. Snapshot in memory: the `podcast_group` row, its `podcast_group_settings` row, member IDs, and whether `play_session` has `contextType = GROUP AND contextId = groupId` (plus its `generation`).
2. One write transaction: `DELETE FROM podcast_group WHERE id = ?` (cascades members and settings), `UPDATE play_session SET contextType = NULL, contextId = NULL, contextAnchorEpisodeId = NULL, contextAnchorSortDate = NULL, generation = generation + 1, updatedAt = :now WHERE contextType = 'GROUP' AND contextId = :groupId` (06 keeps the current item and plays Up next, then stops). After the commit: `RefreshController.reschedulePeriodic()` and `SnapshotScheduler.requestSoon()`.
3. Keep the snapshot under a random token for 10 s; a second delete creates a second token (independent snackbars, 08).
4. Undo: one write transaction re-inserting the row with its original `id`, `uuid` and `orderKey` (`AUTOINCREMENT` never reused the ID; no other row moves), re-inserting settings and memberships for podcasts that still exist, and restoring the session context only if `generation` still equals the post-delete value. If another group now holds the `nameKey` (created after the delete), the re-insert is not attempted: `undoDelete` returns `Outcome.Failure(GroupError.NameTaken(replacementId))`, leaves the replacement group and its members untouched, writes nothing and captures nothing. The token stays valid until the window ends, so renaming the replacement and retrying still restores the snapshot — 08 keeps an Undo action on screen until then ([08 Banners, snackbars and undo](08-ui-ux.md#banners-snackbars-and-undo)); after the window the token yields `UndoExpired`.
5. After the window: cancel the channel's active notification (`tag = channelId`, `id = 5000`) and delete the channel (Android, [Notification channels](#notification-channels)); 08's `ArtworkStore` garbage collection drops `g-{uuid}` ([02 Artwork references](02-data-model.md#artwork-references)).
6. **Sync** ([10 Tombstones and retention](10-sync.md#tombstones-and-retention)). The delete is captured as `deleted = true` (cascaded membership deletes find no parent row and capture nothing, [02 Trigger form](02-data-model.md#trigger-form); receivers drop a deleted group's memberships). On both platforms 10's `OutboxReader` holds those rows back until 10 s after the capture ([10 Outbox and coalescing](10-sync.md#outbox-and-coalescing)), so an undo inside the window never leaves the device: the re-insert captures every field and `in = true` with newer clocks, which supersede the held tombstone rows. After a process death inside the window the delete is final (as locally) and the rows are pushed at the next round. There is no cross-device undo. A device that receives more than 3 group deletes in one pull holds them for the user ([R7.7](../PLAN.md#21-functional-requirements)).

| Effect of deleting a group | Mechanism |
|---|---|
| Memberships, group settings | FK `ON DELETE CASCADE` |
| Podcasts, episodes, user state, downloads | untouched |
| Playing from the group | context cleared in the same transaction; current item continues |
| Feeds tab selected | 08 falls back to All with a snackbar (M2 acceptance 6) |
| Effective settings of former members | resolver flows re-emit; refresh tick rescheduled; 07's planner reacts |
| Notification channel `new_episodes_{uuid}` (Android) | deleted after the undo window |
| Other devices (while linked) | the tombstone travels only after the 10-s undo window on both platforms ([10 Outbox and coalescing](10-sync.md#outbox-and-coalescing)); more than 3 group deletes in one pull are held by the mass-change guard |
| Mosaic artwork `g-{uuid}` | unreferenced → collected |
| Import history (`import_item.groupNamesJson`) | unaffected (names, not IDs) |

### Notification channels

Android only. Channel posting is 03's ([03 New-episode notifications](03-feeds-and-discovery.md#new-episode-notifications)); the lifecycle is here, in `GroupNotificationChannels` (`:core:data` `androidMain`), the Android implementation of the common port `GroupChannelSync` (`sync()`, `onUuidChanged(old, new)`, `onDeleted(uuid)`). The desktop has no channels: its `NoGroupChannels` does nothing, and `DesktopNotifier` applies the same per-group switches without a channel ([11 Notifications](11-desktop.md#notifications)).

| Event | Action |
|---|---|
| A group's `notifyNewEpisodes` becomes `true` | `createNotificationChannel(new_episodes_{uuid}, name = group name, importance DEFAULT, group grp_new_episodes)` |
| Group renamed | only if the group's own `notifyNewEpisodes = true` (the channel exists): `createNotificationChannel` with the same ID and the new name (updates the name, keeps the user's settings); otherwise nothing, so a rename never creates a channel |
| `notifyNewEpisodes` set to `false` or `null` | delete the channel; switching it back on "un-deletes" it with the user's sound and importance |
| Group deleted | delete after the undo window |
| A Replace restore or a sync merge changes a group's `uuid` (`onUuidChanged(old, new)`, called after the commit) | when the group's own setting is `true`: create `new_episodes_{new}` with the old channel's importance, sound and audio attributes, lights and vibration (pattern included) and badge flag, then delete `new_episodes_{old}`; lockscreen visibility and "bypass Do Not Disturb" cannot be set by an app and fall back to their defaults ([Platform constraints](#platform-constraints); answers [10 Open questions](10-sync.md#open-questions) 5) |
| Start-up (`AppInitializer` order 140, after the database open; [01](01-foundation.md#application-start-up)), and `sync()` after settings writes, import commits, restores and sync pages that touched groups or group settings | create missing channels for groups whose own setting is `true`; delete every `new_episodes_*` channel whose UUID is not such a group (the safety net when `onUuidChanged` was missed, for example after process death: the old channel and its user settings go, a new one is created) |

The channel group `grp_new_episodes` and the default channel `new_episodes` are created by 03's `ensureChannels()`. The AOSP per-app limit of 5,000 channels is irrelevant at our scale ([PreferencesHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/PreferencesHelper.java)).

---

## Group feeds

Serves R2.3, R2.4 (data side), R2.5, R2.8, R2.9, R3.4, R8.1. Delivered in [M1](../PLAN.md#m1-subscribe-and-ingest-rss) (All and Podcast sources) and [M2](../PLAN.md#m2-groups-and-group-feeds) (everything else), on both platforms from the same common code. Honours [D16](../PLAN.md#3-key-decisions), [D30](../PLAN.md#3-key-decisions). SQL, indices and EXPLAIN expectations: [02 Feed pages](02-data-model.md#feed-pages), [02 Feed counts](02-data-model.md#feed-counts).

```kotlin
// :core:domain — canonical members first
interface FeedRepository {
    fun pagedFeed(source: FeedSource, filters: FeedFilters, order: FeedOrder): Flow<PagingData<EpisodeRow>>
    fun observeGroupCounts(sinceMs: Long): Flow<Map<Long, FeedCounts>>        // groups without episodes → zeros
    fun observeVirtualCounts(sinceMs: Long): Flow<VirtualCounts>              // All and Ungrouped
    fun observeTabs(): Flow<List<FeedTab>>
    fun observePrefs(source: FeedSource): Flow<FeedPrefs>
    suspend fun setFilters(source: FeedSource, filters: FeedFilters)          // minSortDate ignored, see hide-older
    suspend fun setHideOlderThanDays(source: FeedSource, days: Int?)
    suspend fun setFeedOrder(source: FeedSource, order: FeedOrder)
    suspend fun markVisited(source: FeedSource, leftAt: Long)
    suspend fun countUnplayed(source: FeedSource, sortDateBefore: Long?): Int // "Mark all played" confirmation
    suspend fun downloadAllEstimate(source: FeedSource): DownloadAllEstimate  // "Download all unplayed"
}
// :core:model
data class FeedTab(val source: FeedSource, val title: String, val colorArgb: Int?, val iconKey: String?,
                   val groupUuid: String?)
data class FeedCounts(val unplayed: Int, val newSinceVisit: Int)
data class VirtualCounts(val all: FeedCounts, val ungrouped: FeedCounts)
data class FeedPrefs(val feedOrder: FeedOrder, val playOrder: FeedOrder, val filters: FeedFilters,
                     val hideOlderThanDays: Int?, val lastViewedAt: Long?)
data class DownloadAllEstimate(val episodeIds: List<Long>, val totalCandidates: Int, val knownBytes: Long,
                               val unknownSizeCount: Int, val capped: Boolean)
```

The implementation (`FeedRepositoryImpl`, `:core:data`) builds queries only through `FeedQueryBuilder` (or 02's generated fallback, selected behind this interface by spike S2) and maps `EpisodeRowProjection` to `EpisodeRow` with `PagingData.map`.

### Sources and tabs

| Source | Contains | Order | Persisted view prefs |
|---|---|---|---|
| `FeedSource.All` | every episode of podcasts with `includeInAll = 1` | `NEWEST_FIRST` (fixed in v1) | `groups.all_*` keys ([Settings](#settings); synced) |
| `FeedSource.Group(id)` | every episode of the group's members, membership evaluated live | `podcast_group.feedOrder` | columns of `podcast_group` (synced with the group) |
| `FeedSource.Ungrouped` | episodes of podcasts in no group (ignores `includeInAll`) | `NEWEST_FIRST` (fixed) | `groups.ungrouped_*` keys (synced) |
| `FeedSource.Podcast(id)` | the podcast's episodes (podcast screen, Auto browse) | `podcast.episodeOrder ?: (SERIAL → OLDEST_FIRST else NEWEST_FIRST)` | order in `podcast.episodeOrder`; filters transient (ViewModel) |

Every source applies 02's `VISIBLE` fragment (Shorts unless opted in, upcoming, live, members-only, [04 Content flags and filtering](04-youtube.md#content-flags-and-filtering)). An episode of a podcast in "tech" and "news" appears in both group feeds and once in All; played state is per episode, so marking it in one feed marks it everywhere.

`observeTabs()` emits, in order: All (always); groups with `showAsTab = true` by `orderKey`; Ungrouped when `groups.show_ungrouped_tab` is on **and** at least one podcast is in no group. The "All groups" sheet (`AllGroupsKey`) lists every group including hidden ones. Persisting the selected tab and the deleted-group fallback are 08's ([08 Group feed pager](08-ui-ux.md#group-feed-pager)).

### Filters, order and hide-older-than

| View setting | Stored as | Becomes |
|---|---|---|
| Unplayed only | bit `UNPLAYED = 1` of `filterFlags` | `FeedFilters.unplayedOnly` |
| Downloaded only | bit `DOWNLOADED = 2` | `downloadedOnly` |
| In progress only | bit `IN_PROGRESS = 4` | `inProgressOnly` (`startedAt IS NOT NULL AND playedAt IS NULL`) |
| Audio / video | `mediaFilter` | `media` |
| Hide older than N days | `hideOlderThanDays` ∈ {null, 1, 3, 7, 14, 30, 90, 365} | `minSortDate = floorToHour(now) − N·86 400 000` |
| Newest / oldest first | `feedOrder` | `order` |

`observePrefs` recomputes `minSortDate` on every emission and hourly while collected; rounding to the hour keeps the `RoomRawQuery` stable so `flatMapLatest` does not rebuild the pager on every tick. Filter chips write through `setFilters` immediately (one low-churn row update; `podcast_group` is not observed by the feed `PagingSource`, so the write does not invalidate the open list — the new `Pager` replaces it). On `FeedSource.Podcast`, `setFilters` and `setHideOlderThanDays` throw `IllegalArgumentException` (the podcast screen's filters are transient ViewModel state) and `setFeedOrder` writes `podcast.episodeOrder`. On `All` and `Ungrouped`, `setFeedOrder` throws `IllegalArgumentException` (fixed `NEWEST_FIRST` in v1; 08 shows no order control there).

### Paging hand-off to 08

- One `Pager` per **visible** page: the Feeds ViewModel keeps at most 3 cached flows (current page ± 1) keyed by `(source, filters, order)` in an LRU and never builds pagers for every tab.
- `PagingConfig(pageSize = 40, prefetchDistance = 40, initialLoadSize = 80, enablePlaceholders = true, maxSize = 400)`; flows are built with `flatMapLatest` over `observePrefs(source)` and `.cachedIn(viewModelScope)` ([01 ViewModels and UI state](01-foundation.md#viewmodels-and-ui-state)).
- Lists use `itemKey { it.id }` and `itemContentType { if (it.sourceType == SourceType.YOUTUBE_CHANNEL) 1 else 0 }` (16:9 thumbnails vs square covers, 08).
- Positions, download progress and now-playing never come from the paged row ([D16](../PLAN.md#3-key-decisions)); rows overlay them from `EpisodeLiveStateSource` ([08 Live row state](08-ui-ux.md#live-row-state)).
- Pull-to-refresh calls `RefreshController.refreshFeed(source)` ([03 Refresh scheduling](03-feeds-and-discovery.md#refresh-scheduling)).

### Counts and new since last visit

[R2.8](../PLAN.md#21-functional-requirements). Counts are bounded by a window so an imported back catalogue cannot produce "12,000 unplayed".

- `sinceMs = floorToHour(now) − 30 d`; a group with `hideOlderThanDays < 30` uses its own bound (02's SQL takes the larger date). For All and Ungrouped, `observeVirtualCounts` passes `max(sinceMs, floorToHour(now) − N d)` per feed, N from `groups.all_hide_older_than_days` / `groups.ungrouped_hide_older_than_days` (0 = no bound). The window constant is `GroupCounts.WINDOW_DAYS = 30` (not a user setting). The repository re-evaluates `sinceMs` hourly while collected.
- **Unplayed** = `VISIBLE`, `availability = 'AVAILABLE'`, `playedAt IS NULL`, inside the window ([02 Feed counts](02-data-model.md#feed-counts)). YouTube episodes count with and without the engine (in external mode, opening one in YouTube marks it played, 04).
- **New since last visit** = unplayed **and** `isNew = 1` **and** `firstSeenAt > COALESCE(lastViewedAt, createdAt)`. `isNew` is never set by initial fetches, imports or restores ([03 isNew and back-catalogue guard](03-feeds-and-discovery.md#isnew-and-back-catalogue-guard)), so a fresh import shows no "new" badges.
- All and Ungrouped are counted separately (an episode in two groups counts once in All); their `lastViewedAt` lives in `device_settings` (`groups.all_last_viewed_at`, `groups.ungrouped_last_viewed_at`) because "since last visit" is a per-device notion. When absent it is initialised to `now` on first read.

**Visit rule.** A visit starts when a feed page becomes the settled pager page while the Feeds destination is resumed, and ends when another page settles, the destination leaves composition or the app goes to the background. A visit of ≥ 1 s calls `markVisited(source, leftAt = now)`, which writes `podcast_group.lastViewedAt` (or the `device_settings` key). The ViewModel keeps the value read at visit start as the baseline for the "New" row highlight (`row.isNew && row.firstSeenAt > baseline`), so rows stay highlighted during the visit. `lastViewedAt` is device-local: it is not in backups, never synced ([10 Group and member fields](10-sync.md#group-and-member-fields)), and restored or synced groups start with `lastViewedAt = now`.

### includeInAll and Ungrouped

`podcast.includeInAll` (default 1, toggled as "Show in All" in podcast settings through 03's `PodcastRepository.setIncludeInAll`) hides a high-volume show (an hourly bulletin) from the All feed and All counts only; its groups, its podcast screen, Ungrouped and play contexts other than All still include it ([PO-11](../PLAN.md#48-further-product-owner-decisions): per-podcast switch only, no per-group "hide from All").

### Group actions

[R2.6](../PLAN.md#21-functional-requirements). Menu items on a group (feed header overflow and Library group tile).

| Action | Behaviour | Calls | Milestone |
|---|---|---|---|
| Refresh this group | only member podcasts, forced | `RefreshController.refreshNow(RefreshScope.Group(id))` | M2 |
| Play | [Playing a group](#playing-a-group) | `PlaybackController.playFeed(Group(id), prefs.filters, prefs.playOrder, null)` | M4 |
| Mark all as played | options: all, older than 1 week, 1 month, 3 months; confirmation shows `countUnplayed(source, before)` ("Mark 214 episodes in 'tech' as played?"); no undo | `EpisodeRepository.markFeedPlayed(source, sortDateBefore)` (03; SQL [02 User-state writes](02-data-model.md#user-state-writes)) | M2 |
| Download all unplayed | confirmation "Download 37 episodes (about 1.9 GB, 3 sizes unknown)?"; metered prompt per 07 | `downloadAllEstimate(source)` → `DownloadController.request(ids, DownloadLane.MANUAL, allowMetered = null)` | M6 |
| Share as OPML (desktop: "Export group as OPML…") | per-group export; the desktop saves through a file dialog ([Destinations](#destinations)) | `ExportKey(groupId)` ([OPML export](#opml-export)) | M3 |
| Import OPML into this group | picker; session created with this group as target | `ImportRepository.create(source, CreateImportOptions(targetGroupId = id))` | M3 |
| Edit, Group settings, Delete | editor, settings screen, [Delete and undo](#delete-and-undo) | `GroupEditKey(id)`, `GroupSettingsKey(id)` | M2 |

`downloadAllEstimate` ([02 Download all candidates](02-data-model.md#download-all-candidates), M6): the feed's source predicate, its view filters and `hideOlderThanDays` bound (as [Playing a group](#playing-a-group) rules 1, 3 and 5, no anchor), unplayed, without a `download` row in `QUEUED`…`COMPLETED` (`FAILED` and `MISSING` rows count as candidates), without a tombstone (`downloadDismissedAt IS NULL`; the user deleted those deliberately), and downloadable (`enclosureUrl IS NOT NULL`, or YouTube when `YouTubeCapabilitiesSource.capabilities.value.downloads` at call time, [04 Capability matrix](04-youtube.md#capability-matrix)); newest first, **capped at 200** (`capped = true` → "200 newest of 1,234"). Unlike the context tail, Up next items and the current episode are **not** excluded: downloading them is wanted (answers 02's open question 7). Size per episode follows [07 Estimates](07-downloads.md#estimates): `enclosureLength` when ≥ 100 KB (smaller values are placeholders), else `durationMs × 16 000 B/s` (128 kbit/s) when known, else counted in `unknownSizeCount`; no invented numbers.

### Performance

Budgets are [R2.9](../PLAN.md#21-functional-requirements): first page (COUNT + 80 rows) ≤ 60 ms for a group, ≤ 100 ms for All on the reference device with 300 podcasts, 50,000 episodes and 20 groups; counts for 20 groups ≤ 50 ms (recorded). They are measured by 02's `FeedQueryTimingTest`. If All misses its budget with LIMIT/OFFSET, only All switches to a keyset `PagingSource` keyed by `(sortDate, id)` behind `pagedFeed` (risk [T6](../PLAN.md#8-risks-and-mitigations)); callers do not change.

---

## Effective settings resolution

Serves R2.7, R3.7 (auto-download side), R4.4, R4.5, R7.3. Delivered in M2 (refresh interval, notifications), M4 (speed, skip silence), M6 (auto-download, delete after played), M9a (YouTube auto-download globals with the engine), MS2 (sync classification); the resolver is common code used by both platforms. Honours [D20](../PLAN.md#3-key-decisions), [D45](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions), [PO-37](../PLAN.md#48-further-product-owner-decisions). Storage: `podcast_settings` and `podcast_group_settings` share 02's `ScopeOverrides` columns; `null` = inherit ([02 podcast_settings](02-data-model.md#podcast_settings)). There are no per-episode overrides in v1.

### Rules

First match wins. "Groups" means the podcast's member groups whose column is non-null.

| Setting | Column | Resolution | Global fallback (key owner) | From |
|---|---|---|---|---|
| Playback speed | `playbackSpeed` | podcast → **context group**: the group of `play_session` when `contextType = GROUP` and the episode's podcast is a member of it (also for Up next items) → global | `playback.speed` (06) | M4 |
| Skip silence | `skipSilence` | same as speed | `playback.skip_silence` (06) | M4 |
| Volume boost (reserved) | `boostDb` | same as speed | 06 (M12) | M12 |
| Intro / outro skip (reserved) | `introSkipMs`, `outroSkipMs` | podcast → 0; group columns ignored (intros are per show) | — | M12 |
| Auto-download | `autoDownload` | podcast → `true` if any group says `true`; `false` if groups set it and none says `true` → global | `downloads.auto_download` (07); `YOUTUBE_CHANNEL`: `youtube.auto_download` (04) | M6, M9a |
| Keep latest N | `autoDownloadKeepLatest` | podcast → **max** over groups → global | `downloads.auto_download_keep_latest` (07); `YOUTUBE_CHANNEL`: `youtube.auto_download_keep_latest` (04) | M6 |
| Network | `autoDownloadNetwork` | podcast → **most restrictive** (`UNMETERED` beats `ANY`) → global | `downloads.auto_download_network` (07) | M6 |
| Require charging | `autoDownloadRequireCharging` | podcast → `true` if any group says `true` → global | `downloads.auto_download_require_charging` (07) | M6 |
| Include video | `includeVideoInAutoDownload` | podcast → `false` if any group says `false` → global | `downloads.auto_download_include_video` (07) | M6 |
| Delete after played | `deleteAfterPlayed` | podcast → **least aggressive** (`NEVER` > `AFTER_24H` > `IMMEDIATELY`) → global; applies to manual and auto downloads ([PO-12](../PLAN.md#48-further-product-owner-decisions)) | `downloads.delete_after_played` (07) | M6 |
| New-episode notifications | `notifyNewEpisodes` | podcast → `true` if any group says `true`; `false` if groups set it and none says `true` → global | `feeds.notify_new_episodes` (03) | M2 |
| Refresh interval | `refreshIntervalMinutes` ∈ {0, 60, 120, 240, 480, 720, 1440}; 0 = "Manual only" at every scope ([PO-21](../PLAN.md#48-further-product-owner-decisions)) | podcast (0 → manual only) → **min** over the groups that set a value, with 0 counted as +∞ (a timed group wins; only when every such group says 0 → manual only) → global (0 → manual only). Manual only resolves to `null` | `feeds.refresh_interval_minutes` (03) | M2 |

Additional rules:

1. **Dependent auto-download fields.** In a group, `autoDownloadKeepLatest`, `autoDownloadNetwork`, `autoDownloadRequireCharging` and `includeVideoInAutoDownload` are editable only while that group's `autoDownload = true`; setting `autoDownload` to `false` or `null` clears them in the same write. So the merges above only ever combine groups that enable auto-download. `deleteAfterPlayed` is independent. Podcast-level fields are independent (they also shape a group-enabled download).
2. **Capability.** For `YOUTUBE_CHANNEL` podcasts while `YouTubeCapabilitiesSource.capabilities.value.downloads == false` (external mode: the `armeabi-v7a` APK, the engine turned off or failed, every APK before M9a and the desktop before MD3; [04 Capability matrix](04-youtube.md#capability-matrix), [D77](../PLAN.md#3-key-decisions)) auto-download resolves to `false` with source `NotSupported`; network, charging, include-video and delete-after always use the `downloads.*` globals above ([04 Auto-download for YouTube](04-youtube.md#auto-download-for-youtube)). Capabilities change at run time, so the value can flip in both directions without any settings write; 07's planner sees the flip like any policy change ([07 Auto-download policy](07-downloads.md#auto-download-policy)), and nothing is written to the override rows.
3. **Video.** YouTube episodes have `isVideo = false` (audio-only, D52), so "include video" never blocks them.
4. **Validation** on write: speed 0.5–3.0 in 0.05 steps; keep 1–10; refresh interval in the set above (0 included). Out-of-range values read from a backup (`OverridesV1`), an import or a sync record are dropped (inherit); 0 is in range and restores as manual only.

### Synced and device-local overrides

Override fields live in the same `podcast_settings` and `podcast_group_settings` rows on both platforms, but only some travel through sync ([D20](../PLAN.md#3-key-decisions) amended, [D93](../PLAN.md#3-key-decisions), [PO-37](../PLAN.md#48-further-product-owner-decisions)). 02's capture triggers watch only the synced columns, and a received record writes only those, leaving this device's other columns alone ([10 Per-scope overrides](10-sync.md#per-scope-overrides)). Backups carry every field ([Archive](#archive)).

| Override field (`ScopeOverrides`) | Sync | Why |
|---|---|---|
| `playbackSpeed`, `skipSilence` | synced (`s.<field>`) | how a show or a group should sound is the same on every device |
| `boostDb`, `introSkipMs`, `outroSkipMs` (reserved, M12) | synced | as above; syncing from v1.0 avoids a protocol change when M12 adds them |
| `autoDownload`, `autoDownloadKeepLatest`, `autoDownloadNetwork`, `autoDownloadRequireCharging`, `includeVideoInAutoDownload`, `deleteAfterPlayed` | device-local | storage and networks differ per device (a phone keeps 3, a desktop 20); the desktop ignores the network and charging rules ([07 Desktop runners](07-downloads.md#desktop-runners)) |
| `notifyNewEpisodes` | device-local | notification wishes differ per device; on Android the field drives a channel |
| `refreshIntervalMinutes` | device-local | refresh cost and cadence differ per device (WorkManager's 15-min floor on Android, nothing while the desktop app is quit) |

Consequences:

- The [Rules](#rules) are unchanged and run on each device with its own values, so the effective auto-download policy, notification switch or refresh interval of one podcast may differ between devices; attribution reports this device's sources.
- The global fallbacks follow the same split: the synced `playback.*` keys travel behind "Sync playback settings" ([10 Settings](10-sync.md#settings)); the `downloads.*`, `feeds.refresh_*` and `feeds.notify_new_episodes` globals never do. "Sync playback settings" governs global keys only; the synced override fields of a podcast or group always travel with its record.
- A row whose fields all become `null` is deleted (02); its delete trigger pushes the synced fields as `null` (inherit), and a receiver deletes its own row only when its device-local fields are `null` too.
- Validation ([Rules](#rules) rule 4) applies to received values as to local writes.

### Attribution

Every settings screen shows the effective value and its source ([R2.7](../PLAN.md#21-functional-requirements)). Strings live in `:core:ui` (08 owns layout):

| `SettingSource` | Text |
|---|---|
| `Podcast` | "Set for this podcast" |
| `Group(groupId, name)` | "From group 'news'" (player: "1.5× (from group 'news')", M4 acceptance 7) |
| `Groups(groups)` (a merge decided by several groups) | "From groups 'tech' and 'news'"; three or more: "From 3 groups" (tap lists them). Only the groups that determined the value are listed (for `max`, the groups holding the maximum) |
| `AppDefault` | "App default" |
| `YouTubeDefault` | "YouTube default" |
| `NotSupported` | "Not available for YouTube channels" (row hidden by 08 where [04's capability matrix](04-youtube.md#capability-matrix) says so; the reason line is 08's, [08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)) |

### API

```kotlin
// :core:model
sealed interface SettingSource {
    data object Podcast : SettingSource
    data class Group(val groupId: Long, val name: String) : SettingSource
    data class Groups(val groups: List<Pair<Long, String>>) : SettingSource
    data object AppDefault : SettingSource
    data object YouTubeDefault : SettingSource
    data object NotSupported : SettingSource
}
data class Effective<out T>(val value: T, val source: SettingSource)
data class EffectivePlayback(val speed: Effective<Float>, val skipSilence: Effective<Boolean>, val boostDb: Effective<Float>)
data class EffectiveAutoDownload(
    val podcastId: Long, val enabled: Effective<Boolean>, val keepLatest: Effective<Int>,
    val network: Effective<NetworkPolicy>, val requireCharging: Effective<Boolean>,
    val includeVideo: Effective<Boolean>, val deleteAfterPlayed: Effective<DeleteAfter>,
)
// :core:domain (canonical interface, owned here)
interface EffectiveSettingsResolver {
    suspend fun playback(podcastId: Long, contextGroupId: Long?): EffectivePlayback
    fun observePlayback(podcastId: Long, contextGroupId: Long?): Flow<EffectivePlayback>
    suspend fun autoDownload(podcastIds: Collection<Long>): Map<Long, EffectiveAutoDownload>
    fun observeAutoDownload(): Flow<Map<Long, EffectiveAutoDownload>>        // every podcast; distinctUntilChanged
    suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>>
    suspend fun refreshIntervals(): Map<Long, Effective<Int?>>               // null = manual only
}
```

`contextGroupId` is passed by 06 as `play_session.contextId` when `contextType = GROUP`; the resolver itself checks membership and ignores the group otherwise. The implementation (`:core:data`) reads `ScopeSettingsDao` rows for the podcasts and their groups in one query, the globals from `SettingsRepository`, `podcast.sourceType`, and `YouTubeCapabilitiesSource.capabilities.value` (injected `YouTubeCapabilitiesSource`, `:youtube:api`). Observing flows combine Room flows over `podcast_settings`, `podcast_group_settings`, `podcast_group_member`, `podcast` with the DataStore flows and `capabilities.map { it.downloads }.distinctUntilChanged()`, so `observeAutoDownload()` re-emits when the engine is turned off, fails or comes back (M9a).

| Caller (owner) | When | Call |
|---|---|---|
| `:playback:core`'s `EffectivePlaybackSettings` (06), used by Android's `PlayerFactory`/`QueueProjector` and the desktop's `DesktopPlaybackController` (11) | before the first prepare and on every item transition | `playback(podcastId, contextGroupId)` |
| 06 `PlaybackStateSource.effectivePlayback` | while a session exists | `observePlayback(…)` |
| 07 `AutoDownloadPlanner` (both platforms) | after refresh events; on every emission of `observeAutoDownload()` (debounced 2 s, 07) | `autoDownload(ids)` / `observeAutoDownload()` |
| 07 cleanup | daily and after playback completes | `autoDownload(ids).deleteAfterPlayed` |
| 03 `RefreshScheduler`, `FeedRefresher` and the desktop refresh lane ([03 Desktop refresh](03-feeds-and-discovery.md#desktop-refresh)) | tick computation; `nextRefreshAt` per feed | `refreshIntervals()` |
| 03 `NewEpisodeNotifier` (Android channels; the desktop's `DesktopNotifier`) | once per engine run | `notifications(ids)`; channel choice is 03's (first notifying group by `orderKey`) |

### Writing overrides

```kotlin
// :core:model — mirror of 02's ScopeOverrides (all nullable)
data class SettingOverrides(
    val playbackSpeed: Float? = null, val skipSilence: Boolean? = null, val boostDb: Float? = null,
    val introSkipMs: Long? = null, val outroSkipMs: Long? = null,
    val autoDownload: Boolean? = null, val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: NetworkPolicy? = null, val autoDownloadRequireCharging: Boolean? = null,
    val deleteAfterPlayed: DeleteAfter? = null, val includeVideoInAutoDownload: Boolean? = null,
    val notifyNewEpisodes: Boolean? = null, val refreshIntervalMinutes: Int? = null,
)
enum class SettingField { PLAYBACK_SPEED, SKIP_SILENCE, BOOST_DB, INTRO_SKIP_MS, OUTRO_SKIP_MS, AUTO_DOWNLOAD,
    AUTO_DOWNLOAD_KEEP_LATEST, AUTO_DOWNLOAD_NETWORK, AUTO_DOWNLOAD_REQUIRE_CHARGING, DELETE_AFTER_PLAYED,
    INCLUDE_VIDEO_IN_AUTO_DOWNLOAD, NOTIFY_NEW_EPISODES, REFRESH_INTERVAL_MINUTES }
data class ScopedSettingsView(
    val own: SettingOverrides,                          // this scope's row (all null = no row)
    val effective: SettingOverrides,                    // every field filled with the value that applies; refresh null = manual only
    val sources: Map<SettingField, SettingSource>,      // podcast: resolver attribution (playback without context group);
                                                        // group: Group(self) or AppDefault
    val groupHints: List<GroupPlaybackHint>,            // podcast only: member groups with their own playback values
)
data class GroupPlaybackHint(val groupId: Long, val name: String, val speed: Float?, val skipSilence: Boolean?)
// :core:domain
interface ScopeSettingsRepository {
    fun observePodcast(podcastId: Long): Flow<ScopedSettingsView?>       // null once the podcast is gone
    fun observeGroup(groupId: Long): Flow<ScopedSettingsView?>           // null once the group is gone
    suspend fun updatePodcast(podcastId: Long, change: (SettingOverrides) -> SettingOverrides): Outcome<Unit, SettingsError>
    suspend fun updateGroup(groupId: Long, change: (SettingOverrides) -> SettingOverrides): Outcome<Unit, SettingsError>
}
```

- Writes are a read-modify-write inside one write transaction (`@Upsert` is allowed on these single-writer tables, [02 DAO rules](02-data-model.md#dao-rules)); a row whose fields are all `null` is deleted. Rule 1 (dependent fields) and validation run before the write; an invalid value returns 01's `SettingsError.OutOfRange(field name)` and writes nothing. A podcast or group that no longer exists makes the write a no-op returning success (the screen is closing anyway).
- After a write: refresh interval changed → `RefreshController.reschedulePeriodic()`; `notifyNewEpisodes` changed → `GroupChannelSync.sync()` (Android; the screen requests `POST_NOTIFICATIONS` per 03's permission rule); always → `SnapshotScheduler.requestSoon()` (Android). 07 and 06 observe the resolver flows; nothing else is pushed. Sync needs no call: the triggers capture the synced columns (02).
- 06's scoped commands (`PlaybackController.setSpeed(speed, scope)`, `nd.SPEED_SET_SCOPE`, `nd.SKIP_SILENCE`) write through this repository: `PODCAST` → `updatePodcast`, `GROUP` → `updateGroup(contextGroupId)` (06 first checks that the context is a group containing the podcast, else returns `ScopeWriteResult.NO_CONTEXT_GROUP` and writes nothing), `GLOBAL` → `SettingsRepository`. A scoped write from the player also clears the same field at the more specific scopes on the current chain, so the chosen value takes effect at once ([06 Per-scope playback settings](06-playback.md#per-scope-playback-settings)); attribution then reports the new source. Settings screens never clear other scopes.
- On the podcast settings screen, playback rows show the podcast override or the global value, plus a hint listing member groups with their own value ("While playing from 'news': 1.5×").

---

## Playing a group

Serves R2.6, R3.7, R4.8, R8.5. Delivered in [M4](../PLAN.md#m4-playback-core) (Android) and [MD1](../PLAN.md#md1-desktop-playback) (desktop). Honours [D44](../PLAN.md#3-key-decisions) ([PO-11](../PLAN.md#48-further-product-owner-decisions) default: keep Up next first), [D84](../PLAN.md#3-key-decisions). This section defines **which** episodes a play context contains and **where** it starts; the query is 02's ([02 Play context](02-data-model.md#play-context)), projection and transitions are 06's ([06 Queue and play context](06-playback.md#queue-and-play-context)). The [Start rules](#start-rules) are specified here and implemented once, in `:playback:core`'s `PlayStarter` ([06 Shared playback core](06-playback.md#shared-playback-core)), which Android's session command and the desktop's `DesktopPlaybackController` both run; `PlayStarter` asks `PlayContextResolver` (below) for the spec and the start item, so "Play group" behaves identically on both platforms (MD1 acceptance 3).

```kotlin
// :core:model
data class PlayContextSpec(
    val type: ContextType, val contextId: Long?, val order: FeedOrder,
    val filterFlags: Int, val mediaFilter: MediaFilter,
    val minSortDate: Long?,                 // fixed when the context starts (play_session.contextMinSortDate)
    val boundStartBySubscription: Boolean,  // OLDEST_FIRST groups only
)
// :core:domain
interface PlayContextResolver {
    suspend fun spec(source: FeedSource, filters: FeedFilters, order: FeedOrder): PlayContextSpec
    suspend fun downloadsSpec(): PlayContextSpec
    suspend fun startItem(spec: PlayContextSpec): Long?     // null = nothing to play
}
```

### Context per entry point

| Entry point | `ContextType` / `contextId` | Order | Filters carried into the context | `minSortDate` |
|---|---|---|---|---|
| Group feed: "Play" or a row's play button | `GROUP` / groupId | `podcast_group.playOrder` | the group's view filters: `DOWNLOADED`, `IN_PROGRESS`, `mediaFilter` (`UNPLAYED` is implied) | from `hideOlderThanDays` |
| All feed | `ALL` / null | `NEWEST_FIRST` | All view filters | from `groups.all_hide_older_than_days` |
| Ungrouped feed | `UNGROUPED` / null | `NEWEST_FIRST` | Ungrouped view filters | from `groups.ungrouped_hide_older_than_days` |
| Podcast screen | `PODCAST` / podcastId | effective `episodeOrder` | the screen's transient filters | null |
| Downloads screen "Play all" | `DOWNLOADS` / null | `NEWEST_FIRST` | completed downloads only (all podcasts, ignores `includeInAll`) | null |
| A single episode with no feed (episode detail from a notification, search, preview) | `EXTERNAL` | — | no tail | — |

### Membership of the context tail

An episode is in the tail iff **all** hold (evaluated live on every projector query):

1. Source predicate: group membership, `includeInAll = 1` (All only), no membership (Ungrouped), podcast, or `download.state = COMPLETED` (Downloads).
2. Not played (`playedAt IS NULL`), not in Up next, not the current item.
3. `VISIBLE` and `availability = 'AVAILABLE'` (YouTube premieres, live, members-only, unavailable reasons are skipped, [R3.8](../PLAN.md#21-functional-requirements)).
4. YouTube episodes only while `YouTubeCapabilitiesSource.capabilities.value.inAppPlayback` (never in external mode, [R3.7](../PLAN.md#21-functional-requirements)); the value is bound per query as 02's `youtubePlayable`, so callers re-query when it changes ([02 Play context](02-data-model.md#play-context)).
5. The carried filters and `sortDate ≥ minSortDate`.
6. Strictly after the anchor `(contextAnchorSortDate, contextAnchorEpisodeId)` in the context order; **a null anchor means "from the beginning of the order"** (02's query without the row-value predicate).

### Start rules

1. **Row tapped (explicit start):** current = that episode, anchor = that episode; Up next is kept and plays next, then the tail continues after the anchor.
2. **"Play" without a start and Up next non-empty:** current = the head of Up next (06 moves it out of Up next per its rules), anchor = null, so after Up next the tail starts at the beginning of the order (M4 acceptance 6: two Up next items, then tech's unplayed episodes newest first).
3. **"Play" without a start and Up next empty:** current = `startItem(spec)`, anchor = that item.
4. **`startItem`:** the first tail item from the beginning of the order (02's start-item query). For `GROUP` with `OLDEST_FIRST` (`boundStartBySubscription = true`) the first search adds `e.sortDate >= p.subscribedAt`, so "Play fiction" does not start at a 2011 episode of a show subscribed this year; if that finds nothing, a second search runs without the bound (only the back catalogue is left unplayed). `PODCAST` with `OLDEST_FIRST` never uses the bound (serial shows start at episode 1).
5. `startItem == null` and Up next empty → `PlaybackController.playFeed` returns an outcome the UI shows as "Nothing unplayed in 'tech'"; in external mode, a group whose unplayed items are all YouTube shows "Episodes in 'tech' open in YouTube".

```mermaid
sequenceDiagram
  participant UI as Group feed (08)
  participant PC as PlaybackController (06 or 11)
  participant PS as PlayStarter (playback core)
  participant SY as PrePlaySync (10)
  participant CR as PlayContextResolver (05)
  participant Q as QueueRepository (06)
  UI->>PC: playFeed(Group(id), prefs.filters, prefs.playOrder, startEpisodeId = null)
  PC->>PS: beforeUiStart() (from MS3)
  PS->>SY: beforeStart() while linked, at most 1.5 s
  PC->>PS: start the context (Android inside the nd.PLAY_CONTEXT session command)
  PS->>CR: spec(source, filters, order)
  CR-->>PS: PlayContextSpec(GROUP, id, order, flags, minSortDate)
  alt Up next empty
    PS->>CR: startItem(spec)
    CR-->>PS: episodeId or null
  end
  PS->>Q: commit play_session (current, context, anchor), generation + 1
  PS->>Q: observeVirtualQueue(K = 20) using the tail rules
  PC->>PC: play from the visible UI (Media3 on Android, the audio engine on the desktop)
```

On Android 06 runs `PlayStarter` inside the session command, and the desktop controller runs it on its playback thread, so on both platforms the write and the first projection are atomic ([06 Queue and play context](06-playback.md#queue-and-play-context)). From MS3, while a sync server is linked, `PlayStarter.beforeUiStart()` first gives 10's "pull before play" at most 1.5 s, so a newer position from another device is not overwritten by a stale local start ([06 Starting playback](06-playback.md#starting-playback), [10 Episode-state rules](10-sync.md#episode-state-rules)).

Edge cases:

- New episodes arriving while playing: `NEWEST_FIRST` contexts keep moving towards older items, so newer arrivals are not inserted behind the anchor; the next "Play" starts with them. `OLDEST_FIRST` contexts pick them up at the end.
- A podcast removed from the group drops out of the tail at the next projector query; the current item keeps playing. Deleting the group clears the context ([Delete and undo](#delete-and-undo)).
- The context group's playback settings apply to every item whose podcast is a member, including Up next items ([Rules](#rules)).
- Sync: the `session` record carries the context with its group by `uuid` and its podcast by `syncId` ([10 Episode, Up next and session fields](10-sync.md#episode-up-next-and-session-fields)); a device that adopts a remote session ("Continue on this device", MS3) resolves them locally, and this section's tail rules then decide the same episodes on both devices, except where device-local state differs (downloads for a `DOWNLOADED` filter, capabilities).
- In-app YouTube turned off, failed or back while a context plays (M9a; the desktop from MD3): `startItem` reads the capability at call time; 06's projector re-queries the tail, so YouTube items leave or rejoin it at the next projection, and YouTube rows already in Up next stay where they are ([06 YouTube branch](06-playback.md#youtube-branch)).

---

## OPML export

Serves R1.4, R1.5, R1.6, R1.9, R8.1. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) on both platforms (YouTube attributes and NewPipe JSON in [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds)). Honours [D31](../PLAN.md#3-key-decisions). Spec: [OPML 2.0](http://opml.org/spec2.opml).

### Model and writer

Features reach export only through `ExportRepository` (`:core:domain`); its implementation `OpmlExporter` (`:core:data` `commonMain`) reads podcasts, groups and memberships in one read transaction, builds an `ExportDocument` on `Default`, and calls the pure writer in common `:feeds` (which knows no `:core:model` types; `ExportLayout` maps 1:1 to `OpmlLayout`). Only the destination differs per platform (`ExportDestination`, [Destinations](#destinations)).

```kotlin
// :core:model
enum class ExportFormat { OPML, NEWPIPE_JSON }                  // NEWPIPE_JSON from M8, full export only
enum class ExportLayout { GROUPED, FLAT }                       // also the type of the setting backup.opml_layout
data class ExportRequest(val groupId: Long?, val format: ExportFormat = ExportFormat.OPML,
                         val layout: ExportLayout = ExportLayout.GROUPED, val includeYouTube: Boolean = true,
                         val includePasswords: Boolean = false)
data class PreparedExport(val fileName: String, val mimeType: String,
                          val shareUri: String?,   // Android: content://${applicationId}.fileprovider/export/…; desktop: null (no share sheet)
                          val feeds: Int, val privateLinks: Int, val passwords: Int)
// :core:domain — owned here
interface ExportRepository {
    suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError>   // writes <cache>/export/{fileName}
    suspend fun saveTo(export: PreparedExport, destinationUri: String): Outcome<Unit, ExportError>  // Android: SAF copy with "wt";
                                                                                   // desktop: atomic replace of a file: URI
}
```

```kotlin
// :feeds (common) — ch.lkmc.neutrodyne.feeds.opml
data class ExportDocument(val title: String, val createdAt: Long, val groups: List<ExportGroup>,
                          val feeds: List<ExportFeed>)                 // groups in group order (orderKey, id)
data class ExportGroup(val key: String, val name: String, val colorArgb: Int?, val iconKey: String?)
data class ExportFeed(
    val displayTitle: String,   // COALESCE(customTitle, title)
    val feedTitle: String, val xmlUrl: String, val htmlUrl: String?, val language: String?,
    val groupKeys: List<String>,        // member groups in group order; first = primary group
    val youtube: Boolean, val ytVariants: Int?,
)
enum class OpmlLayout { GROUPED, FLAT }
object OpmlWriter { fun write(out: okio.BufferedSink, doc: ExportDocument, layout: OpmlLayout) }  // UTF-8, never throws on content
```

The writer is hand-written string output in common code (no `XmlSerializer`: none exists in `commonMain`, Android's throws `IllegalArgumentException` on characters outside XML 1.0, [KXmlSerializer](https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlSerializer.java), and the sync server writes the same OPML into its per-account backup ZIPs, [10 Backups](10-sync.md#backups)). Exporting 1,000 feeds takes well under 100 ms; no progress UI.

### Structure

**Grouped (hybrid, default):**

1. `<?xml version="1.0" encoding="UTF-8"?>`, `<opml version="2.0" xmlns:nd="urn:neutrodyne:opml:1">`.
2. `<head>`: `<title>` ("Neutrodyne subscriptions", or "Neutrodyne: {group name}" for a group export), `<dateCreated>` in RFC 822 with a four-digit year and `GMT` (`EEE, dd MMM yyyy HH:mm:ss 'GMT'`, formatted in common code with kotlinx-datetime and fixed English day and month names), `<docs>http://opml.org/spec2.opml</docs>`.
3. `<body>`: one folder outline per group in group order (`orderKey`), **including empty groups** (`<outline text="fiction" title="fiction"/>`), so names and order round-trip.
4. Each feed appears **exactly once**, inside the folder of its primary group (first in group order), with `category` listing **all** its groups in group order.
5. Ungrouped feeds follow the folders at top level.
6. Inside a folder and among ungrouped feeds, feeds are sorted by `displayTitle.lowercase()` in code-point order, ties by `displayTitle`, then `xmlUrl` — deterministic golden files on every platform and the server. (Until the scope revision this used `java.text.Collator`, which is JVM-only; accented initials now sort after `z`, which only matters to readers that keep file order.)

**Flat:** no folder outlines; every feed at top level in the same sort order, `category` kept. Flat output preserves groups and memberships for Neutrodyne and tag-aware readers, but **not** empty groups, group order, colours or icons (documented in the dialog: "Groups are kept as tags only").

### Attributes

| Outline | Attribute | Value |
|---|---|---|
| Folder | `text`, `title` | group name, raw (only XML-escaped) |
| Folder | `nd:groupColor` | `#RRGGBB` uppercase hex of `colorArgb` (omitted when null) |
| Folder | `nd:groupIcon` | `iconKey` (omitted when null) |
| Feed | `type` | always `rss` (also Atom and YouTube; the spec has no Atom value) |
| Feed | `text` | `displayTitle` |
| Feed | `title` | `feedTitle` |
| Feed | `xmlUrl` | `podcast.feedUrl` (no userinfo); with "Include passwords" `https://user:pass@host/…` (user and password percent-encoded per RFC 3986 userinfo rules) |
| Feed | `htmlUrl` | `podcast.link` if set; YouTube: `https://www.youtube.com/channel/{UC…}` |
| Feed | `language` | `podcast.language` if set |
| Feed | `category` | group names joined by `,`; inside each name `%` → `%25`, `,` → `%2C`, `/` → `%2F` (so a name never splits and never reads as a path); omitted for ungrouped feeds |
| Feed | `nd:source` | `rss` or `youtube` |
| Feed | `nd:ytVariants` | YouTube only: set bits in the fixed order `UULF,UUSH,UULV` ([04 OPML](04-youtube.md#opml)) |

Not written: `description` (bloat), `version`, `created`, Neutrodyne IDs or UUIDs. **Escaping** in every attribute and text node: `&` `&amp;`, `<` `&lt;`, `>` `&gt;`, `"` `&quot;`, newline `&#10;`, CR `&#13;`, tab `&#9;`. Before escaping, strip characters invalid in XML 1.0 (`U+0000–U+0008`, `U+000B`, `U+000C`, `U+000E–U+001F`, `U+FFFE`, `U+FFFF`) and unpaired surrogates, so one bad title can never abort an export.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0" xmlns:nd="urn:neutrodyne:opml:1">
  <head>
    <title>Neutrodyne subscriptions</title>
    <dateCreated>Sun, 04 Oct 2026 21:30:00 GMT</dateCreated>
    <docs>http://opml.org/spec2.opml</docs>
  </head>
  <body>
    <outline text="tech" title="tech" nd:groupColor="#2196F3" nd:groupIcon="memory">
      <outline type="rss" text="ATP" title="Accidental Tech Podcast" xmlUrl="https://atp.fm/rss"
               htmlUrl="https://atp.fm" category="tech,news" nd:source="rss"/>
      <outline type="rss" text="Marques Brownlee" title="Marques Brownlee"
               xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"
               htmlUrl="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ" category="tech"
               nd:source="youtube" nd:ytVariants="UULF"/>
    </outline>
    <outline text="news" title="news" nd:groupColor="#F44336">
      <outline type="rss" text="The Daily" title="The Daily" xmlUrl="https://feeds.example.com/daily"
               category="news" nd:source="rss"/>
    </outline>
    <outline text="fiction" title="fiction" nd:groupColor="#673AB7" nd:groupIcon="auto_stories"/>
    <outline type="rss" text="Some Show" title="Some Show" xmlUrl="https://example.com/rss" nd:source="rss"/>
  </body>
</opml>
```

Importer behaviour that makes nesting safe ([D31](../PLAN.md#3-key-decisions)): AntennaPod reads every outline with `xmlUrl` at any depth, Pocket Casts scans `xmlUrl=` line by line and dedupes, gPodder and FreshRSS recreate folders (FreshRSS keeps the innermost folder) ([AntennaPod](https://github.com/AntennaPod/AntennaPod), [Pocket Casts](https://github.com/Automattic/pocket-casts-android), [gPodder opml.py](https://github.com/gpodder/gpodder), [FreshRSS ImportService](https://github.com/FreshRSS/FreshRSS/blob/edge/app/Services/ImportService.php)).

### Options and warnings

`ExportKey(groupId: Long?)` dialog (`:feature:importexport`; visuals 08):

| Option | Default | Remembered in |
|---|---|---|
| Format: Grouped / Flat list | Grouped | `backup.opml_layout` |
| Include YouTube channels ("Other podcast apps may not be able to play these") | on | `backup.opml_include_youtube` |
| Include passwords for private feeds | off, every time | never remembered |
| NewPipe JSON (YouTube channels only, M8; format: [04 NewPipe subscriptions JSON](04-youtube.md#newpipe-subscriptions-json-import-and-export)) | — | — |

- With YouTube excluded, a group whose members are all channels is written as an empty folder (grouped).
- **Private-URL warning** ([R1.9](../PLAN.md#21-functional-requirements)): `prepare` counts the written feeds for which 03's `PrivateFeedUrls.looksPrivate(xmlUrl)` is true ([03 Private feed URLs](03-feeds-and-discovery.md#private-feed-urls)) into `privateLinks`, and the userinfo written into `passwords`. If either is > 0 the dialog shows "This file contains private access links for N feeds. Anyone with the file can listen to them." plus, with passwords, "It also contains N passwords in plain text." (Continue / Cancel) **before** the file leaves the app (save or share). Cancel leaves the cache copy to `ExportFilesCleaner`.
- Passwords come from `SecretStore.forPodcast` at write time (03; Android Keystore, desktop per [PO-44](../PLAN.md#48-further-product-owner-decisions)); podcasts whose credential cannot be read are exported without userinfo.

### Destinations

| Destination | Mechanism |
|---|---|
| Save to file (Android) | After `prepare` and the warning: `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(export.mimeType))` launched with `export.fileName`, then `saveTo(export, uri)`, which copies the cache file to `openOutputStream(uri, "wt")` (if the provider rejects `"wt"` with `IllegalArgumentException` or `FileNotFoundException`, retry with `"w"`, safe because `CreateDocument` returns a new empty document — Unverified which providers do). Snackbar "Saved" with "Share" |
| Share (Android) | After `prepare` and the warning: `ACTION_SEND` with type `export.mimeType`, `EXTRA_STREAM = export.shareUri` (`FileProvider` authority `${applicationId}.fileprovider`), `EXTRA_TITLE`, `ClipData.newRawUri(fileName, uri)`, `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser` (the `ClipData` carries the grant through the chooser) |
| Save to file (desktop) | After `prepare` and the warning: `FileSaver.save(suggestedName = export.fileName)` (`:core:ui`'s `PlatformActions`, 08: the native save dialog, which asks before replacing a file; on Linux the XDG portal's `SaveFile`), then `saveTo(export, uri)` through `FileExportDestination`: copy to a temporary file in the target directory, flush, then an atomic move over the target (`FileSystem.atomicMove`) — a cancelled or failed save leaves an existing file untouched; where the directory refuses the temporary file or the move (some network shares), a direct copy is the fallback, and a failure during it reports that the file may be incomplete; a chosen name without an extension gets `.opml` (`.json`, `.zip`). Snackbar "Saved" with "Show in folder" (`RevealInFolder`, [11 Show in folder](11-desktop.md#show-in-folder)) |
| Share (desktop) | None: the desktop has no share sheet ([11 Behaviour differences from Android](11-desktop.md#behaviour-differences-from-android)); "Share as OPML" reads "Export group as OPML…" there and saves |

File names: OPML `neutrodyne-subscriptions-{yyyy-MM-dd}.opml`, MIME `text/x-opml`; a group `neutrodyne-{slug}-{yyyy-MM-dd}.opml` (slug = `nameKey` with non-`[a-z0-9]` runs → `-`, trimmed, ≤ 40 chars, fallback `group`); NewPipe JSON `neutrodyne-youtube-{yyyy-MM-dd}.json`, MIME `application/json` ([04 NewPipe subscriptions JSON](04-youtube.md#newpipe-subscriptions-json-import-and-export)).

`res/xml/file_paths.xml` (`:app`, Android) contains `<cache-path name="export" path="export/"/>` (07 adds the download roots to the same file). `ExportFilesCleaner` (an `AppInitializer`, order 300, both platforms) deletes files in the export cache directory (Android `cacheDir/export/`, desktop `<cache>/export/`, [11 AppDirs](11-desktop.md#appdirs)) older than 24 h; each export also deletes older files with the same name.

**Single-group export** ([R1.5](../PLAN.md#21-functional-requirements); shared on Android, saved on the desktop): `ExportKey(groupId)` writes one folder for that group with all its members (YouTube per the option) and `category` = that group only; Flat writes the members with `category` = that group. The OPML inside a full backup is the grouped export without passwords.

---

## OPML import

Serves R1.1, R1.2, R1.3, R1.6, R8.3, N9. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) on both platforms; YouTube items become importable in [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds). Honours [D24](../PLAN.md#3-key-decisions), [D32](../PLAN.md#3-key-decisions), [D66](../PLAN.md#3-key-decisions). Tables: [02 import_session](02-data-model.md#import_session), [02 import_item](02-data-model.md#import_item); commit SQL: [02 Import commit](02-data-model.md#import-commit). The same pipeline serves every format in [Other import formats](#other-import-formats).

```mermaid
sequenceDiagram
  participant U as User
  participant E as ExternalImportActivity, picker, file dialog or drop
  participant I as ImportRepository
  participant DB as Room
  participant W as ImportFetchRunner (worker or desktop lane)
  participant R as FeedRefresher (03)
  U->>E: open, share or pick a file
  E->>I: create(ImportSource)
  I->>I: copy to the import cache, sniff, parse, classify
  I->>DB: import_session PREVIEW and import_item rows
  I-->>E: sessionId
  E->>U: ImportKey(sessionId) preview
  U->>I: confirm(sessionId)
  I->>DB: commit chunks of 500 (pending podcasts, groups, memberships, aliases)
  I->>W: schedule (import-sessionId work, or the import-backup lane)
  W->>R: run(Podcasts(ids), origin IMPORT)
  R-->>W: FeedRunEvent per podcast
  W->>DB: item statuses, treat-as-played, session DONE
  W-->>U: report notification (Android channel import_backup, desktop OS notification)
```

```kotlin
// :core:domain — canonical interface, owned here
interface ImportRepository {
    suspend fun create(source: ImportSource, options: CreateImportOptions = CreateImportOptions()): Outcome<Long, ImportError>
    fun observeSession(sessionId: Long): Flow<ImportSessionView?>
    fun pagedItems(sessionId: Long, filter: ImportItemFilter): Flow<PagingData<ImportItemView>>
    fun observeGroupProposals(sessionId: Long): Flow<List<GroupProposal>>
    suspend fun setSelected(sessionId: Long, ordinals: Set<Int>?, selected: Boolean)   // null = all selectable
    suspend fun updateOptions(sessionId: Long, change: (ImportOptions) -> ImportOptions)
    suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError>
    suspend fun cancel(sessionId: Long)                       // PREVIEW: delete; COMMITTED/FETCHING: stop the worker, CANCELLED
    suspend fun retry(sessionId: Long, ordinals: Set<Int>)
    suspend fun editUrl(sessionId: Long, ordinal: Int, input: String): Outcome<Unit, AddPodcastError>
    suspend fun enterPassword(sessionId: Long, ordinal: Int, credentials: BasicCredentials): Outcome<Unit, AddPodcastError>
    suspend fun remove(sessionId: Long, ordinals: Set<Int>)  // unsubscribes podcasts this session created (never ALREADY_SUBSCRIBED/MERGED)
    fun observeOpenSessions(): Flow<List<ImportSessionView>>  // PREVIEW, COMMITTED and FETCHING, for a resume banner (08)
}
// :core:model
data class ImportSource(val uri: String, val displayName: String?)          // canonical: content:// (Android), file:// (desktop), https://
data class CreateImportOptions(val targetGroupId: Long? = null)              // "Import OPML into this group"
```

### Session and item states

```mermaid
stateDiagram-v2
  [*] --> PREVIEW: create
  PREVIEW --> [*]: cancel deletes session and payload
  PREVIEW --> COMMITTED: confirm (commit transactions done)
  COMMITTED --> FETCHING: ImportFetchRunner starts
  FETCHING --> DONE: no item QUEUED
  COMMITTED --> CANCELLED: cancel
  FETCHING --> CANCELLED: cancel (podcasts stay subscribed and pending)
  DONE --> FETCHING: Retry or Edit URL or Enter password
  DONE --> [*]: db-maintenance after 7 days
  CANCELLED --> [*]: db-maintenance after 7 days
```

| Item status | Set when | Selectable in preview | Report section |
|---|---|---|---|
| `PREVIEW` | new valid entry | yes | — |
| `ALREADY_SUBSCRIBED` | `feedKey` or alias match | no (memberships still apply) | "Already subscribed (groups updated)" |
| `DUPLICATE_IN_FILE` | same identity as an earlier entry; its group names are merged into that entry | no | "Not imported" |
| `INVALID_URL` | no http(s) URL after normalisation, or an unsupported service (NewPipe `service_id ≠ 0`) | no | "Not imported" |
| `YOUTUBE_UNSUPPORTED_YET` | YouTube item before M8, or a `PL…` playlist (`errorDetail = "playlist"`) | no | "Not imported" |
| `QUEUED` | committed, not yet fetched (or retried) | — | progress |
| `SUBSCRIBED` | first ingest succeeded (also an empty but valid feed) | — | "Done" |
| `MERGED` | redirect or alias identity matched an existing podcast ([03 Podcast dedupe and merge](03-feeds-and-discovery.md#podcast-dedupe-and-merge)) | — | "Merged with existing" |
| `NOT_A_FEED` | `NOT_A_FEED`, `PARSE_ERROR`, `UNSUPPORTED_LIST_FEED` (after one autodiscovery attempt, [03 Refresh of pending podcasts](03-feeds-and-discovery.md#refresh-of-pending-podcasts)) | — | "Need attention" |
| `NO_MEDIA` | items but none playable (blog feed from a reader's OPML) | — | "No audio or video found" with "Remove all" |
| `AUTH_REQUIRED` | `HTTP_AUTH` | — | "Need attention" (Enter password) |
| `GONE` | `HTTP_GONE`, or `HTTP_NOT_FOUND` for RSS | — | "Need attention" |
| `FETCH_FAILED` | any other failure; `errorDetail` = `FeedErrorKind` name, `DEFERRED` (YouTube feeds paused, the channel loads with a later refresh) or YouTube resolution failure | — | "Need attention"; `DEFERRED` items under "Will load later" without actions |

Unselected `PREVIEW` items are never committed and stay `PREVIEW` (shown under "Not imported"). Failed podcasts stay subscribed with an error badge (AntennaPod 3.1 lesson, [forum](https://forum.antennapod.org/t/import-issues-with-opml/2676)). `cancel` on a `COMMITTED`/`FETCHING` session cancels unique work `import-{sessionId}` (Android; the desktop lane stops a running loop at its next item and skips `CANCELLED` sessions) and sets `CANCELLED`; its podcasts stay `PENDING_FIRST_FETCH` and are fetched by the next periodic refresh (still `INITIAL`, no storm). `remove` applies only to items whose podcast this session created (`QUEUED` and the need-attention statuses); `ALREADY_SUBSCRIBED` and `MERGED` items never unsubscribe anything.

`ImportItemFilter` → statuses passed to 02's `ImportDao.pagedItems(sessionId, statuses)` ([02 Import commit](02-data-model.md#import-commit)):

| Filter | Statuses |
|---|---|
| `ALL` | every `ImportItemStatus` |
| `NEW_ONLY` (preview "Only new") | `PREVIEW` |
| `NOT_IMPORTED` | `DUPLICATE_IN_FILE`, `INVALID_URL`, `YOUTUBE_UNSUPPORTED_YET`, plus `PREVIEW` once the session is past `PREVIEW` (unselected items) |
| `NEEDS_ATTENTION` | `NOT_A_FEED`, `NO_MEDIA`, `AUTH_REQUIRED`, `GONE`, `FETCH_FAILED` |

### 1. Acquire

`PayloadStore` (`:core:data` `commonMain`, on the injected Okio `FileSystem`) copies the source before anything else, reading it through the platform `PayloadSource` (Android `ContentPayloadSource`, desktop `FilePayloadSource`):

1. `content://` (Android; and `file://` from old senders, best effort: without storage permission it usually ends in step 4): query `OpenableColumns.DISPLAY_NAME` and `SIZE`; reject `SIZE > 50 MiB` (`ImportError.TooLarge`); copy through a counting stream that aborts at 50 MiB (sizes lie) to `<cache>/import/incoming-{random}.tmp` (Android `cacheDir`).
2. `https://` ("Import from URL" box, or 03's `AddPodcastError.SubscriptionList(url)` "Import it" action): GET with the FEED client of 01's `NeutrodyneHttpClients` (Ktor over the island's OkHttp client; 03's timeouts, no credentials; on Android `LocalNetworkGuardDns` applies, the desktop has no LAN guard), same 50 MiB cap.
3. After the session row exists, rename to `<cache>/import/{sessionId}.bin` and store `payloadPath = "import/{sessionId}.bin"`.
4. A `SecurityException` or `FileNotFoundException` while reading → `ImportError.Unreadable` ("Couldn't open this file. Try choosing it from inside Neutrodyne.").
5. `create` runs steps 1, 2 and 7 in the caller's coroutine (on Android the URI grant lives only as long as the caller's activity), then runs sniffing, parsing, classification and the `PREVIEW` inserts as one `@ApplicationScope` job that the caller awaits. If the caller is cancelled (the user left `ExternalImportActivity`), the job still finishes and posts the "Ready to review" notification ([Receiving files](#receiving-files)).
6. `FirstLaunchRestoreInitializer` and the "Restore Android backup" action ([Auto Backup](#auto-backup), Android) use the internal `PayloadStore.adopt(path)`, which copies an app-private file the same way; `ImportSource` never points at app-private files.
7. `file://` (desktop: the import dialog, a drop onto the window, the `.opml` association or a second launch's hand-off, [Desktop inputs](#desktop-inputs)): only a regular file (no directory, device or link to one); its size from the file system must be ≤ 50 MiB (`TooLarge`; 11's 64 MiB pre-check runs first); copied through the same counting stream to `<cache>/import/` ([11 AppDirs](11-desktop.md#appdirs)), so the user may move or delete the original at once. A permission error — including a refused macOS folder-privacy prompt ([11 macOS folder privacy](11-desktop.md#macos-folder-privacy)) — is `Unreadable`.

### 2. Sniff and parse

`ImportSourceSniffer` (`:feeds`, common) decides the format from bytes, never from MIME type or extension ([Sniffing](#sniffing)). For `OPML`, the bound `OpmlReader` — `XmlPullOpmlReader` from the `:feeds:jvm` island on both platforms, over Android's platform `XmlPullParser` or kxml2 2.3.0 on the desktop ([03 Package layout](03-feeds-and-discovery.md#package-layout)) — runs a cascade over the payload file (re-opened per pass):

```kotlin
// :feeds (common) — ch.lkmc.neutrodyne.feeds.opml
data class OpmlLimits(val maxDepth: Int = 32, val maxFeeds: Int = 10_000, val maxOutlines: Int = 200_000,
                      val maxAttrChars: Int = 8_192)
enum class ParseMode { STRICT, RELAXED, SALVAGE }
data class FolderRef(val id: Int, val name: String, val parentId: Int?, val colorArgb: Int?, val iconKey: String?)
data class OpmlEntry(
    val ordinal: Int, val url: String, val text: String?, val title: String?, val htmlUrl: String?,
    val type: String?, val folderId: Int?,          // immediate parent folder
    val categories: List<String>,                   // decoded tokens, last path segment
    val preselect: Boolean,                         // false inside isComment, subscribed="0", type="link"
    val ndSource: String?, val ndYtVariants: String?,
)
data class OpmlDocument(val entries: List<OpmlEntry>, val folders: List<FolderRef>, val headTitle: String?,
                        val neutrodyne: Boolean, val mode: ParseMode, val ignoredOutlines: Int)
enum class OpmlFailure { ENTITY_DECLARED, TAG_TOO_LONG, TOO_DEEP, TOO_MANY_FEEDS, TOO_MANY_OUTLINES, NO_OUTLINES }
sealed interface OpmlReadResult {                   // :feeds cannot see :core:common's Outcome
    data class Ok(val document: OpmlDocument) : OpmlReadResult
    data class Failed(val failure: OpmlFailure) : OpmlReadResult
}
interface OpmlReader { fun read(open: () -> okio.Source, limits: OpmlLimits = OpmlLimits()): OpmlReadResult }

// :feeds:jvm (JVM island) — ch.lkmc.neutrodyne.feeds.jvm.opml
class XmlPullOpmlReader(private val parsers: PullParserFactory) : OpmlReader   // 03's factory: platform parser or kxml2
```

Parser setup (03's `PullParserFactory` and `PrologGuard` in the same island, [03 Parser](03-feeds-and-discovery.md#parser)): namespace processing **off** (prefixed attributes keep raw names such as `nd:source`), `FEATURE_PROCESS_DOCDECL` off, `setInput(source.buffer().inputStream(), null)` so KXml detects BOMs and the declared encoding (never a `Reader`, which would ignore `encoding="ISO-8859-1"`), leading ASCII whitespace skipped while preserving a BOM ([KXmlParser](https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java)). `PrologGuard` rejects any `<!ENTITY` in the prolog (`ENTITY_DECLARED`): no XXE, no billion laughs. `MarkupGapGuard` wraps the stream of every pass and fails with `TAG_TOO_LONG` after more than 1 MiB without a `<` character (byte `3C` in ASCII-compatible encodings, where it never occurs inside a multi-byte sequence; the code unit `003C` at an aligned position in UTF-16, detected by BOM or by the first bytes `3C 00`/`00 3C`). XML forbids a literal `<` in attribute values and OPML has no long text nodes, so no legitimate file comes near this, and no parser buffer (KXml builds each attribute value in memory) can grow past about 1 MiB of characters.

Cap failures (`ENTITY_DECLARED`, `TAG_TOO_LONG`, `TOO_DEEP`, `TOO_MANY_FEEDS`, `TOO_MANY_OUTLINES`) are final in every pass; only `XmlPullParserException` falls through:

1. **Strict** pass. On `XmlPullParserException` → 2.
2. **Relaxed** pass (`http://xmlpull.org/v1/doc/features.html#relaxed` = true; tolerates `&nbsp;`, some unquoted attributes). On failure → 3.
3. **Salvage**: a streaming scanner reads the decoded text in 64 KiB chunks, extracts each `<outline …>` tag (≤ 64 KiB per tag), and parses its attributes with `(\w[\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')`, unescaping the five predefined entities and numeric references. The feed and outline caps and `MarkupGapGuard` apply. Folders are lost; `category` survives (it is an attribute), so groups of our own exports usually survive too. `mode = SALVAGE` → warning "This file is damaged; folders could not be read" ([R1.1](../PLAN.md#21-functional-requirements)).

Walk rules (strict and relaxed):

- Element names and attribute names are matched case-insensitively (a lowercase attribute map per outline: `xmlurl`, `url`, `text`, `title`, `htmlurl`, `type`, `category`, `iscomment`, `subscribed`, `nd:source`, `nd:ytvariants`, `nd:groupcolor`, `nd:groupicon`); `type` values are lowercased.
- An outline with `xmlUrl` — or with `url` when `type` is `rss` or `link` and the URL does not end in `.opml` (gPodder fallback) — is a **feed**, regardless of `type` (AntennaPod writes `type="atom"`). Its children are ignored (Overcast extended nests `podcast-episode` outlines in feeds).
- Outlines with `type` ∈ {`podcast-episode`, `podcast-playlist`, `include`} or `type="link"` with a `.opml` URL are **ignored** with their subtree (counted in `ignoredOutlines`, warning "12 entries were ignored: episode lists"). `include`/`link` lists are never fetched.
- Any other outline is a **folder**; its name is `text ?: title`, trimmed.
- `isComment="true"` marks the subtree `preselect = false` (not dropped); Overcast `subscribed="0"` and `type="link"` entries are `preselect = false`.
- Caps: depth > 32 → `TOO_DEEP`; > 10,000 feeds → `TOO_MANY_FEEDS`; > 200,000 outlines → `TOO_MANY_OUTLINES`; an attribute longer than 8,192 chars drops that outline with a warning. Each failure rejects the file with a clear message (M3 acceptance 3). `neutrodyne = true` when the root has `xmlns:nd="urn:neutrodyne:opml:1"`.
- `category` tokens: split on `,` first; trim; for a token containing a literal `/` keep the last non-empty segment (`/Harvard/Berkman` → `Berkman`, spec "slash-delimited category strings"); then decode exactly once in a single left-to-right pass, mapping only `%25` → `%`, `%2C` → `,` and `%2F` → `/` (hex case-insensitive). A decoded `,` never re-splits, a decoded `/` never re-segments, and `%25` never decodes twice (`a%252Cb` → `a%2Cb`, never `a,b`); `+` is never a space, and a `%` not followed by two hex digits stays literal.

### 3. Classify

`ImportClassifier` (`:core:data` `commonMain`, because it needs `:youtube:api`) turns each entry into an `import_item`:

1. `YouTubeUrlClassifier.classify(url)`, then `classify(htmlUrl)` ([04 OPML](04-youtube.md#opml)): `Channel` → `kind = YOUTUBE`, `normalizedUrl` = canonical `https://www.youtube.com/feeds/videos.xml?channel_id={UC…}`, variants from `nd:ytVariants` > URL prefix hint > 1; `Handle`/`LegacyPath`/`Video` → `kind = YOUTUBE`, `normalizedUrl = null` (resolved by the worker, M8); `Playlist` → `YOUTUBE_UNSUPPORTED_YET`. Before M8 every YouTube item is `YOUTUBE_UNSUPPORTED_YET` ("YouTube channel — supported in a later build").
2. Otherwise 03's `AddInputNormalizer.normalize(url)` ([03 Input normalisation](03-feeds-and-discovery.md#input-normalisation)): `feed:`/`itpc:`/`pcast:`/`podcast:` schemes, scheme-less → `https://`, subscribe-page wrappers unwrapped; anything not http(s) → `INVALID_URL`. Userinfo stays in `originalUrl` until commit (the payload file holds it anyway); `normalizedUrl` never contains it.
3. Identity = `UrlNormalizer.forIdentity(normalizedUrl)` ([03 URL normalisation](03-feeds-and-discovery.md#url-normalisation)); an unresolved YouTube ref uses its lowercased handle or path as identity. Within the file, a repeated identity → `DUPLICATE_IN_FILE`, its group names unioned into the first entry (exports that repeat feeds per folder).
4. Already subscribed: identity against `podcast.feedKey` and `podcast_url_alias.url` → `ALREADY_SUBSCRIBED` with `podcastId`, unselected.
5. Title for display and for the pending row: Neutrodyne files: `title`, and `text` becomes `customTitle` when it differs; other files: `title ?: text ?: host` (gPodder writes the description into `text`).
6. Pre-selection: `PREVIEW` items are selected unless `preselect = false`. The preview never loads covers (10,000 rows would hit the network); rows show monograms.

### 4. Group mapping

[R1.2](../PLAN.md#21-functional-requirements). Each entry's group names = **`category` tokens ∪ {immediate parent folder}**, each normalised with `GroupNames` (`:feeds`) in `ImportClassifier` (empty dropped; > 40 code points truncated with a warning). A folder that only contains other folders is not a group (FreshRSS-style nesting maps to the innermost folder). **Empty folders:** in Neutrodyne files (`neutrodyne = true`) every folder becomes a proposal even without feeds, so empty groups and group order round-trip ([R1.4](../PLAN.md#21-functional-requirements)); in other files a folder without feeds is not proposed (Overcast's `playlists` holds only ignored outlines). Proposals are deduplicated by `nameKey`; the first-seen spelling wins unless an existing group has that key, whose name wins. Proposal order = folders in document order, then category-only names in order of first appearance; new groups are appended after existing groups in that order, each with `OrderKey.after(last)` (our grouped export therefore reproduces group order).

The parse step stores the ordered proposal list with its looks in `ImportOptions.sourceGroups` (session JSON), because empty folders, colours and icons are not visible in any `import_item`; `observeGroupProposals` combines it with member counts from `groupNamesJson` and the existing groups' `nameKey`s (02 forbids SQL inside JSON, so this runs in Kotlin).

Proposals excluded by default (the preview's group card shows each with a switch):

- **Wrapper folder.** Let T = top-level outlines that are feeds, or folders whose subtree contains a feed. The single folder in T is a wrapper when T has exactly one element, the file is not a Neutrodyne export, and either its `nameKey` ∈ {`feeds`, `subscriptions`, `podcasts`, `podcast feeds`} or it contains a sub-folder with feeds. Pocket Casts' `feeds` and Overcast's `feeds` (beside a feed-less `playlists`) are wrappers; a one-group share named "tech" is not. Shown as "'feeds' looks like a container, not a group".
- **gPodder default sections**: when `<head><title>` starts with "gPodder", folders named `audio`, `video` or `other` (gPodder derives them from content type, [opml.py](https://github.com/gpodder/gpodder)).

```kotlin
// :core:model (plain class; :core:data encodes it into import_session.optionsJson through a
// @Serializable mirror ImportOptionsJson, since :core:model has no serialization plugin)
data class ImportOptions(
    val importGroups: Boolean = true,                           // master switch "Import folders as groups"
    val excludedGroupKeys: Set<String> = emptySet(),            // wrapper and gPodder sections pre-filled
    val renamedGroups: Map<String, String> = emptyMap(),        // nameKey → new name (validated)
    val targetGroupId: Long? = null, val targetGroupName: String? = null,  // "Put everything into group…"
    val treatExistingAsPlayed: Boolean = false,                 // D66 toggle
    val notifyNewEpisodes: Boolean = false,                     // writes podcast_settings.notifyNewEpisodes = true
    val restoreMode: RestoreMode? = null,                       // backup sessions only
    val restoreCategories: Set<RestoreCategory> = emptySet(),
    val redownloadIds: List<Long> = emptyList(),                // backup sessions: episodes of `dl = true` lines (≤ 2,000)
    val fromAndroidBackup: Boolean = false,                     // session adopted from files/backup/auto-snapshot.zip (Android)
    val restoreRequestedAt: Long? = null,                       // backup sessions: restore() called; the host runs it (Restore algorithm)
    val linkedReach: LinkedRestoreReach? = null,                // backup sessions on a linked device, Replace only (Restore while linked)
    val laneAttempts: Int = 0,                                  // desktop: runs of the import-backup lane that ended in retry while online
    val sourceGroups: List<SourceGroup> = emptyList(),          // written at parse time, proposal order
    val committedAt: Long? = null, val attemptMarkerAt: Long? = null,   // worker bookkeeping
)
data class SourceGroup(val nameKey: String, val name: String, val colorArgb: Int?, val iconKey: String?,
                       val excludedReason: String?)       // pre-fills excludedGroupKeys
```

Neutrodyne exports: folder `nd:groupColor`/`nd:groupIcon` are applied to **newly created** groups only (existing groups keep their look). Formats without groups (NewPipe, Takeout, URL list) pre-fill the target group per 04's rule ([04 Pipeline rules for YouTube items](04-youtube.md#pipeline-rules-for-youtube-items-05-implements)). "Import OPML into this group" sets `targetGroupId` and `importGroups = false`.

### 5. Preview contract

`ImportKey(sessionId)` (`:feature:importexport`, visuals: [08 Screens](08-ui-ux.md#screens)) renders these models; everything survives process death because it is in `import_session`/`import_item`.

```kotlin
// :core:model
data class ImportSessionView(
    val id: Long, val sourceName: String?, val format: ImportFormat, val state: ImportState,
    val recoveredBySalvage: Boolean, val warnings: List<ImportWarning>, val options: ImportOptions,
    val counts: Map<ImportItemStatus, Int>, val selectedCount: Int, val youtubeCount: Int, val createdAt: Long,
)
data class ImportItemView(
    val ordinal: Int, val title: String, val host: String, val kind: ImportItemKind, val status: ImportItemStatus,
    val selected: Boolean, val selectable: Boolean, val groupNames: List<String>, val isPrivate: Boolean,
    val errorDetail: String?, val podcastId: Long?,
)
data class GroupProposal(val nameKey: String, val name: String, val memberCount: Int,
                         val existingGroupId: Long?, val included: Boolean, val excludedReason: String?)
data class ImportWarning(val code: String, val count: Int = 0, val detail: String? = null)  // JSON mirror in :core:data
enum class ImportItemFilter { ALL, NEW_ONLY, NOT_IMPORTED, NEEDS_ATTENTION }
```

Header: "142 podcasts and 3 YouTube channels in *antennapod-feeds-2026-10-01.opml*" plus warnings (`SALVAGED`, `IGNORED_OUTLINES`, `TRUNCATED_NAMES`, `LINKED_LISTS`). Rows: monogram, title, host only (never the full URL: tokens, N3), chips YouTube / Already subscribed / Duplicate / Invalid / Private feed (`PrivateFeedUrls.looksPrivate`). Controls: search, "Only new", select all/none, sticky "Subscribe to 139". Options: "Treat existing episodes as played (except the newest per podcast)" and "Notify me about new episodes for these podcasts", both off ([canonical defaults](#settings)); switching the second on requests `POST_NOTIFICATIONS` per [03's permission rule](03-feeds-and-discovery.md#new-episode-notifications), and it affects only episodes found after the first fetch (D66). `pagedItems(sessionId, filter)` maps the filter to statuses (table in [Session and item states](#session-and-item-states)) and pages 02's `ImportDao.pagedItems`; the search box narrows the stream with `PagingData.filter` on title and host (case-insensitive, in Kotlin; ≤ 10,000 rows). Proposals come from `ImportOptions.sourceGroups` ([4. Group mapping](#4-group-mapping)).

### 6. Commit

`confirm(sessionId)` ([R1.3](../PLAN.md#21-functional-requirements): pending podcasts in the library within 2 s for 300 feeds):

1. Outside any Room transaction but inside `CredentialCommitCoordinator.withCredentialCommit` (03; held through step 3's commit chunks): for selected items whose `originalUrl` has userinfo, `SecretStore.put(origin, user, secret)` ([03 Basic auth and CredentialStore](03-feeds-and-discovery.md#basic-auth-and-credentialstore); Android Keystore, desktop per [PO-44](../PLAN.md#48-further-product-owner-decisions)).
2. Resolve the final group set: included proposals (renamed) when `importGroups`, plus the target group (created if `targetGroupName`).
3. Chunks of 500 items, each one write transaction (02's `ImportDao.commitChunk`): create missing groups (`nameKey` conflict = reuse; `uuid = Uuid.random()`, `orderKey = OrderKey.after(last)`; colour/icon per [Palette](#palette) or `nd:` attributes; `createdAt = now`, `lastViewedAt = now`); insert `PENDING_FIRST_FETCH` podcasts with a new `syncId` (M1 acceptance 11), `initialFetch = 1`, `nextRefreshAt = now`, `subscribedAt = now`, `title`, `customTitle`, `artworkKey = ArtworkKeys.monogram(feedKey)` ([08 Keys and versions](08-ui-ux.md#keys-and-versions)), `credentialId`, YouTube columns; aliases (reason `IMPORT`) for an original identity that differs from `feedKey`; memberships with `orderKey = OrderKey.after(last)` for new **and** already-subscribed items (M3 acceptance 7); `podcast_settings.notifyNewEpisodes = true` when the option is on; item → `QUEUED` with `podcastId`; `originalUrl` rewritten to `Redactor.url(originalUrl)` (01) so no password stays in the table.
4. `options.committedAt = attemptMarkerAt = now`; state `COMMITTED`; `ImportWorkScheduler.schedule(sessionId)` (Android: unique work `import-{sessionId}`; desktop: pokes the `import-backup` lane); `RefreshController.reschedulePeriodic()`; `GroupChannelSync.sync()`.
5. Items needing YouTube resolution (M8) stay `QUEUED` without `podcastId`; the fetch runner inserts them ([04 Pipeline rules](04-youtube.md#pipeline-rules-for-youtube-items-05-implements)).

**Sync.** While a server is linked, the commit is captured like any local edit by 02's triggers — podcasts with every field, new groups, memberships: about 1,000 outbox rows for 300 feeds, one or two push rounds ([10 Outbox and coalescing](10-sync.md#outbox-and-coalescing)). Other devices receive the podcasts as pending and fetch them themselves (`import-sync` on Android, the refresh lane on the desktop, [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)); the server never fetches. Fix-ups (Edit URL, Remove) propagate the same way.

### 7. Fetch

`ImportFetchRunner` (`:core:data` `commonMain`) holds the loop below; two hosts run it, and nothing else differs between the platforms.

**Android:** `ImportFetchWorker` (`:core:data` `androidMain`, created by `MetroWorkerFactory`): unique work `import-{sessionId}`, tag `import`, constraint `NetworkType.CONNECTED`, `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` **on API 31+ only** (below 31 expedited work runs as a foreground service and needs `getForegroundInfo()`, which crashes when missing; 03 made the same choice for `refresh-now`), backoff linear 30 s. It never runs as a foreground service. Policy `KEEP` for the enqueue at commit (canonical); the fix-up actions of [8. Report and fix-ups](#8-report-and-fix-ups) enqueue with `APPEND_OR_REPLACE`, so a request that arrives while a worker is finishing runs after it instead of being dropped (the extra run ends at once when nothing is pending). Origin is `RESTORE` for backup sessions, else `IMPORT`; `attempt` = `runAttemptCount`.

**Desktop:** the `import-backup` lane (`DesktopImportBackupLane`, `:core:data` `desktopMain`, [11 Background work](11-desktop.md#background-work)) runs the loop for every session in `COMMITTED` or `FETCHING`, oldest first, without a soft deadline (`deadline = null`). It returns early when `NetworkMonitor` reports offline (the lane is poked again when the network returns) or when the refresh engine is busy (`stoppedByDeadline`: the next tick, at most 60 s later, or the end of the refresh lane's run pokes it). `attempt` = `ImportOptions.laneAttempts`, incremented when a run ends in `retry` while online. `confirm` and every fix-up poke the lane through `LaneImportWorkScheduler`; a quit stops it, and the next start resumes from the persisted session ([11 Wake and restart catch-up](11-desktop.md#wake-and-restart-catch-up)).

```
ImportFetchRunner.run(sessionId, attempt, deadline):    // deadline: Android elapsedRealtime + 8 min; desktop none
  session = load; if state ∉ {COMMITTED, FETCHING} → success
  state = FETCHING
  if attempt ≥ 20 → every QUEUED item → FETCH_FAILED("NOT_ATTEMPTED"); go to finish
  (M8) resolve QUEUED YouTube items without podcastId: YouTubeChannelResolver.resolve(ref, MetadataDepth.ID_ONLY),
       2 concurrent, 0.5–1.5 s jitter; insert each podcast in its own transaction; failure → FETCH_FAILED
  loop:
    derive(): each QUEUED item whose podcast.lastAttemptAt ≥ options.attemptMarkerAt → final status (table below)
    pending = QUEUED items with podcastId and no attempt since the marker
    if pending empty → go to finish
    if deadline ≠ none and deadline − now < 30 s → return retry
    report = FeedRefresher.run(RefreshRequest(Podcasts(pending), force = true, pagesOnly = false,
                                              origin, deadlineElapsedMs = deadline))
    apply report.outcomes (and FeedRunEvents seen) → statuses
    if any Failed(OFFLINE) or report.stoppedByDeadline → return retry   // unattempted items stay QUEUED
finish:
  one write transaction: no item QUEUED → state = DONE, finishedAt = now; otherwise back to loop
  (a fix-up re-queued items meanwhile); after DONE: report notification, SnapshotScheduler.requestSoon() (Android)
```

`force = true` because import fetches are user-initiated; a periodic run that already ingested an imported podcast is detected by `derive()` (the attempt marker), so it is not fetched twice. 03's engine applies 6 parallel / 2 per host (also `youtube.com`), its process-wide mutex (a busy engine makes `run` return `stoppedByDeadline`, hence `retry`), `INITIAL` ingestion (no `isNew`, no notifications, no auto-download — M3 acceptance 4) and the pending-podcast rules ([03 Refresh of pending podcasts](03-feeds-and-discovery.md#refresh-of-pending-podcasts)).

Status derivation, from the `FeedOutcome` ([03 API](03-feeds-and-discovery.md#api)) or, when the outcome was not observed, from the persisted podcast row (`lastErrorKind` maps like the outcome's kind):

| Outcome / row | Status |
|---|---|
| `Ingested`, `NotModified`, `Unchanged`; row `status = ACTIVE` and `lastErrorKind IS NULL` | `SUBSCRIBED` |
| `Merged(intoPodcastId)`; row gone and the item's identity is an alias with reason `MERGE` | `MERGED` (`podcastId` = winner, updated by 02's merge) |
| `Failed(HTTP_AUTH)`; row `needsCredentials = 1` | `AUTH_REQUIRED` |
| `Failed(HTTP_GONE)`, `Failed(HTTP_NOT_FOUND)` for RSS; row `gone = 1` | `GONE` |
| `Failed(NO_MEDIA)` | `NO_MEDIA` |
| `Failed(NOT_A_FEED / PARSE_ERROR / UNSUPPORTED_LIST_FEED)` | `NOT_A_FEED` |
| `Deferred(untilMs)` (04: YouTube feeds paused for an outage or rate limit; not attempted, `nextRefreshAt = untilMs`) | `FETCH_FAILED`, `errorDetail = "DEFERRED"`; the periodic refresh loads the channel later, still `INITIAL` |
| `Failed(OFFLINE)`, or no outcome (call cancelled by the deadline or a stop) | stays `QUEUED` |
| any other `Failed(kind)` (incl. YouTube 404, which 03 treats as transient) | `FETCH_FAILED`, `errorDetail` = kind name |

**Treat existing as played** ([D66](../PLAN.md#3-key-decisions)): in the same transaction that sets `SUBSCRIBED`, when the option is on, run 02's "played except newest" pair for that podcast restricted to `e.isNew = 0` ([02 User-state writes](02-data-model.md#user-state-writes); a genuinely new episode found by a non-initial refresh meanwhile stays unplayed), and delete those episodes from `queue_entry` (06's invariant: every path that marks episodes played removes them from Up next, [06 Queue and play context](06-playback.md#queue-and-play-context)). The status transition happens once, so this runs at most once per podcast.

### 8. Report and fix-ups

The progress screen observes `ImportDao.observeProgress` ("Fetched 87 of 139"). At `DONE` the runner posts one notification, "139 added, 3 need attention", whose click opens `neutrodyne://open/import/{sessionId}` through 01's `IntentRouter` ([01 Intent routing](01-foundation.md#intent-routing)); it is skipped while that session's screen is visible. Android: channel `import_backup` (ID 3001, tag `import-{sessionId}`), skipped when `POST_NOTIFICATIONS` is not granted (never requested for imports). Desktop: `DesktopNotifier` through the OS notification centre ([11 Notifications](11-desktop.md#notifications)).

| Action | Effect |
|---|---|
| Retry | `PodcastRepository.retry(podcastId, refresh = false)` (03: clears `gone`/`needsCredentials`/backoff without its own refresh), items → `QUEUED`, `attemptMarkerAt = now`, state `FETCHING`, `ImportWorkScheduler.schedule` (Android `import-{sessionId}` with `APPEND_OR_REPLACE`; desktop: lane poke) |
| Edit URL | `PodcastRepository.editFeedUrl(podcastId, input)` (03 applies a move and ingests); success → `SUBSCRIBED` (or `MERGED`), failure → error shown inline |
| Enter password | `PodcastRepository.setCredentials(podcastId, credentials)` (03 probes, stores and refreshes the podcast); `AuthRequired` → error inline; success → item `QUEUED`, `attemptMarkerAt = now`, `ImportWorkScheduler.schedule` as for Retry; whichever fetch runs first sets `lastAttemptAt`, and `derive()` then finalises the item without a second fetch |
| Remove / Remove all | `UnsubscribeUseCase(ids)` (03) for podcasts this session created, then delete those `import_item` rows; not offered for `ALREADY_SUBSCRIBED` or `MERGED` items |
| Re-download (backup sessions, M6) | see [After restore](#after-restore) |

### 9. Session lifetime

`PREVIEW` cancel deletes the session and the payload immediately. 02's `db-maintenance` step 3 deletes sessions in `DONE`, `CANCELLED` or abandoned `PREVIEW` with `COALESCE(finishedAt, createdAt)` older than 7 days and their payloads ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker)). Until `db-maintenance` ships (M11b), `AutoSnapshotWorker` (Android, as its first step) and the `import-backup` lane (desktop, once per 24 h) run the same `ImportDao` cleanup (M3). `ExternalImportActivity` sessions created after the user left get a "ready to review" notification (ID 3002, Android) instead of being lost ([Receiving files](#receiving-files)); the desktop needs none, because its inputs reach the running window directly.

---

## Other import formats

Serves R1.6, R1.7, R8.1. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) on both platforms (sniffing, backups, YouTube reported as unsupported) and [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) (YouTube formats). Format specifications and parsers: [04 Import and export formats](04-youtube.md#import-and-export-formats); this section is how they enter the pipeline.

### Sniffing

`ImportSourceSniffer.sniff(open: () -> okio.Source, archives: ArchiveReaderFactory): SniffResult` (`:feeds`, common) checks the magic bytes first, then reads at most the first 64 KiB as text: a UTF-8 BOM is skipped; a UTF-16 BOM, or first bytes `3C 00`/`00 3C` without BOM, decode the text as UTF-16 LE/BE (only XML is accepted in UTF-16); leading whitespace is skipped and the first non-whitespace character decides. ZIPs are inspected through their central directory by the bound `ArchiveReaderFactory` ([Archive guard](#archive-guard)):

| First bytes / content | Result |
|---|---|
| `PK\x03\x04` | ZIP: an entry named exactly `manifest.json` whose `format` is `neutrodyne-backup` → `NEUTRODYNE_BACKUP`; else any entry ending `.csv` (case-insensitive) → `TAKEOUT_CSV`; else `Unsupported("zip")` |
| `1F 8B` (gzip) | `Unsupported("tgz")` with 04's message ("choose the .zip file type") |
| `{` | JSON (≤ 10 MB): top-level `subscriptions` array whose objects have `service_id` → `NEWPIPE_JSON`; `localSubscriptions`, `groups`/`channelGroups` or `"format":"Piped"` → `LIBRETUBE_JSON`; else `Unsupported("json")` |
| `<` | XML: first element `opml` → `OPML`; `rss`, `feed`, `RDF` → `Unsupported("single_feed")` ("This is a podcast feed, not a list"; for an `https://` source a "Subscribe" action hands the URL to `AddPodcastKey`); otherwise, when `<outline` occurs → `OPML` (salvage) |
| text, first line 3 comma-separated columns, second line starts `UC` | `TAKEOUT_CSV` |
| text, ≥ 80 % of non-empty non-`#` lines are URLs, `UC…` IDs or `@handles` | `URL_LIST` (M8; [04 URL list](04-youtube.md#url-list-import)) |
| anything else | `Unsupported("unknown")` → "This doesn't look like a podcast list" |

### Adapters

Every parser yields `ImportEntry`s that go through [Classify](#3-classify), [Group mapping](#4-group-mapping), preview, commit and fetch unchanged.

```kotlin
// :feeds (common) — ch.lkmc.neutrodyne.feeds.importing
data class ImportEntry(val ordinal: Int, val url: String, val title: String?, val customTitle: String?,
                       val htmlUrl: String?, val groupNames: List<String>, val preselect: Boolean,
                       val youtubeVariantsHint: Int?)
data class ImportGroup(val name: String, val colorArgb: Int?, val iconKey: String?,  // raw name, document order
                       val excludedReason: String? = null)   // "wrapper", "gpodder_section" (Group mapping)
data class ImportDocument(val format: String, val entries: List<ImportEntry>, val groups: List<ImportGroup>,
                          val salvaged: Boolean, val warnings: List<String>)
object ImportDocuments {                    // pure adapters; OPML applies the folder rules of Group mapping here
    fun fromOpml(doc: OpmlDocument): ImportDocument
    // fromNewPipe / fromLibreTube / fromTakeout / fromUrlList wrap 04's parser DTOs (YouTubeImportEntry) the same way
}
```

| `ImportFormat` | Parser (spec owner) | Groups | Notes |
|---|---|---|---|
| `OPML` | `OpmlReader` (here) | folders ∪ `category` | [OPML import](#opml-import) |
| `NEWPIPE_JSON` | `NewPipeSubscriptions.parse` (04) | none → target group "YouTube" pre-filled | other `service_id`s → `INVALID_URL` (`newpipe_service:{id}`) |
| `LIBRETUBE_JSON` | `LibreTubeBackupParser` (04) | LibreTube groups, order = `index`; channels only listed in a group are imported too | names through `GroupNames` |
| `TAKEOUT_CSV` | `TakeoutSubscriptionsParser` (04), CSV or ZIP | none → "YouTube" pre-filled | RFC 4180; localised header skipped |
| `URL_LIST` (M8) | `UrlListParser` (04) | none → "YouTube" pre-filled only if every item is YouTube | ≤ 1 MB, ≤ 5,000 lines |
| `NEUTRODYNE_BACKUP` | `BackupCodec` | from the archive | creates a session without `import_item` rows (the restore adds them when it finishes) and routes to the restore preview ([Full backup and restore](#full-backup-and-restore)) |

### Archive guard

The archive container is JVM code (`java.util.zip`), so it sits in the `:feeds:jvm` island behind small common interfaces in `:feeds`, which `:core:data`'s `androidMain` and `desktopMain` bind to the island's classes ([D81](../PLAN.md#3-key-decisions)):

```kotlin
// :feeds (common) — ch.lkmc.neutrodyne.feeds.backup
data class ArchiveCaps(val maxEntries: Int, val maxEntryBytes: Long, val maxTotalBytes: Long,
                       val maxRatio: Int = 100, val ratioFloorBytes: Long = 16L shl 20)
fun interface ArchiveReaderFactory { fun open(path: okio.Path, caps: ArchiveCaps): ArchiveReader }   // throws ArchiveException
interface ArchiveReader : AutoCloseable {
    val entryNames: List<String>                     // central directory, names already validated
    fun open(name: String): okio.Source              // counting source; throws ArchiveException at a cap
}
fun interface ArchiveWriterFactory { fun create(path: okio.Path, deflateLevel: Int = 6): ArchiveWriter }
interface ArchiveWriter : AutoCloseable { fun entry(name: String): okio.Sink }   // one entry open at a time; close() ends the ZIP
class ArchiveException(val kind: Kind, val reason: String) : Exception(reason) { enum class Kind { HOSTILE, CORRUPT } }
// :feeds:jvm (JVM island) — ch.lkmc.neutrodyne.feeds.jvm.archive
class ZipGuard : ArchiveReaderFactory          // java.util.zip.ZipFile, random access over the local payload copy
class ZipArchiveWriter : ArchiveWriterFactory  // java.util.zip.ZipOutputStream, DEFLATE
```

`ZipGuard` wraps `java.util.zip.ZipFile` over the local payload copy (random access; never extracts to disk, so entry names are never paths — no zip-slip). Entry names containing `..`, starting with `/` or containing `\` reject the archive (`ArchiveException(HOSTILE, "zip_path")`, surfaced as `Hostile("zip_path")`); on devices the `ZipFile` constructor already throws `ZipException` for the first two (apps targeting 34+), which `ZipGuard` maps to the same error, so JVM tests, the desktop and Android devices agree. Any other `ZipException` → `ArchiveException(CORRUPT)`, surfaced as `Corrupt("zip")` (backups) or `Unsupported("zip")`. The sync server reads and writes its per-account ZIPs with its own `java.util.zip` code and the same `BackupCodec`, because it may depend only on `:sync:protocol` and `:feeds` ([PLAN 5.1](../PLAN.md#51-module-graph) rule 7).

| Cap | Backup | Takeout ZIP |
|---|---|---|
| Entries | ≤ 16 in the central directory (more → `Hostile("zip_entries")`); only whitelisted names are read | ≤ 10,000 in the central directory; ≤ 50 `.csv` entries scanned |
| Uncompressed per entry | ≤ 128 MiB | ≤ 5 MB |
| Uncompressed total of the entries read | ≤ 256 MiB | ≤ 250 MB (non-CSV entries are never read) |
| Ratio | an entry whose inflated bytes exceed 100 × its compressed size and 16 MiB aborts | same |

Declared sizes in the central directory are not trusted: every entry is read through a counting source that throws `ArchiveException(HOSTILE, "zip_bomb")` at the cap (M3 acceptance 3: zip bomb and zip-slip names rejected without crash or OOM).

---

## Full backup and restore

Serves R1.7, R1.9, R7.6, R7.9, R8.11, N1, N9. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) on both platforms; re-download offer in [M6](../PLAN.md#m6-downloads); the link policies of `RestoreMerger` and [Restore while linked](#restore-while-linked) in [MS2](../PLAN.md#ms2-client-sync). Honours [D33](../PLAN.md#3-key-decisions) (amended 2026-10-05), [D66](../PLAN.md#3-key-decisions), [D92](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions). Export and matching SQL: [02 Backup export](02-data-model.md#backup-export), [02 Restore matching](02-data-model.md#restore-matching).

### Archive

File `neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip`, MIME `application/zip`, DEFLATE level 6. Everything is keyed by stable identities — `feedKey` (+ aliases, real `podcastGuid`, optional `syncId`), group `uuid`, episode `identityKey` with `kv` — never by row IDs ([02 Local row IDs](02-data-model.md#local-row-ids)). Android, the desktop and the sync server (its per-account ZIPs, [10 Backups](10-sync.md#backups)) write the same format.

| Entry | Content | Manual | Snapshot | Required on read |
|---|---|---|---|---|
| `library.json` | podcasts (identity, user fields, aliases, settings), groups (look, view prefs, settings, members), optional credentials | yes | yes | yes |
| `episodes.jsonl` | one line per episode with user state, queued or current: stub fields + state | yes | yes (size guard) | yes (may be empty) |
| `queue.json` | Up next refs and the play session | yes | yes | yes |
| `settings.json` | whitelisted portable settings with types | yes | yes | yes |
| `subscriptions.opml` | grouped OPML, no passwords — usable by other apps | yes | no | no |
| `manifest.json` | format, versions, counts, per-entry size + SHA-256 + records | yes (last) | yes (last) | yes |

The manifest is written last because it carries the hashes; readers open the local payload copy with `ZipFile` (random access), so position does not matter.

```kotlin
// :feeds — ch.lkmc.neutrodyne.feeds.backup. Json: write encodeDefaults = false, explicitNulls = false;
// read ignoreUnknownKeys = true; no polymorphic type chosen by the file.
@Serializable data class BackupManifest(
    val format: String = "neutrodyne-backup", val formatVersion: Int = 1, val minReaderVersion: Int = 1,
    val createdAt: String,                                   // ISO-8601 UTC (for humans)
    val kind: BackupKind, val app: AppInfoV1, val dbSchemaVersion: Int,
    val episodeKeysVersion: Int, val urlNormalizerVersion: Int, val installationId: String,
    val snapshotLevel: Int = 0, val includesCredentials: Boolean = false,
    val counts: CountsV1, val entries: List<EntryInfo>,
)
@Serializable enum class BackupKind { MANUAL, AUTO_SNAPSHOT, SCHEDULED }
@Serializable data class AppInfoV1(val versionName: String, val versionCode: Long,
                                   val abi: String,          // Android BuildInfo.apkAbi, desktop "<os>-<arch>", server "server"; informational
                                   val platform: String? = null)   // "android", "windows", "macos", "linux", "server" (2026-10-05); informational
@Serializable data class CountsV1(val podcasts: Int, val groups: Int, val episodeLines: Int, val played: Int,
                                  val inProgress: Int, val upNext: Int, val settings: Int)
@Serializable data class EntryInfo(val name: String, val bytes: Long, val sha256: String, val records: Int)
@Serializable data class LibraryV1(val podcasts: List<PodcastV1>, val groups: List<GroupV1>,
                                   val credentials: List<CredentialV1> = emptyList())
@Serializable data class PodcastV1(
    val key: String, val feedUrl: String, val source: String = "RSS",         // feedKey; SourceType name
    val aliases: List<String> = emptyList(), val podcastGuid: String? = null,  // real GUIDs only
    val youtubeChannelId: String? = null, val youtubeVariants: Int? = null,
    val title: String, val customTitle: String? = null, val artworkUrl: String? = null, val link: String? = null,
    val subscribedAt: Long, val includeInAll: Boolean = true, val episodeOrder: String? = null,
    val settings: OverridesV1? = null, val credentialOrigin: String? = null,   // set when it had a credential
    val syncId: String? = null,                                                // 2026-10-05, optional (D33): adopted on restore when unused
    val guidAmbiguous: List<String>? = null,                                   // 2026-10-10, optional (D98): the feed's KNOWN_AMBIGUOUS GUIDs, canonical
)
@Serializable data class GroupV1(
    val uuid: String, val name: String,
    val sortOrder: Int,                                       // rank in group order, still written for older readers
    val orderKey: String? = null,                             // 2026-10-05, optional: the group's OrderKey
    val colorArgb: Int? = null, val iconKey: String? = null,
    val kind: String = "MANUAL", val rule: JsonElement? = null,
    val feedOrder: String = "NEWEST_FIRST", val playOrder: String = "NEWEST_FIRST", val filterFlags: Int = 0,
    val mediaFilter: String = "ALL", val hideOlderThanDays: Int? = null, val showAsTab: Boolean = true,
    val createdAt: Long? = null, val settings: OverridesV1? = null, val members: List<MemberV1> = emptyList(),
)
@Serializable data class MemberV1(val podcastKey: String, val addedAt: Long? = null,
                                  val orderKey: String? = null)              // 2026-10-05, optional
@Serializable data class CredentialV1(val origin: String, val username: String, val password: String)
// OverridesV1: the 13 SettingOverrides fields, all nullable, enums by name
```

```kotlin
@Serializable data class EpisodeLineV1(                       // one line of episodes.jsonl
    val p: String, val k: String, val kv: Int = 1,            // podcast key, identityKey, EpisodeKeys version
    val g: String? = null, val t: String? = null, val d: Long? = null,      // guid, title, pubDate
    val u: String? = null, val ty: String? = null, val dur: Long? = null,   // enclosure URL, type, duration hint
    val yt: String? = null, val l: String? = null,                          // YouTube video ID, link
    val pl: Long? = null, val pc: Int = 0, val st: Long? = null,            // playedAt, playCount, startedAt
    val lp: Long? = null,                                                   // lastPlayedAt (2026-10-05, optional)
    val pos: Long? = null, val posAt: Long? = null, val posSrc: String? = null,
    val fav: Boolean = false, val dis: Long? = null,                        // favourite, downloadDismissedAt
    val dl: Boolean = false, val md: Long? = null,                          // was downloaded, measuredDurationMs
    val ts: Long,                                                           // last user-state change
    val fc: Map<String, Long>? = null,       // 2026-10-06, optional: staging only (10), clock ms per state field the record has
)
@Serializable data class EpisodeRefV1(val p: String, val k: String, val kv: Int = 1)
@Serializable data class UpNextRefV1(val p: String, val k: String, val kv: Int = 1,
                                    val ok: String? = null)   // 2026-10-05: the entry's OrderKey; JSON-compatible with EpisodeRefV1
@Serializable data class QueueV1(val upNext: List<UpNextRefV1>, val session: SessionV1? = null)
@Serializable data class SessionV1(
    val current: EpisodeRefV1? = null, val contextType: String? = null,
    val contextGroupUuid: String? = null, val contextPodcastKey: String? = null,
    val order: String = "NEWEST_FIRST", val filterFlags: Int = 0, val mediaFilter: String = "ALL",
    val minSortDate: Long? = null, val anchor: EpisodeRefV1? = null, val anchorSortDate: Long? = null,
)
@Serializable data class SettingsV1(val values: List<SettingValueV1>)
@Serializable data class SettingValueV1(val key: String, val type: String,   // SettingKey subclass name
                                        val value: JsonPrimitive? = null, val values: List<String>? = null)
```

**Line timestamps (`ts`, `posAt`, `pl`, `lp`, `fc`).** `ts` is the last user-state change (`episode_state.updatedAt`); `ts = 0` is the no-state-change sentinel for lines exported only as queue, download, current-episode or stub hints, which have no `episode_state` row (02 writes no NULL into the required field and never synthesises now). Such a line carries no state: `pl`, `st`, `lp`, `dis`, `md` are null and `pc = 0`, `fav = false`. `posAt` is independent (`episode_position.updatedAt`): a line with `ts = 0` but `pos > 0` still merges its position by the position rule. Expected combinations: `pl = null` with `pos > 0` (and usually `st` set) is in progress, never played. In a restore **Merge** a null `pl` never clears a locally played mark (played merges by OR/max); Replace takes the backup's state, and the First-link column (`LINK_MERGE`) decides by clock, so there a newer remote "unplayed" does clear it ([Merge and Replace rules](#merge-and-replace-rules), MS2 acceptance 3). On merge, a `ts = 0` line writes no state fields and captures none — but its stub (unmatched, with `u` or `yt`), its queue reference and its position are still honoured per the rules below: when the merged position is non-zero and the merged result unplayed, the in-progress proxy is derived with `EpisodeStateDao.ensure(id, posAt)` and `startedAt = COALESCE(startedAt, posAt)` — stamped `posAt`, as 06's position save would have stamped it, never now and never 0; an existing row keeps its `updatedAt` ([02 Restore matching](02-data-model.md#restore-matching)). `fc` (2026-10-06) exists only in 10's first-link staging files, never in archives: the clock milliseconds of each state field the server record actually carries (`played`, `fav`, `playCount`, `lastPlayedAt`, `measuredDurationMs`, `pos`), so the First-link column compares a field's own clock and leaves a field the record lacks untouched. A staged line's `ts` is the newest of its non-`pos` state clocks, or 0 when the record has none of them (only `pos` or an Up next reference); the server's per-account ZIPs use the same `ts` mapping without `fc` ([10 Merge](10-sync.md#merge) step 2, [10 Backups](10-sync.md#backups)).

Not in any backup, by design: episodes without user state (re-fetched), show notes, chapters, artwork, download files and rows, refresh state and validators, `lastViewedAt`, import history, `device_settings` (including the YouTube engine's and the update check's device state), the YouTube engine's installed versions (`noBackupFilesDir/ytdlp/`, re-fetched by engine updates, [04 Engine updates](04-youtube.md#engine-updates)), the update check's cache (`noBackupFilesDir/updates/last-check.json`, rebuilt by the next check), credentials unless the user opts in (manual backups only), and every piece of sync state — `sync_state`, the outbox, clocks, parked and held rows and the sync token (database rows and `SecretStore` entries, never archive entries; [Restore while linked](#restore-while-linked)). `device_settings` also holds sync's and the desktop's device keys (`sync.device_name`, `desktop.*`), so none of them travels.

**Across platforms** ([R1.7](../PLAN.md#21-functional-requirements), [R8.11](../PLAN.md#21-functional-requirements), M3 acceptance 11). An archive holds identities and user state, never paths, row IDs, download files or device keys, so a backup made on Android restores on the desktop and vice versa with identical groups, memberships, order, played state, positions and Up next. Restored settings are preferences: a key without meaning on the target (`playback.pause_for_navigation` or `backup.auto_snapshot_enabled` on the desktop) is stored and ignored ([Settings whitelist](#settings-whitelist)); opted-in credentials are stored through the target's `SecretStore`. A sync server's per-account ZIP restores like any other (`AppInfoV1.abi = "server"`, never credentials).

A backup does not depend on the ABI or capabilities of the app that wrote it (`AppInfoV1.abi` and `platform` are informational): a backup from a 64-bit APK restores completely on the `armeabi-v7a` APK or with in-app YouTube off. YouTube channels, their user state and Up next references are restored there too; 06 keeps restored YouTube Up next rows but never projects them in external mode, exactly as after a capability change ([06 YouTube branch](06-playback.md#youtube-branch)), and the re-download offer skips YouTube lines ([After restore](#after-restore)).

### Versioning

- Adding an optional field with a default never bumps `formatVersion`; readers ignore unknown keys.
- Scope revision 2026-10-05: `PodcastV1.syncId`, `GroupV1.orderKey`, `MemberV1.orderKey`, `UpNextRefV1.ok`, `EpisodeLineV1.lp` and `AppInfoV1.platform` are such fields, so `formatVersion` and `minReaderVersion` stay 1 ([D33](../PLAN.md#3-key-decisions)); so is `EpisodeLineV1.fc` (2026-10-06), which only 10's staging files carry, and `PodcastV1.guidAmbiguous` (2026-10-10, [D98](../PLAN.md#3-key-decisions)). Writers keep `sortOrder` as the rank in group order and keep Up next in list order; readers that predate the fields restore by rank and list order, and current readers assign fresh `OrderKey`s in that order when a file lacks them. An archive that lacks `guidAmbiguous` restores no provenance: every GUID of the restored podcast stays `UNKNOWN` — never independent — so only ambiguity survives a round trip and a file can declare it but never full coverage ([02 episode_guid_provenance](02-data-model.md#episode_guid_provenance)).
- A change of meaning or a new required entry bumps `formatVersion`; `minReaderVersion` is the oldest reader that restores the file correctly (it stays 1 when old readers can safely ignore the change).
- A reader restores every `formatVersion` up to its own and refuses `minReaderVersion > supported` with "This backup was made by a newer version of Neutrodyne. Update the app to restore it."
- Episode-key changes are handled per line by `kv` ([02 Key versions](02-data-model.md#key-versions)); a line with `kv` newer than the app's `EpisodeKeys.VERSION` matches by enclosure URL or guid only, and its stub keeps the foreign key (prefixed by version, so it cannot collide). `dbSchemaVersion` is informational.

### Writing

`BackupWriter.write(kind, target: okio.Path, includeCredentials: Boolean)` (`:core:data` `commonMain`, holds the backup mutex; the ZIP container is the bound `ArchiveWriterFactory`, [Archive guard](#archive-guard)):

1. Read the portable settings once: `SettingsRepository.observePortableSnapshot().first()` filtered by the [whitelist](#settings-whitelist).
2. Open one read transaction ([02 Backup export](02-data-model.md#backup-export)): read podcasts (with `syncId`), aliases, settings rows, groups and members (with their `orderKey`s; `sortOrder` = rank in group order), queue (with `orderKey`) and session in full; stream episode lines in keyset chunks of 1,000 into the `episodes.jsonl` entry of the `ArchiveWriter` on `target.tmp` (a local file; no `ContentResolver` or other platform I/O inside the transaction), each entry through an Okio `HashingSink.sha256` and a byte/record counter. The archive is a point-in-time snapshot (WAL reader isolation).
3. After the transaction: `library.json` (credentials read through `SecretStore` only when opted in; `credentialOrigin` is always written so a restore can flag `needsCredentials`), `queue.json`, `settings.json`, `subscriptions.opml` (manual only, from the in-memory library), then `manifest.json`.
4. Close, flush to disk, atomic move `target.tmp` → `target` (Okio `FileSystem.atomicMove`).
5. Manual backups: `target` = `<cache>/export/neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip`; then, through `ExportDestination` as for OPML ([Destinations](#destinations)), Android copies it to the `CreateDocument("application/zip")` destination with `"wt"` and the desktop replaces the file chosen in the save dialog atomically; delete `target`, set `backup.last_manual_backup_at`. Snapshots: see [Auto Backup](#auto-backup) (Android).

Write order is therefore `episodes.jsonl` (inside the read transaction), `library.json`, `queue.json`, `settings.json`, `subscriptions.opml`, `manifest.json`. The "Create backup" flow (`BackupKey`): switch "Include passwords for private feeds" (off every time); then `preflight(includePasswords)`, and when it reports private links or passwords the same warning dialog as [OPML export](#options-and-warnings) ("This backup contains private access links for N feeds…", "…and N passwords in plain text.", [R1.9](../PLAN.md#21-functional-requirements)); then the `CreateDocument` launcher (Android) or the save dialog (desktop: File › Back up library…, 11); then `createBackup(uri, includePasswords)` on `@ApplicationScope`.

### Validation and preview

A backup arrives through the import pipeline ([Sniffing](#sniffing) → `NEUTRODYNE_BACKUP` session, no items). `BackupRepository.inspect(sessionId)` validates with `ZipGuard` caps, then:

1. Only whitelisted entry names are read; others are ignored (warning).
2. `manifest.json` (≤ 1 MiB): `format` must be `neutrodyne-backup` (`NotABackup`), `minReaderVersion` check (`NewerFormat`).
3. Every entry in `manifest.entries` must exist with the stated size and SHA-256 (`Corrupt(entry)`); required entries must be present.
4. `library.json` ≤ 32 MiB; `episodes.jsonl` is streamed, lines > 64 KiB or undecodable are skipped and counted (warning, not fatal).
5. Matching is dry-run to compute what Replace would remove.

```kotlin
// :core:domain — canonical interface, owned here
interface BackupRepository {
    suspend fun createBackup(destinationUri: String, includePasswords: Boolean): Outcome<BackupSummary, BackupError>
    suspend fun inspect(sessionId: Long): Outcome<BackupPreview, BackupError>
    suspend fun restore(sessionId: Long, request: RestoreRequest): Outcome<Unit, BackupError>   // schedules RestoreRunner
    fun observeRestore(): Flow<RestoreProgress?>
    fun observeSnapshotStatus(): Flow<SnapshotStatus>                         // desktop: supported = false
    suspend fun setSnapshotEnabled(enabled: Boolean)                          // Android; desktop: no-op
    suspend fun preflight(includePasswords: Boolean): PrivacyCounts            // R1.9 warning before createBackup
    suspend fun restoreAndroidBackup(): Outcome<Long, BackupError>              // Android: foreign snapshot → restore session id;
                                                                                // desktop: Unsupported
    suspend fun discardAndroidBackup()                                          // Android: foreign snapshot → restored-{ts}.zip
    suspend fun writeSnapshotNow(): Outcome<SnapshotStatus, BackupError>        // Android: SnapshotNowReceiver (shell, bmgr) and the
                                                                                // debug build's "Write snapshot now"; desktop: Unsupported
}
// :core:model
enum class RestoreMode { MERGE, REPLACE }
enum class RestoreCategory { HISTORY, UP_NEXT, SETTINGS }        // subscriptions and groups are always restored
enum class LinkedRestoreReach { THIS_DEVICE, ALL_DEVICES }        // Replace on a linked device (Restore while linked)
data class RestoreRequest(val mode: RestoreMode, val categories: Set<RestoreCategory>,
                          val linkedReach: LinkedRestoreReach = LinkedRestoreReach.THIS_DEVICE)   // ignored unless REPLACE while linked
data class BackupPreview(
    val createdAt: Long, val appVersionName: String, val automatic: Boolean, val podcasts: Int, val groups: Int,
    val played: Int, val inProgress: Int, val upNext: Int, val settings: Int, val includesCredentials: Boolean,
    val localPodcastsNotInBackup: Int, val localGroupsNotInBackup: Int, val warnings: List<ImportWarning>,
    val linkedServerHost: String?,                // non-null while this device is linked to a sync server
    val writtenOn: String?,                       // AppInfoV1.platform, for "made on Android" in the preview
)
data class BackupSummary(val bytes: Long, val podcasts: Int, val episodeLines: Int)
sealed interface RestoreProgress {
    data class Running(val phase: String, val done: Int, val total: Int) : RestoreProgress
    data class Finished(val sessionId: Long, val auto: Boolean) : RestoreProgress
    data class Failed(val error: BackupError) : RestoreProgress
}
data class SnapshotStatus(val supported: Boolean,               // false on the desktop (no Auto Backup)
                          val enabled: Boolean, val lastWrittenAt: Long?, val bytes: Long?, val level: Int,
                          val lastError: String?,
                          val foreignPending: Boolean)   // a snapshot from another installation waits (First-launch restore)
data class PrivacyCounts(val privateLinks: Int, val passwords: Int)
```

Preview text: "Backup from 4 Oct 2026 (Neutrodyne 1.3): 142 podcasts, 6 groups, 3,214 played episodes, 12 in Up next, settings." Checkboxes: Listening history, Up next, Settings (Merge default: history and Up next on, settings off; Replace: all on). Mode: **Merge** (default, [canonical defaults](#settings)) or **Replace**; Replace requires a confirmation naming its effect ("Removes 9 podcasts and 2 groups that are not in the backup, and replaces your listening history"). Confirming Replace first calls `PlaybackController.pause()` from the feature layer. A backup made on the other platform says so ("Backup from 4 Oct 2026, made on Android"). While the device is linked the preview adds "This device syncs with sync.example.org", and Replace asks how far it reaches ([Restore while linked](#restore-while-linked)).

### Restore algorithm

`RestoreRunner` (`:core:data` `commonMain`) runs the steps below and leaves every row decision to `RestoreMerger` ([RestoreMerger](#restoremerger)). `restore()` stores mode, categories and reach in the session's `ImportOptions`, sets `restoreRequestedAt` and schedules a host through `ImportWorkScheduler`:

- **Android:** `RestoreWorker` (`:core:data` `androidMain`): unique work `backup-restore`, policy `KEEP` (a second request while one runs returns `BackupError.RestoreRunning`), no constraints (local work), tag `backup`, input `sessionId` and `auto`.
- **Desktop:** the `import-backup` lane runs the oldest session whose `restoreRequestedAt` is set and that is not yet `COMMITTED`, one at a time (a second request while one is pending returns `RestoreRunning`); a quit stops it, and the next start runs it again from step 1.

Every step is idempotent, so a host may re-run the whole restore from step 1 after process death, a quit or a system stop; a full restore at the N5 scale is expected to take well under a minute (measured by `BackupRoundTripTest` with 02's `SeedDatabase`), so the runner does not checkpoint inside a run.

```mermaid
sequenceDiagram
  participant W as RestoreRunner (worker or desktop lane)
  participant C as BackupCodec over ArchiveReader
  participant M as RestoreMerger
  participant DB as Room
  participant CS as SecretStore (03)
  participant IW as ImportFetchRunner
  W->>C: validate payload again (caps, hashes)
  opt linked, REPLACE, this device only
    W->>W: unlink first (10), so nothing below is captured
  end
  opt REPLACE
    M->>DB: unsubscribe local podcasts not in backup (UnsubscribeUseCase), delete local-only groups
  end
  opt backup includes credentials
    M->>CS: put(origin, user, secret)
  end
  M->>DB: transaction A, library (groups, podcasts, aliases, settings, members)
  M->>DB: transactions B, 1,000 episode lines each (match or stub, merge state)
  M->>DB: transaction C, Up next and session
  M->>M: settings through SettingsRepository
  opt linked MERGE
    M->>DB: captureAt with the backup's timestamps inside transactions A to C
  end
  W->>DB: import_item per backup podcast, session COMMITTED
  W->>IW: schedule import-sessionId (pending podcasts, initialFetch)
  opt linked, REPLACE, all synced devices
    W->>W: Use this device's library everywhere (10)
  end
```

1. **Validate** again (the cache copy is ours, but cheap to re-check); failure → session `CANCELLED`, `RestoreProgress.Failed`, notification (ID 3003 on Android; `DesktopNotifier` on the desktop). For `auto` restores (Android) a `Corrupt` or `NotABackup` snapshot is renamed to `restored-{epochMs}.zip` as well, so the installation-ID guard of [Snapshot production](#snapshot-production) stops protecting a file that can never be restored; `NewerFormat` keeps it ([First-launch restore](#first-launch-restore)).
2. **Replace pre-step** (skipped on an empty database): on a linked device with reach `THIS_DEVICE`, 10's unlink runs first ([10 Unlink and Delete my data](10-sync.md#unlink-and-delete-my-data)); with reach `ALL_DEVICES` (confirmed after 10's pull, with `linkedAt` already cleared, [Restore while linked](#restore-while-linked)) every write of steps 2–7 runs with `applying = 1`. Then local podcasts matched by no backup podcast → `UnsubscribeUseCase(ids)` (03: pauses playback of them, deletes download files, D24); local groups matched by no backup group → deleted without undo (channels deleted immediately on Android); for every matched podcast, delete its `episode_state` and `episode_position` rows (only when HISTORY is selected; one transaction per podcast); clear `queue_entry` and the `play_session` context (UP_NEXT).
3. **Credentials** (opted-in manual backups only): inside `CredentialCommitCoordinator.withCredentialCommit` (03; held until transaction A (step 4) commits): `SecretStore.put` outside transactions (Android Keystore; desktop per [PO-44](../PLAN.md#48-further-product-owner-decisions)), so 02's `db-maintenance` credential sweep cannot delete a stored credential before its podcast references it. Remember `origin → credentialId`.
4. **Transaction A, library.** Groups in backup order: names go through `GroupNames` (invalid → truncated, empty → "Group"); backup groups sharing a `nameKey` are merged into the first (members united, `GroupMerger`); match `uuid`, then `nameKey`; apply the [Merge and Replace rules](#merge-and-replace-rules). In Replace, matched groups first get a temporary `nameKey = '~restore~' || id` so renames that swap names cannot hit the unique index, and every restored group takes the backup's `orderKey` — or, for a file without it, a key from `OrderKey.rewrite(n)` in rank order (`sortOrder`); in Merge, matched groups keep their key and new groups get `OrderKey.after(last)` in backup order. Members take the backup's `orderKey`, else `OrderKey.after(last)` within their group. Podcasts: 02's matching (key → aliases → real `podcastGuid`); unmatched → insert `PENDING_FIRST_FETCH`, `initialFetch = 1`, `nextRefreshAt = now`, `subscribedAt` from the backup, `syncId` = the backup's when present and unused locally, else a new one ([02 Restore matching](02-data-model.md#restore-matching)), `artworkUrl` from the backup and `artworkKey` = `ArtworkKeys.forUrl(artworkUrl)` or `ArtworkKeys.monogram(feedKey)`, `needsCredentials = 1` when `credentialOrigin` is set and no credential was restored. Aliases `INSERT OR IGNORE` with reason `RESTORE` (never equal to a `feedKey`). A `GroupV1.kind` other than `MANUAL` is restored as `MANUAL` keeping `ruleJson` (warning "Smart group 'x' was imported as a regular group").
5. **Transactions B, episodes.** Read `queue.json` first and remember its refs. Stream lines, 1,000 per transaction: skip lines whose podcast is unknown; match by key (local keys computed for the line's `kv` when it differs), then normalised enclosure URL, then guid; unmatched lines with `u` or `yt` become stubs (02 SQL: `inFeed = 0`, `isNew = 0`, `contentHash = 0`) — all of them with HISTORY, only those referenced by `queue.json` without it; merge the state fields (`pl`, `pc`, `st`, `lp`, `fav`, `dis`, `md`) only when HISTORY is selected and the line's `ts != 0`; merge `pos`/`posAt`/`posSrc` whenever HISTORY is selected, independently of `ts`, by the position rule — including, when the merged position is non-zero and the merged result unplayed, the derived in-progress proxy (`ensure(id, posAt)` + `startedAt = COALESCE(startedAt, posAt)`, never now) and the reset when it is played; a winning position on a linked Merge restore is captured regardless of `ts` with `captureAt("episode", rid, "pos", posAt, literal)`, where the literal is the snapshot it wrote (`{ms, dur, src}`, or `{"ms":0,"reset":true}` when the merge reset the position), never a later read of `episode_position`; record `(p, k) → episodeId` for referenced refs.
6. **Transaction C, Up next and session** (UP_NEXT): per the rules table; Merge appends with `OrderKey.after(last)` in backup order, Replace takes each entry's `ok` or keys from `OrderKey.rewrite(n)`; the session's group and podcast references are mapped through the uuid and key maps; `generation + 1`.
7. **Settings** (SETTINGS): every whitelisted value through `SettingsRepository.set`; while linked, a synced key follows 01's Sync write ordering instead — under the key's mutex the literal intent `captureAt("setting", key, "v", createdAt, literal)` (the archive's `createdAt`) commits in Room first, then `SettingsSyncPort.applyRemote` writes DataStore without an implicit capture; a key whose pending local intent is newer keeps that intent and its DataStore value ([Restore while linked](#restore-while-linked), [01 Sync write ordering](01-foundation.md#datastore-files-and-typed-setting-keys)); unknown keys, `DEVICE` keys, type mismatches, invalid values and — while this device is linked — `sync.server_url` and `sync.username` are skipped and counted.
8. **Finish:** one `import_item` per backup podcast (inserted → `QUEUED`, matched → `ALREADY_SUBSCRIBED`, credentials missing → `AUTH_REQUIRED`); session `COMMITTED`, `attemptMarkerAt = now`; `ImportWorkScheduler.schedule` ([7. Fetch](#7-fetch)); `reschedulePeriodic()`; `GroupChannelSync.sync()`; request artwork sync for inserted podcasts (08, M4+); `SnapshotScheduler.requestSoon()` (Android); when `ImportOptions.fromAndroidBackup` (set by `PayloadStore.adopt` for the first-launch restore and `restoreAndroidBackup()`): rename the snapshot to `restored-{epochMs}.zip` ([First-launch restore](#first-launch-restore)); with reach `ALL_DEVICES`, 10's steps 2–5 (adopt IDs, upload, prune by the snapshot cursor, write `linkedAt`; [10 Use this device's library everywhere](10-sync.md#use-this-devices-library-everywhere)) before the session becomes `COMMITTED`, so a failure there re-runs the restore; `RestoreProgress.Finished`.

### RestoreMerger

`RestoreMerger` (`:core:data` `commonMain`) is the one routine that writes a library given as 05's backup DTOs into the database, and the [Merge and Replace rules](#merge-and-replace-rules) table is its specification: the single source of the merge rules for backup restores **and** for a device's first sync link ([D92](../PLAN.md#3-key-decisions): "the first link merges with 05's backup Merge rules"). The row writes of steps 2–7 of the [Restore algorithm](#restore-algorithm) are its body; validation, unlinking, the import items and scheduling stay in `RestoreRunner`.

```kotlin
// :core:data commonMain
interface StagedLibrary {                                   // over an ArchiveReader (restore) or a directory (sync)
    val createdAtMs: Long
    suspend fun library(): LibraryV1                        // PodcastV1, GroupV1 with MemberV1, CredentialV1
    suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit)   // streamed; undecodable lines counted
    suspend fun queue(): QueueV1?
    suspend fun settings(): SettingsV1?
}
class RestoreMerger(/* DAOs, GroupMerger, SecretStore, SettingsRepository, UnsubscribeUseCase,
                       SyncStateDao, SyncOutboxDao, GroupChannelSync, Clock */) {
    suspend fun merge(source: StagedLibrary, policy: MergePolicy, categories: Set<RestoreCategory>,
                      captureWithBackupTimes: Boolean,                     // restore on a linked device, Merge
                      onUnmatched: (suspend (podcastSyncId: String, identityKey: String) -> Unit)?,  // link policies
                      progress: (done: Int, total: Int) -> Unit,
                      onApplied: (suspend (MergedRecord) -> Unit)? = null): MergeSummary
}
// :core:domain — the port 10's FirstLinkMerger uses (:sync:impl cannot depend on :core:data, PLAN 5.1 rule 4)
interface LibraryMerger {
    suspend fun mergeStaged(stagingDir: String, policy: MergePolicy,
                            onUnmatched: suspend (podcastSyncId: String, identityKey: String) -> Unit,
                            progress: (done: Int, total: Int) -> Unit,
                            onApplied: (suspend (MergedRecord) -> Unit)? = null): Outcome<MergeSummary, BackupError>
}
// :core:model
enum class MergePolicy { RESTORE_MERGE, RESTORE_REPLACE, LINK_MERGE, LINK_REPLACE }
data class MergeSummary(val podcastsAdded: Int, val podcastsMatched: Int, val groupsAdded: Int, val groupsMatched: Int,
                        val episodeLines: Int, val stubs: Int, val unmatched: Int, val skippedLines: Int)
data class MergedRecord(val collection: String, val recordId: String, val fields: Map<String, MergedField>)
data class MergedField(val value: JsonElement, val originalAtMs: Long, val origin: MergeValueOrigin)
enum class MergeValueOrigin { LOCAL, STAGED }
```

- **Inputs.** `LibraryV1`, streamed `EpisodeLineV1`s, `QueueV1` and `SettingsV1` — what an archive holds, and what 10's `FirstLinkMerger` writes into its staging directory with `BackupCodec` under the archive's entry names (`library.json`, `episodes.jsonl`, `queue.json`, `settings.json`; no manifest), mapping server records to `PodcastV1` (with `syncId`), `GroupV1` (`uuid`, `orderKey`, members), `EpisodeLineV1` (match hints as stub fields; field-clock milliseconds as `fc`, `posAt`, `pl`, `lp`, and `ts` per the [Archive](#archive)'s staging rule: the newest non-`pos` state clock, 0 when the record has none) and `QueueV1` (Up next with `ok`, the session) ([10 Merge](10-sync.md#merge)). `LibraryMergerImpl` wraps the directory as a `StagedLibrary` and calls `merge` with all three categories.
- **Policies.** `RESTORE_MERGE` and `RESTORE_REPLACE` are the table's Merge and Replace columns; `LINK_MERGE` is its First-link column; `LINK_REPLACE` (10's "Use the server's library on this device") is the Replace column with three differences: IDs are adopted as in `LINK_MERGE`, settings and shared passwords are durable effects as in the First-link column (reported through `onApplied`, journaled by 10 as `pendingSetting` / `pendingAuth` with each chunk, applied after its commit by `SyncApplyEffects` through `SettingsSyncPort.applyRemote` and the credential coordinator; `RestoreMerger` writes neither DataStore nor `SecretStore` for a link policy), and unmatched lines are handled as in `LINK_MERGE`.
- **Capture.** On a linked device every transaction runs inside `SyncStateDao.withApplying` (02), so nothing is captured implicitly. `captureWithBackupTimes` (restore on a linked device, Merge) records each value it changed with `SyncOutboxDao.captureAt` at the backup's own timestamps, passing the literal it wrote for played, position, session and setting fields (02's five-argument form; [Restore while linked](#restore-while-linked)); the link policies capture nothing, because `FirstLinkMerger` uploads the merged library afterwards stamped from row timestamps ([10 Merge](10-sync.md#merge) step 5).
- **First-link metadata (review 2026-10-06).** For link policies, `onApplied` runs inside each projection transaction after IDs are resolved, with logical record IDs and per-field winners (canonical sync field names). LOCAL values and `originalAtMs` are frozen before any projection write; STAGED values identify the winning source fields, whose complete HLCs and session writer remain in 10's staging records. Secret plaintext is never included. The callback writes only Room bookkeeping, never network, DataStore or secret files, so failure rolls the chunk back. First-link row timestamps preserve winning input times; resume uses saved per-field metadata rather than apply-time `now`. `MergeSummary` remains counts only; 10 uses these events for frozen captures, raw-state seeding and durable effects ([10 First-link choices](10-sync.md#first-link-choices)). Manual restore has no callback.
- **Unmatched episode lines.** Restores stub every unmatched line that has an enclosure URL or YouTube ID (step 5). The link policies stub only lines that are queued, current, in progress (`pos > 0`, not played) or favourite, and hand every other unmatched line to `onUnmatched`, after its transaction commits; 10 parks the corresponding record in `sync_parked` until ingestion finds the episode ([10 Parked state and stubs](10-sync.md#parked-state-and-stubs)), so a first link does not create thousands of stubs for episodes the next refresh ingests anyway.
- **IDs.** Link policies make a local podcast matched by feed key or alias take the record's `syncId`, and a local group matched by `nameKey` take the record's `uuid` (`GroupChannelSync.onUuidChanged`), so later records address them directly ([10 Redirects on clients](10-sync.md#redirects-on-clients)). Restores adopt a backup `syncId` only for inserted podcasts whose ID is free, and a backup `uuid` only in Replace.
- **Idempotent.** Re-running a policy with the same input changes nothing further; 10 relies on this when a link is interrupted (`SyncStatus.LinkUnfinished`).

### Merge and Replace rules

The Merge and Replace columns serve restores (`RESTORE_MERGE`, `RESTORE_REPLACE`); the First-link column serves a device's first sync link (`LINK_MERGE`, MS2), whose state rules are timestamp last-writer-wins instead of the restore's "never lose a mark" rules, because sync afterwards decides every field by its clock ([10 Conflict resolution](10-sync.md#conflict-resolution)). "Record" means a server record mapped into the staging files.

| Data | Merge (default) | Replace | First link (`LINK_MERGE`, [10 Merge](10-sync.md#merge)) |
|---|---|---|---|
| Podcasts | union; matched keep their local row, episodes and downloads | exactly the backup's; local-only podcasts are unsubscribed (episodes, state, downloads deleted, D24) | union; a local podcast matched by feed key or alias adopts the record's `syncId` |
| `customTitle`, `includeInAll`, `episodeOrder`, `youtubeVariants` of matched podcasts | local wins (`customTitle`: local if non-null, else backup) | backup wins | as Merge; the field clocks decide after the upload ([10 Merge](10-sync.md#merge) step 5) |
| Aliases | union | union | union |
| GUID provenance (`guidAmbiguous` → `episode_guid_provenance`, [02](02-data-model.md#episode_guid_provenance)) | union into `KNOWN_AMBIGUOUS` on the matched podcast: a GUID ambiguous on either side stays ambiguous, and the union is written before any merged-away podcast row cascades its table; a missing field contributes nothing | the archive's union (provenance of unsubscribed local-only feeds dies with them) | union, same as Merge (the staged file can carry the field only when the local device wrote it; records carry none, [10 What syncs](10-sync.md#what-syncs)) |
| `podcast_settings`, `podcast_group_settings` | per column: local non-null wins, else backup | the backup row replaces the local row | synced columns as Merge, then by field clocks; device-local columns untouched (records never carry them) |
| Groups | match `uuid` → `nameKey`; matched keep local name, look, orders, view prefs; new groups appended after local groups in backup order | exactly the backup's: matched groups take backup values (a `nameKey` match adopts the backup `uuid`), local-only groups deleted, order = backup order | match `uuid` → `nameKey`; a `nameKey` match adopts the record's `uuid`; matched keep local values (then field clocks); new groups take the record's `orderKey` |
| Group and member order (`orderKey`) | matched keep their key; new ones `OrderKey.after(last)` in backup order | the backup's keys, else `OrderKey.rewrite(n)` in rank order | as Merge, except that new items keep the record's key |
| Memberships | union | exactly the backup's | union |
| Played | OR; `playedAt` = max; `playCount` = max; `lastPlayedAt` = max; a line with `ts = 0` contributes no state fields — its only `episode_state` write is the in-progress proxy that the Position row derives for a winning non-zero position | backup (absent or `ts = 0` = unplayed: no state field written; the Position row's proxy may still be derived) | the newer of the record's own `played` clock (`fc.played`, [Archive](#archive)) and the local `episode_state.updatedAt` wins, so a newer remote "unplayed" beats an older local played mark (MS2 acceptance 3); a record without a `played` field leaves the local played state alone; `playCount` and `lastPlayedAt` = max |
| Position | the newer of `posAt` and the local `updatedAt` wins, but a backup position of 0 or null never replaces a local non-zero one ([N1](../PLAN.md#22-non-functional-requirements)); when the merged result is played and `playedAt > posAt`, the position resets to 0 (mark-played semantics, 06); a `ts = 0` line carrying `pos`/`posAt` merges its position by the same rule; when the merged position is non-zero and the merged result unplayed, the in-progress proxy is derived as `ensure(id, posAt)` + `startedAt = COALESCE(startedAt, posAt)` (never now, never 0; an existing row keeps its `updatedAt`) | backup (absent = none); the same proxy for a non-zero, unplayed result | as Merge, then 10's episode-state rules derive the effective state ([10 Episode-state rules](10-sync.md#episode-state-rules)) |
| `startedAt` | earliest non-null, cleared when played | backup | derived from the merged state ([10 Episode-state rules](10-sync.md#episode-state-rules)) |
| Favourite, download tombstone | OR (tombstone time = max) | backup | favourite: the newer of the record's own `fav` clock (`fc.fav`) and the local `updatedAt` wins, and a record without `fav` leaves it; the download tombstone is device-local and untouched |
| `measuredDurationMs` | local if non-null, else backup | backup if non-null, else local | as Merge |
| Up next | append backup entries not already queued, in backup order, after the local queue; entries whose merged state is played are skipped, and locally queued episodes that become played are removed (06's invariant) | the backup's list minus played entries | as Merge (local list first, then the record's entries with fresh keys) |
| Play session | restored only when the local `currentEpisodeId` is null | the backup's | as Merge, and never while this device plays ([R7.5](../PLAN.md#21-functional-requirements)) |
| Settings | only when SETTINGS is checked (default off) | when checked (default on) | the record's synced values win; `RestoreMerger` writes no DataStore value: it reports them through `onApplied`, 10 journals each as `pendingSetting` in the chunk's transaction, and `SyncApplyEffects` writes it after the commit through `SettingsSyncPort.applyRemote` (kept pending while "Sync playback settings" is off; [10 Durable apply effects](10-sync.md#durable-apply-effects), [10 Settings capture](10-sync.md#settings-capture)) |
| Credentials (opted-in backups; shared `auth` for sync) | only for origins without a local credential | replace per origin | only for origins without a local credential, as a durable effect: 10 journals a `pendingAuth` reference in the chunk's transaction and `SyncApplyEffects` installs the staged secret after the commit inside `CredentialCommitCoordinator.withCredentialCommit` (03); `RestoreMerger` calls no `SecretStore.put` for link policies |
| Downloads | never restored: "Re-download N episodes" offer | same | never (device-local) |

Every restore write stores the backup's timestamps (`ts`, `posAt`; for the proxy row a `ts = 0` line creates, `posAt`) as the rows' `updatedAt`, never the time of the restore ([02 Restore matching](02-data-model.md#restore-matching)), so a later first-link Merge or "Reconnect" compares real change times. A restore on a linked device keeps these columns and adds the captures of [Restore while linked](#restore-while-linked).

### Stubs and matching

Stub episodes appear in feeds at once with the backup's title, date and enclosure, so played state and positions are visible before the first refresh. The next ingest of their podcast (INITIAL for pending podcasts) matches them by identity key, enclosure or guid and fills the feed columns, keeping user state ([02 Restore matching](02-data-model.md#restore-matching), [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)). Stubs that never match (episodes that left the feed) are removed by retention after 90 days unless protected (favourite, in progress, queued); losing played state of an episode that no longer exists anywhere is accepted ([02 Retention and maintenance](02-data-model.md#retention-and-maintenance)).

The fields a line or stub carries for matching (`k`, `kv`, `g`, `u`, `yt`) are match hints only — they decide *which stored row* a restore line claims, never *what a GUID means to the feed*: untrusted hints can never prove sole carriage, so restore and first-link writes produce `KNOWN_AMBIGUOUS` (from `guidAmbiguous`) or nothing, and every other GUID of a restored podcast stays `UNKNOWN` until the feed's own observed evidence speaks (preferring a visible duplicate over an N1 state loss, [03 deviation 17](03-feeds-and-discovery.md#implementation-deviations-2026-10-07-m1a-data-half)). Provenance itself is likewise never moved between podcast rows by the match: when a restore merge or a future podcast merge ([03 Feed moves](03-feeds-and-discovery.md#permanent-redirects); the M1 data layer still exposes merge only as a stub) unites two feeds into one row, their `episode_guid_provenance` rows union into the survivor inside the same transaction, *before* the losing row's unsubscribe cascade could delete them — an ambiguous GUID on either side stays ambiguous, which is exactly the information-loss the D98 contract exists to prevent.

### Settings whitelist

The whitelist is derived, not maintained by hand: every key in 01's `AllSettingKeys.list` with `file == SettingsFile.PORTABLE`, written with its `SettingKey` subclass name as `type` ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). A key that must not travel (device paths, grants, prompt flags, selection state) must be declared `DEVICE`; the definition of done for every milestone already requires that classification ([PLAN 7.2](../PLAN.md#72-definition-of-done-every-milestone)). Credentials never pass through DataStore. The first-launch automatic restore (Android) skips SETTINGS because Auto Backup restores `settings.preferences_pb` itself, which is at least as new as the snapshot.

**Sync classification** ([D35](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions)). The whitelist and sync are independent: every `PORTABLE` key travels in backups whatever its `synced` flag, a key with `SettingKey.synced = true` also travels through sync ([10 Settings](10-sync.md#settings)), and `DEVICE` keys travel nowhere. Sync's own keys: `sync.server_url` and `sync.username` are `PORTABLE` and never synced — backups and Android's Auto Backup carry them, so a restored install can offer "Reconnect" — but a restore on a linked device never writes them ([Restore algorithm](#restore-algorithm) step 7); `sync.device_name`, `sync.sync_playback_settings` and `sync.share_feed_passwords` are `DEVICE`. The desktop's `desktop.*` keys are `DEVICE` (11). Because a backup restores across platforms, it may carry keys that mean nothing on the target (`playback.pause_for_navigation` or `backup.auto_snapshot_enabled` on the desktop); they are stored and ignored there. The classification of this document's keys is in [Settings](#settings).

YouTube-engine and update-check keys (owned by 04 and 09; [D76](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions); none of them syncs, [10 Settings](10-sync.md#settings)):

| Key | File | In backups | From |
|---|---|---|---|
| `youtube.engine_enabled` ("Play YouTube in the app") | `settings` | yes | M9a |
| `youtube.engine_updates` (`APPROVED` (default) / `UPSTREAM_STABLE` / `OFF`; PO-32 resolved 2026-10-05) | `settings` | yes | M9b |
| `updates.check_enabled` ("Check for updates", Boolean, default `true`; PO-31 resolved 2026-10-05) | `settings` | yes | M11a |
| `youtube.engine_last_check_at`, `youtube.engine_last_outcome`, `youtube.engine_start_failures`, `youtube.breaker_engine_version` | `device_settings` | never | M9a, M9b |
| `updates.last_check_at`, `updates.skipped_version_code`, `updates.notified_version_code`, `updates.first_run_choice_done`, `updates.verification_notice_shown_at` | `device_settings` | never | M11a |

The planned keys `updates.mode`, `updates.channel` and `updates.whats_new_version_code` were dropped before any build had them (PO-31, PO-33); like every unknown key they would be skipped by a restore ([Restore algorithm](#restore-algorithm) step 7). The existing `youtube.audio_quality`, `youtube.volume_levelling` and `youtube.auto_download*` keys stay portable and travel to every APK; they only take effect where the engine runs. A restored value is a preference, never a fact about the target device: run-time checks win. `youtube.engine_enabled = true` restored on the `armeabi-v7a` APK still yields `ExternalReason.NOT_IN_THIS_APK`; a restored `updates.check_enabled` is a preference too, and debug builds (`BuildInfo.debug`) stay `UpdateCheckState.Disabled(DEV_BUILD)` whatever it says ([09 Update check](09-quality-and-release.md#update-check)). The desktop reads the same `updates.*` keys. Because `updates.first_run_choice_done` is a `DEVICE` key, a new device may see 09's first-run update card again ([Open questions](#open-questions) 16).

### After restore

- Restored podcasts fetch through `import-{sessionId}` with `initialFetch = 1`: no `isNew`, no notifications, no auto-download storm ([D66](../PLAN.md#3-key-decisions)); 07 sets `autoDownloadEligibleAfter` when policies resolve to enabled, so nothing is backfilled ([D67](../PLAN.md#3-key-decisions)).
- The report is the import report of the restore session; podcasts with `AUTH_REQUIRED` show "Enter password" (stored credentials are bound to their device — an Android Keystore key, a DPAPI blob or a `0600` file — and never move, [03 Basic auth and CredentialStore](03-feeds-and-discovery.md#basic-auth-and-credentialstore)).
- **Re-download offer** (M6): transactions B record the episode IDs of `dl = true` lines in `ImportOptions.redownloadIds` (newest `d` first, at most 2,000); those that still exist and have no `COMPLETED` download → the report shows "Re-download 23 episodes (about 1.1 GB)" (size as in `downloadAllEstimate`); tapping calls `DownloadController.request(ids, DownloadLane.MANUAL, allowMetered = null)` from the visible screen (Android: UIDT must be scheduled while visible, [07 Runners and scheduling](07-downloads.md#runners-and-scheduling); desktop: the `downloads-manual` lane, [07 Desktop runners](07-downloads.md#desktop-runners)). YouTube lines are skipped while `YouTubeCapabilitiesSource.capabilities.value.downloads` is false (external mode).

### Restore while linked

[R7.9](../PLAN.md#21-functional-requirements), [D33](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions). Delivered in [MS2](../PLAN.md#ms2-client-sync) (MS2 acceptance 6); 08 owns the prompts ([08 Sync screens](08-ui-ux.md#sync-screens)). A restore on a device that syncs must neither overwrite newer state on the other devices nor be undone by the next pull. The device's sync status (10's `SyncStatus`) decides:

| Situation | Behaviour |
|---|---|
| Not linked, or `Reconnect` / `LinkUnfinished` | A plain restore: capture is off and nothing is recorded for sync; a later link reconciles through its first-link choices ([10 Linking and first merge](10-sync.md#linking-and-first-merge)) |
| Linked, **Merge** | No extra question. `RestoreMerger` runs with `applying = 1` and records each value it changed with `captureAt`, using the backup's own timestamps as clock time — `ts` (played state, favourite), `posAt` (position), `pl` (`playedAt`), `lp` (`lastPlayedAt`), `subscribedAt` (podcasts), the group's `createdAt` (new groups and their memberships) and the archive's `createdAt` (settings, Up next additions) — so a restored value wins on the other devices only where it is actually newer ([10 Interaction with backup, retention and YouTube](10-sync.md#interaction-with-backup-retention-and-youtube)). For played, position, session and setting fields the capture also carries the literal value the restore wrote — the played pair, the position snapshot `{ms, dur, src}` or the reset form, the session, the setting's `v` — because 10 pushes these literals, never a later read that a remote derivation may have changed ([02 Kotlin-side captures](02-data-model.md#kotlin-side-captures)). `captureAt` never lowers a newer pending clock ([10 Capture rules](10-sync.md#capture-rules)). **Exception (2026-10-05): presence.** A record the restore (re)creates because it is absent locally — an inserted podcast (`subscribed = true`), a new group (`deleted = false`), a new membership or Up next entry (`in = true`) — has that presence field captured with a fresh HLC tick (`captureLiteral`), because bringing it back is the user's explicit intent; its other fields keep the backup timestamps, so a newer title, look or order made elsewhere still wins. Otherwise a podcast unsubscribed or a group deleted elsewhere after the backup would be re-removed by the next pull (the server answers `stale` against its tombstone), and silently below the mass-change thresholds. Restored podcasts adopt the backup's `syncId` when it is free, so the server recognises them directly instead of merging by feed key; with the fresh presence clock the server revives a tombstoned record ([10 Same podcast on two devices](10-sync.md#same-podcast-on-two-devices)) |
| Linked, **Replace** | The preview asks "Replace the library on…": **This device only** (default): 10's unlink runs first (token deleted, sync tables cleared, `sync.server_url` kept), then Replace runs locally and pushes nothing; Settings › Sync offers "Link again", whose first-link step offers Merge or "Use this device's library everywhere", and the dialog says that Merge would bring the server's library back. **All synced devices** (order fixed 2026-10-05): (1) the preview, still in the foreground and before any local write, runs 10's pull and staging ([10 Use this device's library everywhere](10-sync.md#use-this-devices-library-everywhere) step 1; offline → the option says it needs a connection); (2) it computes the effect against the **backup's** library — server podcasts and groups the backup lacks; (3) the second confirmation names it ("Other devices will remove 37 podcasts and 2 groups when they next sync; devices ask first when this exceeds their limits"); Cancel changes nothing; (4) on confirm, `restore()` stores reach `ALL_DEVICES` in the session and clears `sync_state.linkedAt` in the same transaction — the durable marker: the device is `LinkUnfinished`, so no normal round runs on a half-replaced library; (5) the runner's Replace runs with `applying = 1` (steps 2–7); (6) step 8 runs 10's steps 2–5, which upload this library with fresh clocks, prune by the snapshot cursor and write `linkedAt`. A crash, quit, network failure or system stop anywhere in (5)–(6) leaves the session uncommitted and `linkedAt` empty; the next run repeats the restore from step 1 and 10's flow from its step 1 (both idempotent), and Settings › Sync shows "Finishing restore…" instead of the first-link choices while such a session is pending. Unlinking from Settings › Sync abandons it and keeps the replaced library locally. Listening history on the server merges by time, so newer plays made elsewhere survive; the confirmation says so |

- Receiving devices apply removals through their mass-change guard ([R7.7](../PLAN.md#21-functional-requirements)); the restoring device itself is never held.
- Settings restored while linked: keys with `synced = true` follow 01's Sync write ordering — the literal intent is captured at the archive's `createdAt` in Room first, then `SettingsSyncPort.applyRemote` writes DataStore (no implicit capture), so a crash between the two leaves an intent that 10's start-up recovery completes ([Restore algorithm](#restore-algorithm) step 7); other portable keys are written as usual; `sync.server_url` and `sync.username` are never restored while linked ([Restore algorithm](#restore-algorithm) step 7).
- Android's first-launch restore from Auto Backup always runs unlinked: the database is fresh, `sync_state` is new, and the token never travels ([Auto Backup](#auto-backup)). The restored `settings.preferences_pb` still holds `sync.server_url` and `sync.username`, so 10 reports `SyncStatus.Reconnect` and 08 shows "Reconnect to <server>" in Settings › Sync and as a Library banner; nothing is pushed before the user reconnects, which links as a new device and runs the first-link choices ([First-launch restore](#first-launch-restore), MS2 acceptance 6).
- A manual restore on an unlinked device behaves the same way when the archive carries `sync.server_url`: the setting returns, the link does not.
- The desktop follows the same rules; it has no Auto Backup.

---

## Auto Backup

**Android only** ([D34](../PLAN.md#3-key-decisions) amended 2026-10-05, [R1.8](../PLAN.md#21-functional-requirements)); the desktop has none ([No Auto Backup on the desktop](#no-auto-backup-on-the-desktop)). Serves R1.8, N1, N3. Delivered in [M3](../PLAN.md#m3-import-export-and-backup); M6 verifies that downloads stay out ([M6](../PLAN.md#m6-downloads) acceptance 7). Honours [D34](../PLAN.md#3-key-decisions), [D35](../PLAN.md#3-key-decisions), [PO-15](../PLAN.md#48-further-product-owner-decisions) default (on; no backup without encryption). Platform facts: [Auto Backup](https://developer.android.com/identity/data/autobackup). Auto Backup needs an active system backup transport: Google backup (Play services) or a ROM-integrated one such as [Seedvault](https://github.com/seedvault-app/seedvault); without one (many de-Googled ROMs) nothing is backed up, so R1.8 holds only on API 28+ with a screen lock and a transport, and the Android-backup row of 08's Backup and restore screen points to the manual backup.

GitHub Releases is the only channel ([D79](../PLAN.md#3-key-decisions)), so device setup on a new phone does not reinstall Neutrodyne; the user installs the APK ([R6.1](../PLAN.md#21-functional-requirements)) and Android restores the two included files at install, as for `adb install` ([Platform constraints](#platform-constraints)). Unverified: that a restore at install, run after device setup has finished, still reads the old device's data set; checked in M11b with two devices ([Open questions](#open-questions) 17). Updates — a manual install of the APK downloaded from the GitHub release page (the update check only links to it, [D78](../PLAN.md#3-key-decisions)), or Obtainium — replace only the APK, so the snapshot, the DataStore files and `noBackupFilesDir` stay in place. Restore requires the same signing certificate. Every Neutrodyne APK — the published release builds and local debug builds alike — is signed with the committed public key ([D61](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions)); debug builds are another package (`ch.lkmc.neutrodyne.debug`) and never receive the published app's data, but any APK signed with the key under `ch.lkmc.neutrodyne`, a spoofed one included ([PLAN P10](../PLAN.md#8-risks-and-mitigations)), can receive the restored data; a later move to a private key breaks Auto Backup restore across the switch, and the manual backup ZIP is the path ([Platform constraints](#platform-constraints)).

| Path | Cloud backup | Device-to-device | Why |
|---|---|---|---|
| `files/backup/auto-snapshot.zip` | yes, encrypted transports only | yes | library, groups, history, queue (≤ 20 MB) |
| `files/datastore/settings.preferences_pb` | yes, encrypted transports only | yes | portable settings |
| Everything else: `databases/neutrodyne.db` (with the `sync_*` tables), `device_settings` (with sync's device keys), `files/downloads/`, `Android/data/{applicationId}/files/Podcasts/`, `files/artwork/`, media and Coil caches, `credential` rows inside the DB (feed passwords and the sync token, encrypted with a Keystore key that never leaves the device), import payloads, `restored-*.zip` | no | no | include-only rules; one downloaded episode would exceed the 25 MB quota and silently stop all backups |
| `noBackupFilesDir/ytdlp/` (engine versions, `active.json`, staging), `noBackupFilesDir/updates/last-check.json` (the update check's last result, 09), `cacheDir/yt-dlp/` (player-JS cache) | no | no | excluded by the platform as well as by the include-only rules; device-bound and re-fetchable (an engine version must pass the [D76](../PLAN.md#3-key-decisions) checks on the device that runs it; the next update check rebuilds `last-check.json`) |

**Sync state never travels** ([D34](../PLAN.md#3-key-decisions), [D93](../PLAN.md#3-key-decisions)). The database is never included, so `sync_state`, `sync_outbox`, `sync_clock`, `sync_parked` and `sync_held` stay behind with the encrypted sync token; the snapshot carries `syncId`s and `orderKey`s but no sync bookkeeping. Only `sync.server_url` and `sync.username` travel, inside `settings.preferences_pb`: the restored install shows "Reconnect to <server>" and pushes nothing before the user reconnects ([Restore while linked](#restore-while-linked), [R7.9](../PLAN.md#21-functional-requirements)).

### No Auto Backup on the desktop

The desktop has no Auto Backup and no snapshot ([D34](../PLAN.md#3-key-decisions), [11 Behaviour differences from Android](11-desktop.md#behaviour-differences-from-android)): `AutoSnapshotWorker`, the rules XML, `FirstLaunchRestoreInitializer` and `SnapshotNowReceiver` exist only in `androidMain` and `:app`; the desktop binds `NoSnapshotScheduler`, `SnapshotStatus.supported = false` makes Settings › Backup say that computers have no automatic backup and point to "Back up library…" and sync (08 wording), and a restored `backup.auto_snapshot_enabled` is ignored. Its multi-device paths are the manual backup ZIP and sync; a damaged desktop database is recovered from a manual backup or by reconnecting to the sync server ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery); a local recovery snapshot is proposed in [Open questions](#open-questions) 22).

### Rules XML

`:app/src/main/res/xml/data_extraction_rules.xml` (devices on Android 12+; we target 37):

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <include domain="file" path="backup/auto-snapshot.zip" />
        <include domain="file" path="datastore/settings.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="backup/auto-snapshot.zip" />
        <include domain="file" path="datastore/settings.preferences_pb" />
    </device-transfer>
</data-extraction-rules>
```

Both Android sections must stay present: a missing `<cloud-backup>` or `<device-transfer>` section means that mode is "fully enabled for all content" (database and downloads included). There is deliberately no `<cross-platform-transfer>` section (01 P28): that element (Android 16 QPR2) requires `<platform-specific-params bundleId teamId contentVersion>` naming the iOS app that may receive the data, and Neutrodyne has no iOS app; a section without those parameters would be invalid. Unverified: the platform's behaviour when the section is absent; since the documentation says transfers go only "to the specified app", nothing can match.

`:app/src/main/res/xml-v28/backup_rules.xml` (Android 9–11; client-side encryption exists from Android 9):

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="file" path="backup/auto-snapshot.zip" requireFlags="clientSideEncryption" />
    <include domain="file" path="datastore/settings.preferences_pb" requireFlags="clientSideEncryption" />
    <include domain="file" path="backup/auto-snapshot.zip" requireFlags="deviceToDeviceTransfer" />
    <include domain="file" path="datastore/settings.preferences_pb" requireFlags="deviceToDeviceTransfer" />
</full-backup-content>
```

`:app/src/main/res/xml/backup_rules.xml` (Android 8.0–8.1, no client-side encryption, so PO-15 means no backup):

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="file" path="backup/.none" />
</full-backup-content>
```

- Manifest attributes (01 merges them): `android:allowBackup="true"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"`; no `backupAgent`, no `fullBackupOnly` (a custom agent would run in restricted mode, without the app graph).
- **Unverified, checked in M3 with `bmgr` on API 26, 28, 31 and 36 images ([Testing with bmgr](#testing-with-bmgr)):** (a) two `<include>` elements for one path with different `requireFlags` are accepted and each is evaluated on its own (two elements rather than one attribute listing both flags, because a combined attribute requires both flags at once); (b) an include of a path that never exists yields an empty backup on API 26–27, not the default full backup. Fallbacks: (a) drop the D2D includes on API 28–30; (b) set `android:allowBackup` through a resource bool that is `false` below API 28 (`values-v28` = `true`).
- Consequence of PO-15: devices on Android 8.0–8.1, and devices without a screen lock, have no cloud backup of the library ([Open questions](#open-questions)).

### Snapshot production

`AutoSnapshotWorker` (`:core:data` `androidMain`, created by `MetroWorkerFactory`, tag `backup`):

| Work | Request | Trigger |
|---|---|---|
| `backup-auto-snapshot` | periodic 24 h, `setRequiresDeviceIdle(true)`, `setRequiresCharging(true)`, policy `UPDATE` | start-up initializer, order 200 ([01 Application start-up](01-foundation.md#application-start-up)) |
| `backup-auto-snapshot-now` | one-time, initial delay 10 min, `setRequiresBatteryNotLow(true)`, policy `REPLACE` (debounce) | `SnapshotScheduler.requestSoon()` (`WorkManagerSnapshotScheduler`): called directly by 05's own writes (group create/rename/delete/reorder/look, membership changes, scope-settings writes, import `DONE`, restore finished) and by a library watcher for 03's subscribe and unsubscribe (below) — not after played-state changes (the daily run covers them) |

**Library watcher.** The order-200 initializer that schedules `backup-auto-snapshot` also launches, on `@ApplicationScope`, a collector of `BackupDao.observeLibraryShape()` (`SELECT (SELECT COUNT(*) FROM podcast), (SELECT COUNT(*) FROM podcast_group_member)`, requested from 02) with `distinctUntilChanged().drop(1)` → `requestSoon()`. Refreshes write `podcast` but never change these counts, so they trigger nothing; customTitle and `includeInAll` edits wait for the daily run.

Algorithm:

1. `backup.auto_snapshot_enabled` off → return success (turning it off deletes `auto-snapshot.zip` immediately and cancels both work names).
2. Guard: `backup-restore` is enqueued, blocked or running → return success (the restore requests a snapshot when done).
3. Guard: an existing `auto-snapshot.zip` whose manifest `installationId` differs from `backup.installation_id` came from another installation and has not been restored yet → never overwrite it; return success. (This is what keeps a reinstalled app from replacing the real cloud snapshot with an empty one before the restore ran.) The file leaves this state only by a successful restore, by `discardAndroidBackup()` or by the `Corrupt`/`NotABackup` rule of the [Restore algorithm](#restore-algorithm), each of which renames it to `restored-{epochMs}.zip`.
4. From M3 until M11b only: run 02's import-session cleanup ([9. Session lifetime](#9-session-lifetime)).
5. `BackupWriter.write(AUTO_SNAPSHOT, files/backup/auto-snapshot.zip.tmp, includeCredentials = false)`, without `subscriptions.opml`.
6. Size guard: compressed size > 20 MB → rewrite at the next level; at level 3 still > 20 MB → keep the previous snapshot, store `backup.last_snapshot_error = "too_large"`, log WARN.
7. Flush to disk, atomic move over `auto-snapshot.zip`, delete `restored-*.zip`, write `backup.last_snapshot_at`, `_bytes`, `_level`.

| Level | Episode lines kept | Typical use |
|---|---|---|
| 0 | all lines of [Backup export](02-data-model.md#backup-export) | normal (a few MB at the N5 scale) |
| 1 | played lines with `pl` older than 365 days and no position, favourite or queue reference slimmed to `p`, `k`, `kv`, `pl`, `ts` | very long histories |
| 2 | those lines dropped; tombstone-only lines older than 365 days dropped | extreme |
| 3 | only in-progress, favourite, queued and current lines | last resort |

### First-launch restore

`FirstLaunchRestoreInitializer` (`AppInitializer`, order 110, `:core:data` `androidMain`):

```mermaid
sequenceDiagram
  participant A as NeutrodyneApplication
  participant D as DatabaseOpener (02)
  participant F as FirstLaunchRestoreInitializer
  participant P as PayloadStore
  participant W as RestoreWorker
  A->>D: awaitOpen() (order 100)
  D-->>A: OpenResult(created, recovered)
  A->>F: run() (order 110)
  alt created and files/backup/auto-snapshot.zip exists
    F->>P: adopt(snapshot) into a NEUTRODYNE_BACKUP session
    F->>W: enqueue backup-restore (MERGE, HISTORY and UP_NEXT, auto)
    W->>W: on success rename the snapshot to restored-ts.zip
  else not created and a foreign snapshot exists
    F->>F: SnapshotStatus.foreignPending = true, banner and notification 3004
  else
    F-->>A: nothing to do
  end
```

- `OpenResult.created` (Room `onCreate` in this process) is the only fresh-install signal; a DataStore flag would itself have been restored ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)). `recovered != null` (corrupt database quarantined, so the new one is also `created`) takes the same path with the local snapshot.
- The automatic restore uses **Merge**, not Replace: on an empty database both give the same result (every Merge rule falls back to the backup value), and Merge never removes a podcast the user added in the seconds before the worker ran. SETTINGS is skipped because Auto Backup restores `settings.preferences_pb` itself.
- The snapshot is copied to `cacheDir/import/{sessionId}.bin` (`PayloadStore.adopt`) so the restore path equals the manual one; the original is renamed to `files/backup/restored-{epochMs}.zip` only after success and deleted by the next successful snapshot.
- `NewerFormat` → keep the snapshot untouched, post "Your backup needs a newer version of Neutrodyne" (ID 3004; with a "Check for updates" action once M11a exists, [Error handling and failure modes](#error-handling-and-failure-modes)). After the app is updated the database is no longer fresh, so the second branch applies.
- **Foreign snapshot on an existing database** (reachable after `NewerFormat` and an app update): `foreignPending = true` drives a Library banner and notification 3004 "Restore your library from Android backup?" with Restore (`restoreAndroidBackup()` → session → the normal restore preview, Merge preselected) and Discard (`discardAndroidBackup()`). Until then the guard keeps the file, so the cloud copy is never overwritten unseen.
- **Sync** (MS2): the restore runs before any link exists (fresh `sync_state`, capture off) and adopts the snapshot's free `syncId`s; the restored `sync.server_url` makes 10 report `SyncStatus.Reconnect` ("Reconnect to <server>", 08), and nothing is pushed until the user reconnects, which links as a new device and merges ([Restore while linked](#restore-while-linked), MS2 acceptance 6).
- Nothing waits for the restore at start-up ([01](01-foundation.md#application-start-up)); `BackupRepository.observeRestore()` drives 08's "Restoring your library…" banner in Feeds and Library. The library appears within seconds (transaction A), history fills in, then feeds refresh.

```mermaid
stateDiagram-v2
  [*] --> Absent
  Absent --> Current: AutoSnapshotWorker writes, fsync, atomic rename
  Current --> Current: next write succeeds, or fails and the old file stays
  [*] --> Foreign: Auto Backup restore at install
  Foreign --> Foreign: worker guard refuses to overwrite
  Foreign --> Restored: restore succeeds, Discard, or Corrupt (renamed restored-ts.zip)
  Restored --> Current: next successful snapshot deletes restored-ts.zip
  Current --> Absent: snapshot setting turned off
```

### Testing with bmgr

M3 acceptance 6. Scripted as `scripts/ci/bmgr-check.sh` in 09's nightly `bmgr` job (API 29 and 36 emulators, [09 CI pipelines](09-quality-and-release.md#ci-pipelines)) against the published build: the `x86_64` APK of `assembleRelease` — a non-debuggable release build signed with the committed key, package `ch.lkmc.neutrodyne` ([D2](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions)). Run once by hand in M3 on API 26 (expect an empty backup set), 28 and 31 images to settle the Unverified points of [Rules XML](#rules-xml) ([Test backup and restore](https://developer.android.com/identity/data/testingbackup)):

```bash
pkg=ch.lkmc.neutrodyne                     # the published release build; a local debug build is ch.lkmc.neutrodyne.debug
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell settings put secure backup_local_transport_parameters 'is_encrypted=true,fake_encryption_flag=true'
# is_encrypted: else disableIfNoEncryptionCapabilities skips us (API 31+); fake_encryption_flag: the API 28–30 name (Unverified)
adb shell am broadcast --include-stopped-packages -n "$pkg/ch.lkmc.neutrodyne.SnapshotNowReceiver"
# expect "Broadcast completed: result=-1, data="ok …""; on data="pending" send again (at most 5 times, 5 s apart)
adb shell bmgr backupnow "$pkg"            # expect "Package … with result: Success"
adb shell pm path "$pkg"                   # pull the APK(s), then:
adb shell pm uninstall --user 0 "$pkg" && adb install-multiple --user 0 base.apk
```

The reinstall deliberately omits the `-t` of the official script, so it also proves that the published APK is not `testOnly` ([PLAN M0](../PLAN.md#m0-scaffold-and-ci) acceptance 7). The receiver's full class name keeps the command valid for a local debug build, whose package gains `.debug` while its classes keep their names.

**Snapshot trigger (decided 2026-10-05; kept by the scope revision).** `bmgr backupnow` copies whatever `files/backup/auto-snapshot.zip` holds, so the script first makes the app write it. Published builds are release builds without developer UI ([D2](../PLAN.md#3-key-decisions), [D96](../PLAN.md#3-key-decisions)), so the trigger is a shell-only receiver in `:app`'s `main` source set:

```xml
<receiver
    android:name="ch.lkmc.neutrodyne.SnapshotNowReceiver"
    android:exported="true"
    android:permission="android.permission.DUMP" />
```

- No intent filter: only an explicit broadcast reaches it, and only from a sender holding `DUMP`, whose protection level is `signature|privileged|development`. The shell user (`adb shell`) holds it; a third-party app cannot, except after an `adb shell pm grant`. It follows the pattern of 09's `BenchmarkSeedReceiver`.
- Member-injected: `onReceive` first calls `graph.inject(this)` (`AndroidAppGraph.inject(target: SnapshotNowReceiver)`, [01 Dependency injection](01-foundation.md#dependency-injection)), which sets `BackupRepository` as a Metro `Lazy` (01's rule for framework components created before the database is open). `onReceive` calls `goAsync()`, starts `BackupRepository.writeSnapshotNow()` on `@ApplicationScope` and waits for it at most 8 s (`withTimeoutOrNull` on the `Deferred`, so a slow write is never cancelled; a receiver must finish "under 10 seconds" even with `goAsync()`). It then sets `resultCode = Activity.RESULT_OK` with `resultData = "ok at=<lastWrittenAt> bytes=<bytes> level=<level>"`, or `RESULT_CANCELED` with `"disabled"`, `"guarded"`, `"pending"` or `"error <BackupError>"`, and calls `finish()`. `am broadcast` sends an ordered broadcast, waits for it to finish and prints the result code and data, so the script asserts on that line.
- `writeSnapshotNow()` runs the [Snapshot production](#snapshot-production) algorithm at once under the backup mutex, without the work's constraints, and returns the resulting `SnapshotStatus`. The receiver maps it: `enabled = false` → `disabled` (nothing written); `lastWrittenAt` not advanced because a guard held (a pending restore or a foreign snapshot) → `guarded` (the file stays); otherwise `ok`.
- Harmless if misused: it does only what the daily work does. It is the one named exception to PLAN N7's and 7.2's test-hook rule ([D2](../PLAN.md#3-key-decisions); [01 Debug build type](01-foundation.md#debug-build-type)): shell-only, `DUMP`-protected and running only production code. It ships in every APK, so the check runs on exactly what users install; it works on the non-debuggable release build because the shell holds `DUMP` whatever the app's flags. R8 keeps it, because manifest-declared components are entry points ([shrink code](https://developer.android.com/build/shrink-code)), and its name stays readable (`-dontobfuscate`, [01 Release build and baseline profiles](01-foundation.md#release-build-and-baseline-profiles)).
- Local debug builds (`BuildInfo.debug`, [D2](../PLAN.md#3-key-decisions)) additionally show a "Write snapshot now" row in Settings › Backup (08) that calls the same function; release builds do not have it.
- The script cannot list `files/backup/` with `run-as` (the release build is not debuggable; the debuggable-build exposure, risk P11, was retired 2026-10-05); its assertions use only the receiver's result line and the local transport's file list.

Pass: after reinstall the library, groups, played state, positions and Up next are back, and the local transport's backup data for the package contains only the two included files (no `databases/`, no `Podcasts/`). Device-to-device: the D2D script of the same page (Android 12+; its `D2dTransport` lives in Google Play services, so it needs a Google APIs image and is manual only). An automated `FirstLaunchRestoreTest` (GMD) covers the logic without `bmgr`: place a snapshot file, start with an empty database, assert the restored library.

---

## Receiving files

Serves R1.1, R8.3, R8.9. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) (Android filters, the in-app pickers on both platforms, drops onto the desktop window) and [MD2](../PLAN.md#md2-desktop-shell-behaviours-and-os-integration) (the desktop `.opml` association and the single-instance hand-off, 11). Everything before **In-app entry points** below is **Android**: the activity is `ch.lkmc.neutrodyne.ExternalImportActivity` in `:app` ([01 Manifest and permissions](01-foundation.md#manifest-and-permissions) lists it; filters are defined here). The desktop's inputs are in [Desktop inputs](#desktop-inputs).

```xml
<activity
    android:name=".ExternalImportActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:theme="@style/Theme.Neutrodyne.Translucent"
    android:label="@string/import_into_neutrodyne">
    <!-- "Open with" and Share for typed OPML/XML and for backup or Takeout ZIPs; no scheme = content: and file: -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/x-opml" />
        <data android:mimeType="text/xml" />
        <data android:mimeType="application/xml" />
        <data android:mimeType="application/zip" />
    </intent-filter>
    <!-- Share of an untyped file (file managers report .opml as application/octet-stream) -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="application/octet-stream" />
    </intent-filter>
</activity>
<!-- API 31+: "Open with" for content://…/*.opml whatever MIME type the provider reports.
     pathSuffix does not exist below 31, where the filter would match every content URI. -->
<activity-alias
    android:name=".OpmlByExtensionAlias"
    android:targetActivity=".ExternalImportActivity"
    android:enabled="@bool/neutrodyne_api31_or_newer"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="content" android:host="*" android:mimeType="*/*" android:pathSuffix=".opml" />
    </intent-filter>
</activity-alias>
```

`res/values/bools.xml`: `neutrodyne_api31_or_newer = false`; `res/values-v31/bools.xml`: `true`.

`ExternalImportActivity : AppCompatActivity` (member-injected through `AndroidAppGraph.inject(this)`, [01 Dependency injection](01-foundation.md#dependency-injection); AppCompat so the per-app language applies below API 33, N10). `Theme.Neutrodyne.Translucent` (`:app/res/values/themes.xml`): parent `Theme.Neutrodyne`, `android:windowIsTranslucent = true`, `android:windowBackground = @android:color/transparent`, `android:windowNoTitle = true`, `android:windowAnimationStyle = @null`; its only UI is the progress overlay and the error dialog (Compose `NdDialog`). Never set `screenOrientation` on it (translucent activities that request an orientation crash on API 26, and N7 forbids orientation locks).

**Why no broad filter.** A VIEW filter for `*/*` or `application/octet-stream` would offer Neutrodyne for every unknown file (Pocket Casts removed its octet-stream filter after it caught "install (1).apk"); MIME types of `.opml` are unreliable anyway (`application/octet-stream`, `text/xml`, `text/plain`, `text/x-opml`), and some providers expose neither a type nor a file name (Downloads `msf:` IDs, Gmail). Everything that the narrow filters miss goes through the in-app picker, and the content is sniffed regardless of how it arrived. JSON and CSV files (NewPipe, LibreTube, Takeout) are imported from the in-app picker only; Takeout ZIPs also arrive through the `application/zip` filter.

**Behaviour.**

1. Resolve the stream: VIEW → `intent.data`; SEND → `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`, else `clipData.getItemAt(0).uri`. No URI, or a scheme other than `content`/`file` → toast "Nothing to import", `finish()`.
2. A retained `ExternalImportViewModel` calls `ImportRepository.create(ImportSource(uri.toString(), displayName = null))` ([1. Acquire](#1-acquire) step 5: the copy runs in the caller's coroutine while the grant lives; sniffing, parsing and persisting continue in `@ApplicationScope`). A progress overlay appears only if the call takes longer than 300 ms.
3. Success → `startActivity(Intent(ACTION_VIEW, "neutrodyne://open/import/{sessionId}").setClass(this, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP))`, `finish()`. 01's `IntentRouter` turns it into `Navigate(LibraryKey, [ImportKey(sessionId)])`. `MainActivity` itself has no file filters.
4. Failure → a dialog with the [error text](#error-handling-and-failure-modes), then `finish()`.
5. If the user leaves after the copy but before the session is ready, the application-scope job still finishes and posts "Ready to review: 142 podcasts from antennapod-feeds.opml" (channel `import_backup`, ID 3002, tag = session ID) deep-linking to the session.

**In-app entry points** (both platforms; all in `:feature:importexport`, launched from Discover, Library overflow, Settings › Backup and onboarding, 08, and on the desktop also from File › Import OPML or backup… and Ctrl/Cmd+O, [11 Desktop UX](11-desktop.md#desktop-ux)): `FilePicker.openFile()` of `:core:ui`'s `PlatformActions` — on Android `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` with `arrayOf("*/*")` (MIME filtering would grey out `.opml` files on providers without a mapping), on the desktop the native open dialog without a type filter (08; AWT's filters do not work on Windows anyway, [Platform constraints](#platform-constraints)) → the screen's ViewModel calls `create(ImportSource(uri, displayName))` and navigates to `ImportKey(sessionId)`; an "Import from URL" field creates the session from an `https://` source. A backup file picked anywhere routes to the restore preview because its session format is `NEUTRODYNE_BACKUP`.

### Desktop inputs

[R1.1](../PLAN.md#21-functional-requirements), [R8.3](../PLAN.md#21-functional-requirements), [R8.9](../PLAN.md#21-functional-requirements). The OS registrations, the drop target and the hand-off between instances are 11's ([11 Links and files from the OS](11-desktop.md#links-and-files-from-the-os), [11 Drag and drop](11-desktop.md#drag-and-drop)); every input ends in the same `ImportRepository.create(ImportSource("file:///…", displayName))`, so sniffing, caps, preview, commit and fetch are the common pipeline above.

| Input | Arrives through | Opens |
|---|---|---|
| File › Import OPML or backup…, Ctrl/Cmd+O, the in-app Import buttons | `FilePicker.openFile()` (native open dialog, no filter) | import preview, or the restore preview for a backup |
| A `.opml`, `.xml` or `.zip` file dropped onto the window (at most 20 per drop) | 11's drop target → `DesktopOpenHandler` → `IntentRouter` | `.opml`/`.xml`: import preview; `.zip`: sniffed — Neutrodyne backup → restore preview, Takeout ZIP → import preview |
| A double-clicked or "Open with" `.opml` file (MSI, DMG, DEB and RPM associations; tar.gz after "Add to applications menu") | the OS → first-launch arguments, the macOS open-file handler, or a second launch's hand-off to the running instance (11) | import preview in the running window |
| JSON and CSV files (NewPipe, LibreTube, Takeout) | the in-app dialog only, as on Android | import preview |

- Each input becomes its own session; the first one navigates, later ones wait in the resume banner of `observeOpenSessions()` (08), so a drop of several files never stacks previews.
- Inputs that arrive before the database is open are queued by 11 and applied after the first frame; nothing is imported without the preview's confirmation.
- The file is copied to `<cache>/import/` before parsing ([1. Acquire](#1-acquire) step 7), so moving or deleting the original right after the drop is harmless, and no "ready to review" notification is needed.
- Errors use the dialogs of [Error handling and failure modes](#error-handling-and-failure-modes); 11's "Neutrodyne can't open this file" snackbar covers directories and other file types before 05 sees them.
- Unverified (M3): that macOS lets the app read a file the user picked, dropped or opened from a protected folder (`~/Downloads`, `~/Documents`) without a folder-privacy prompt; a refusal is `Unreadable` with a hint to grant access ([11 macOS folder privacy](11-desktop.md#macos-folder-privacy)).

---

## Settings

Keys follow 01's registry ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). Per-podcast and per-group overrides are columns, not DataStore keys ([Effective settings resolution](#effective-settings-resolution)); group view settings are columns of `podcast_group`. Canonical defaults implemented here: group names 1–40 chars, emoji allowed, unique by `nameKey`; `feedOrder`/`playOrder` `NEWEST_FIRST`; Ungrouped tab off; counts window 30 days or `hideOlderThanDays`; delete-group undo 10 s; OPML export grouped, YouTube on, passwords off; import "treat existing as played" off, "notifications for imported podcasts" off; restore mode Merge; on Android auto snapshot every 24 h (idle + charging) plus 10 min after significant changes, size guard 20 MB.

| Key | Type | Default | File | Synced | UI location | Milestone |
|---|---|---|---|---|---|---|
| `groups.show_ungrouped_tab` | Bool | false | `settings` | yes | Settings › Feeds › "Show Ungrouped tab" | M2 |
| `groups.all_filter_flags` | Int32 (bits of `FilterFlagBits`) | 0 | `settings` | yes | Feeds › All › filter chips | M2 |
| `groups.all_media_filter` | Choice `MediaFilter` | `ALL` | `settings` | yes | same | M2 |
| `groups.all_hide_older_than_days` | Int32 (0 = off; 1, 3, 7, 14, 30, 90, 365) | 0 | `settings` | yes | Feeds › All › overflow | M2 |
| `groups.ungrouped_filter_flags`, `groups.ungrouped_media_filter`, `groups.ungrouped_hide_older_than_days` | as above | 0, `ALL`, 0 | `settings` | yes | Feeds › Ungrouped | M2 |
| `groups.all_last_viewed_at`, `groups.ungrouped_last_viewed_at` | Int64 (0 = initialise to now) | 0 | `device_settings` | no | internal | M2 |
| `backup.opml_layout` | Choice `ExportLayout` {`GROUPED`, `FLAT`} | `GROUPED` | `settings` | no | Export dialog | M3 |
| `backup.opml_include_youtube` | Bool | true | `settings` | no | Export dialog | M3 |
| `backup.auto_snapshot_enabled` | Bool | true | `settings` | no | Settings › Backup › "Include your library in Android backup" (with "Requires a screen lock"); Android only | M3 |
| `backup.installation_id` | Text (UUID generated on first read) | "" | `device_settings` | no | internal | M3 |
| `backup.last_snapshot_at`, `backup.last_snapshot_bytes`, `backup.last_snapshot_level`, `backup.last_snapshot_error` | Int64, Int64, Int32, Text | 0, 0, 0, "" | `device_settings` | no | Settings › Backup status line (Android); diagnostics (09) | M3 |
| `backup.last_manual_backup_at` | Int64 | 0 | `device_settings` | no | Settings › Backup ("Last backup: 3 days ago") | M3 |
| `backup.scheduled_enabled` | Bool | false | `settings` | no | Settings › Backup | M15 |
| `backup.scheduled_interval_days` | Int32 {1, 3, 7, 14} | 7 | `settings` | no | same | M15 |
| `backup.scheduled_keep` | Int32 1–20 | 5 | `settings` | no | same | M15 |
| `backup.scheduled_tree_uri` | Text | "" | `device_settings` (SAF grants and desktop paths never transfer) | no | same | M15 |

**Sync classification** (the [definition of done](../PLAN.md#72-definition-of-done-every-milestone) requires one for every portable key). The All and Ungrouped view keys and `groups.show_ungrouped_tab` sync, because their group counterparts — `podcast_group`'s filter, media, hide-older-than and `showAsTab` columns — travel with every group record and [PO-37](../PLAN.md#48-further-product-owner-decisions) names the feed sort and filter defaults as synced; like every synced key they sit behind "Sync playback settings" ([10 Settings](10-sync.md#settings); [Open questions](#open-questions) 23). Export-dialog memories, snapshot and scheduled-backup keys stay per device. Every `groups.*` and `backup.*` key is the same on both platforms; `backup.auto_snapshot_enabled` has no effect on the desktop.

**Scheduled backup (v1.x, M15) outline.** The user picks a folder with `ACTION_OPEN_DOCUMENT_TREE` (Android 11+ refuses the storage root and `Download/` itself; a subfolder works) and the app calls `takePersistableUriPermission`. `ScheduledBackupWorker` (`backup-scheduled`, periodic `backup.scheduled_interval_days`, charging + battery not low, `UPDATE`) writes a `MANUAL`-format archive with kind `SCHEDULED` (no passwords) through `documentfile` 1.1.0, names it `neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip` (time included because providers rename duplicates to `name (1).zip`), and deletes the oldest files beyond `backup.scheduled_keep` matched by the lenient regex `^neutrodyne-backup-\d{4}-\d{2}-\d{2}-\d{4}( \(\d+\))?\.zip$`. A lost grant (`DocumentFile.canWrite()` false) posts a notification on `import_backup` (ID 3010) instead of failing silently (AntennaPod precedent: every 3 days, keep 5). On the desktop the same files go into a folder chosen with 11's folder dialog ([11 Download folders](11-desktop.md#download-folders)), written by the `import-backup` lane while the app runs; a missing folder posts a `DesktopNotifier` notice.

---

## Testing

Serves N1, N9, N11. Infrastructure, runners, golden-update switch and CI wiring are 09's ([09 Test strategy](09-quality-and-release.md#test-strategy), [09 Test infrastructure](09-quality-and-release.md#test-infrastructure)); SQL-level tests (feed queries, counts, commit, restore matching) are 02's ([02 Testing](02-data-model.md#testing)). Placement follows [01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands): pure logic in `commonTest` (run on the desktop JVM in CI's `unit` job); database-backed `:core:data` tests in `desktopTest` with the bundled driver, `TestClock` and fakes from `:core:testing` — they exercise the common code both apps run; island tests in `:feeds:jvm`'s `test`; Android-only hosts in `androidHostTest` (Robolectric); GMD tests in `:app`.

| Test | Module, runner | Cases | Milestone |
|---|---|---|---|
| `GroupNamesTest` (table-driven `kotlin-test`) | `:feeds` `commonTest` (desktop JVM); the same vectors run in `:sync:server`'s tests (equal-name merges, 10) | trim and whitespace collapse; bidi controls removed; ZWJ emoji kept; 40 vs 41 code points; "Café" NFC vs NFD collide; "Tech" vs "tech"; `İ` key; `suggestsOldestFirst` | M2 |
| `GroupRepositoryTest` | `:core:data` `desktopTest`, virtual time | first free palette colour; `reorder` rejects non-permutations and writes only the moved rows (one row for one drag or move; M2 acceptance 14 with 02's `GroupOrderTest`); delete removes the row without renumbering and clears a GROUP context; undo restores `id`, `uuid`, `orderKey`, settings, surviving members and (generation unchanged) context; undo after 10 s → `UndoExpired` and `GroupChannelSync.onDeleted`; delete "tech" → create "tech" → undo returns `NameTaken(replacement)` leaving the replacement and its members untouched, and undo succeeds after the replacement is renamed inside the window; `applyMembership` tri-state with member `orderKey`s; `observeMemberships` emits once per membership write; `GroupMerger` unites members and moves a GROUP context | M2 |
| `GroupNotificationChannelsTest` | `:core:data` `androidHostTest`, Robolectric (`ShadowNotificationManager`) | every row of [Notification channels](#notification-channels): rename updates the channel name; a rename of a group without its own setting creates no channel; start-up sweep deletes an orphan channel; `onUuidChanged` copies importance, sound, lights, vibration and badge and deletes the old channel | M2, MS2 |
| `FeedRepositoryTest` | `:core:data` `desktopTest` | tab order and the Ungrouped rule; prefs → `FeedFilters` incl. hour-rounded `minSortDate`; Podcast filters rejected; `markVisited` target (row vs `device_settings`); counts zero-fill; virtual counts use the All/Ungrouped hide-older bound; `setFeedOrder` rejected for All and Ungrouped; `downloadAllEstimate` cap 200, Up next and current included, `FAILED` rows included, enclosure lengths < 100 KB treated as unknown, tombstones and external-mode YouTube excluded (`FakeYouTubeCapabilitiesSource`), YouTube included once `downloads` turns true | M2, M6, M9a |
| `EffectiveSettingsResolverTest` (table-driven) | `:core:data` `commonTest`, fakes | every row of [Rules](#rules); refresh: podcast 0 → `null` while the global is 240, a 0-group plus a 60-group → 60 from the 60-group, only 0-groups → `null`, global 0 with no overrides → `null`; context group only for members; dependent fields; YouTube globals and `NotSupported`; attribution lists only deciding groups; `observeAutoDownload` emits once per change, including once per `downloads` flip of `FakeYouTubeCapabilitiesSource` (and not for other capability changes) | M2, M4, M6, M9a |
| `ScopeSettingsRepositoryTest` | `:core:data` `desktopTest` | validation; all-null row deleted; reschedule and channel sync invoked | M2 |
| `OverrideSyncClassTest` | `:core:data` `desktopTest` with 02's capture triggers enabled | writing every override field captures exactly the five synced fields ([Synced and device-local overrides](#synced-and-device-local-overrides)); applying a received override record leaves the device-local columns unchanged; an all-null row's delete pushes the synced fields as `null` | MS2 |
| `PlayContextResolverTest` | `:core:data` `desktopTest` (06's `PlayStarterTest` drives the start rules through it on both platforms) | spec per entry point; start items for both orders; `subscribedAt` bound and its fallback; podcast `OLDEST_FIRST` unbounded; external mode all-YouTube group → null, and a start item once `inAppPlayback` turns true; DOWNLOADS context | M4, M9a |
| `OpmlWriterGoldenTest` | `:feeds` `commonTest` (desktop JVM) | grouped, flat and single-group output equal golden files; empty-group folders; escaping table; U+0008 and lone surrogates stripped; `category` percent-encoding (incl. the `neutrodyne_percent_names` groups `a,b`, `c/d`, `100%` and the literal `a%2Cb`); `nd:` attributes; YouTube excluded; passwords included; case-insensitive code-point order of titles | M3, M8 |
| `ExportRepositoryTest` | `:core:data` `desktopTest` (common part, `FileExportDestination`) and `androidHostTest` (`SafExportDestination`, `FileProvider`) | `prepare` writes `<cache>/export/{fileName}` with the right name and MIME per format; `privateLinks` and `passwords` counts; Android: `saveTo` uses `"wt"`, `shareUri` resolves through the `FileProvider`; desktop: `shareUri` null, the atomic replace leaves an existing file intact when the copy fails, a name without an extension gets `.opml`/`.json`/`.zip`; `BackupRepository.preflight` counts match | M3, M8 |
| `OpmlReaderGoldenTest` | `:feeds:jvm` `test` (kxml2) and `:core:data` `androidHostTest` (the platform parser under Robolectric), same goldens, as 03's corpus | each fixture below → golden `*.expected.json` of `OpmlDocument` plus classified preview (M3 acceptance 1); `neutrodyne_percent_names.opml` decodes exactly once (`a%252Cb` → `a%2Cb`, never `a,b`) | M3 |
| `ImportSourceSnifferTest` | `:feeds` `commonTest` with a fake `ArchiveReaderFactory`; the ZIP rows again in `:feeds:jvm` with `ZipGuard` | every row of [Sniffing](#sniffing) | M3, M8 |
| `HostileInputTest` | `:feeds:jvm` `test` (XML and ZIP cases), runs in 09's capped-heap `mutationTest` task (128 MB `maxHeapSize`, [09 Gradle test configuration](09-quality-and-release.md#gradle-test-configuration)) | generated at test time: entity DOCTYPE (billion laughs, external entity) → `ENTITY_DECLARED`; 10,000-deep nesting → `TOO_DEEP`; 100,000 feed outlines → `TOO_MANY_FEEDS`; 250,000 folder outlines → `TOO_MANY_OUTLINES`; a 10 MB attribute (also in UTF-16 with interleaved CJK characters) → `TAG_TOO_LONG`; a 60 MiB file → `TooLarge`; ZIP bomb (1 GiB of zeros in ~1 MiB) → ratio cap; zip-slip names `../../x`, `/abs`, `a\b` → `zip_path`; 10,000 entries in a backup → `zip_entries` — each fails with its error in < 2 s without `OutOfMemoryError` (M3 acceptance 3) | M3 |
| `RoundTripPropertyTest` | `:feeds` + `:core:data`, `desktopTest` | 200 seeded iterations: 0–12 groups with valid, `nameKey`-distinct names from an alphabet with `, / % & < > " '`, emoji and NFD input (normalised to NFC on creation); some groups empty; 0–40 podcasts incl. YouTube with variant bits; 0–3 memberships each; custom titles → grouped export → import into an empty DB with default options → identical names, order, colours, icons, memberships, custom titles, variants, and the empty groups; flat → identical memberships (M3 acceptance 2) | M3, M8 |
| `ImportPipelineTest` | `:core:data` `desktopTest` + MockWebServer, real refresh engine | preview statuses; wrapper and gPodder exclusions; duplicate union; memberships for already-subscribed podcasts (M3 acceptance 7); `originalUrl` redacted after commit and credentials stored (02's credential orphan-delete statement, run directly between `put` and the commit chunks — the `DbMaintenance` worker arrives only in M11b — waits for the coordinator and the credentials survive); target group; empty folders become groups only from Neutrodyne files; colours and icons survive process death between preview and commit (`sourceGroups`); filter → status mapping; `remove` never unsubscribes an already-subscribed podcast; YouTube items unsupported before M8; new groups and members get `orderKey`s after the existing ones and every new podcast a fresh `syncId` | M3 |
| `ImportFetchRunnerTest` | `:core:data` `desktopTest` + MockWebServer | status derivation for every row of the table in [7. Fetch](#7-fetch) incl. `Deferred` → `FETCH_FAILED(DEFERRED)` without looping and `Merged`; OFFLINE → retry; deadline and busy engine mutex → retry; a periodic run first → no second fetch (marker); `attempt` 20 → `NOT_ATTEMPTED`; atomic `DONE`; origin `RESTORE` for backup sessions; treat-as-played once, only `isNew = 0`; zero new-episode notifications and zero `download` rows for a 300-feed import (M3 acceptance 4); one report notification | M3 |
| `ImportFetchWorkerTest` | `:core:data` `androidHostTest`, WorkManager `TestDriver` | expedited only on API 31+; `KEEP` at commit; a Retry that lands while the worker finishes is not lost (`APPEND_OR_REPLACE`); `runAttemptCount` passed as `attempt` | M3 |
| `DesktopImportBackupLaneTest` | `:core:data` `desktopTest`, `TestClock` | a `FETCHING` session resumes after a simulated restart; offline → returns without counting; a busy refresh engine → runs again on the next tick; `laneAttempts` 20 → `NOT_ATTEMPTED`; a requested restore runs once, a second request → `RestoreRunning`; the interim session cleanup runs once per 24 h | M3 |
| `BackupCodecTest` | `:feeds` `commonTest` | DTO round trip (`AppInfoV1.abi`); unknown keys ignored; `subscriptions.opml` optional; `minReaderVersion` refusal; SHA-256 mismatch; oversize line skipped; entry whitelist; the optional 2026-10-05 fields and the staging-only `EpisodeLineV1.fc` (2026-10-06) round-trip, a file without them decodes, and an old `EpisodeRefV1` array decodes as `UpNextRefV1` | M3 |
| `BackupRoundTripTest` | `:core:data` `desktopTest` | small `SeedDatabase` → backup → clear data → Replace → equal groups, memberships, played state, positions, Up next, session, portable settings (M3 acceptance 5); `lastPlayedAt` round-trips through export → Replace; `ts = 0` lines (queue-only and download-only rows without state) restore stubs and queue entries with no state-field write and no state capture (positions still merge per the position rule); each Merge rule row (played OR, newer position, a backup position 0 never replaces a local non-zero one, favourites OR); Replace unsubscribes local-only podcasts and survives two groups swapping names; duplicate `nameKey`s in a backup merge; a stub matched by the next ingest keeps its state; a line with a newer `kv`; a re-run after a simulated stop in transactions B gives the same database; 02's credential orphan-delete statement, run directly during the credential step (the `DbMaintenance` worker arrives only in M11b), waits for the coordinator and the restored credentials survive; the N5-scale restore time is recorded; a backup written with the engine restored with `FakeYouTubeCapabilitiesSource` in external mode keeps YouTube channels, state and Up next rows and offers no YouTube re-download; restored groups, members and Up next keep the backup's `orderKey`s, and a file without them gets fresh keys in rank order; a free backup `syncId` is adopted, a taken one replaced | M3, M9a |
| `BackupCrossPlatformTest` | `:core:data` `desktopTest` and `androidHostTest` over the shared fixture archives `v1_android/` and `v1_desktop/` | an archive written by the Android host restores in a desktop database and the reverse, with identical groups, memberships, order, played state, positions, Up next and portable settings (M3 acceptance 11; plus a manual round trip in M3) | M3 |
| `RestoreMergerTest` | `:core:data` `desktopTest` | one table-driven case per cell of [Merge and Replace rules](#merge-and-replace-rules) for all four `MergePolicy` values; link policies adopt `syncId` and `uuid`, stub only queued, current, in-progress or favourite lines and hand the rest to `onUnmatched`; a `ts = 0` line with `pos > 0` restores the position and derives the proxy on an unplayed episode (`startedAt` set only when null, a new `episode_state` row stamped `posAt`, never now or 0); against a locally played episode, per policy: `RESTORE_MERGE` writes nothing when `playedAt > posAt` and writes the position (still played) when `posAt > playedAt`, `LINK_MERGE` derives played or in progress by 10's episode-state rules, the Replace policies take the line (unplayed, its position, the proxy); a staged line whose `fc` lacks `played` or `fav` leaves the local played mark and favourite untouched, and a staged `ts = 0` line merges only its position; a re-run changes nothing; the same suite runs through `LibraryMerger.mergeStaged` and is reused by 10's `FirstLinkMergerTest` | M3, MS2 |
| `RestoreWhileLinkedTest` | `:core:data` `desktopTest` with 02's capture triggers and 10's `InMemorySyncServer` | Merge while linked captures with the backup's timestamps (an older restored played mark loses to a newer server "unplayed"; a newer restored position wins on the other device); a podcast unsubscribed and a group deleted on another device after the backup are restored by Merge and stay subscribed and present on every device (fresh presence clock, revival on the server); "all synced devices" shows its counts only after the pull, clears `linkedAt` before writing, and a crash after Replace resumes to a pruned, linked account; Replace "this device only" unlinks before writing and pushes nothing; "all synced devices" resets the account and uploads; `sync.server_url` and `sync.username` are not restored while linked (MS2 acceptance 6); Merge captures carry the literal played pair, position snapshot (or reset form) and setting `v`, so a later remote derivation that zeroes the local position does not change what is pushed; a restored synced setting commits its Room intent before DataStore, and a kill between the two is completed at the next start | MS2 |
| `SnapshotWorkerTest` | `:core:data` `androidHostTest`, Robolectric + `TestDriver` | restore-pending and foreign-installation guards; `discardAndroidBackup` and a `Corrupt` auto restore release the guard; size-guard levels with 400,000 synthetic lines; interrupted write leaves the old file; disabling deletes the file and cancels the work; the library watcher fires on subscribe, unsubscribe and membership changes but not on a refresh | M3 |
| `SettingsWhitelistTest` | `:core:data` `commonTest` | every `PORTABLE` key round-trips through `settings.json`; no `DEVICE` key written; unknown and mistyped keys ignored; the engine and update-check keys of [Settings whitelist](#settings-whitelist) are classified as listed there (`youtube.engine_enabled`, `youtube.engine_updates` and `updates.check_enabled` written; `youtube.engine_*` device state and the five `updates.*` device keys never); every `PORTABLE` key of [Settings](#settings) carries the `synced` value listed there; `sync.server_url` and `sync.username` are written to backups but skipped by a restore while linked | M3, M9a, M9b, M11a |
| `SnapshotNowReceiverTest` | `:app` `androidHostTest`, Robolectric, `FakeBackupRepository` | result `RESULT_OK` with `ok at=… bytes=… level=…` after a write; `disabled`, `guarded` and `error …` mapped to `RESULT_CANCELED`; a write slower than 8 s → `pending` and the write still completes; the merged `release` manifest declares the receiver exported, with permission `android.permission.DUMP` and no intent filter | M3 |
| `BackupRulesXmlTest` | `:app` unit test | parses the three XML resources: include-only `cloud-backup` and `device-transfer` sections both present, no `cross-platform-transfer`, `disableIfNoEncryptionCapabilities="true"`, `requireFlags` in `xml-v28`, only `backup/.none` in the base file | M3 |
| `FirstLaunchRestoreTest` | GMD, both drivers | fresh DB + snapshot → Merge restore of library, groups, history and Up next; a podcast subscribed before the worker runs survives; existing DB → nothing; `NewerFormat` → snapshot kept, after an "update" (existing DB) `foreignPending` → Restore merges; a restored `sync.server_url` yields `SyncStatus.Reconnect` and no outbox row (MS2 acceptance 6) | M3 |
| `ExternalImportActivityTest` | GMD (API 26, 36) | VIEW `content://…/x.opml` as `application/octet-stream` (alias on 36, not on 26); SEND `text/xml`; VIEW backup ZIP → restore preview; activity finished after the copy; 50 MiB message | M3 |
| `SafTruncationTest` | GMD | writing a shorter OPML over a longer document yields a valid file (`"wt"`) | M3 |
| `DesktopImportInputsTest` | `:feature:importexport` `desktopTest` (`runComposeUiTest`) with a fake `DesktopOpenHandler` feed | a dropped `.opml` opens the import preview, a dropped backup ZIP the restore preview, a Takeout ZIP the import preview; three dropped files → three sessions, one preview; a > 50 MiB file → `TooLarge`; an unreadable path → `Unreadable` | M3, MD2 |
| `bmgr` procedure | 09's nightly `bmgr` job on the published release APK (`SnapshotNowReceiver` trigger) and by hand, 09 checklist | [Testing with bmgr](#testing-with-bmgr) (M3 acceptance 6; M6 acceptance 7 for `Podcasts/`); M11b: a second device set up from the first one's Google backup, then the published APK downloaded from the GitHub release page and installed, gets the library back at install (open question 17) | M3, M6, M11b |

Fixtures (`feeds/src/test/resources/opml/`): `antennapod_flat_atom.opml` (`type="atom"`), `pocketcasts_feeds_wrapper.opml` (OPML 1.0, `feeds` wrapper, no `title`), `overcast_basic.opml`, `overcast_extended.opml` (playlists, nested episodes, `subscribed="0"`), `gpodder_sections.opml` (Audio/Video sections, `text` = description, `url` fallback), `freshrss_nested.opml` (nested folders and `category`), `google_broken.opml` (raw `&`, missing `/>`, `&nbsp;` → salvage), `neutrodyne_hybrid.opml`, `neutrodyne_flat.opml`, `neutrodyne_group_share.opml`, `neutrodyne_percent_names.opml` (groups `a,b`, `c/d`, `100%` and the literal `a%2Cb`: export encodes, import decodes exactly once), `iscomment_subtree.opml`, `include_and_link.opml`; encodings `utf8_bom.opml`, `utf16le_bom.opml`, `iso8859_1_declared.opml`, `leading_newline.opml`, `cp1252_declared_utf8.opml` (replacement characters, no crash). `feeds/src/test/resources/backup/`: directories `v1_minimal/`, `v1_full/`, `v1_without_sync_fields/` (written before the scope revision), `v1_android/`, `v1_desktop/`, `future_minreader2/`, `sha_mismatch/` holding the archive entries as plain files; a test helper zips each at test time (no committed binary archives, so every fixture stays a reviewable text diff). YouTube format fixtures are 04's (`feeds/src/test/resources/import/`). Hostile inputs are generated, never committed.

---

## Error handling and failure modes

Serves N1, N9. Expected failures are values ([01 Errors](01-foundation.md#errors)); messages are Compose string resources through `UiText` (final wording 08), identical on both platforms unless a row names one.

```kotlin
// :core:domain
sealed interface ImportError {
    data object TooLarge : ImportError                          // > 50 MiB
    data object Unreadable : ImportError                        // grant lost, provider failure
    data class Network(val error: NetError) : ImportError       // import from URL
    data class Unsupported(val reason: String) : ImportError    // "zip", "tgz", "json", "single_feed", "unknown"
    data class Hostile(val reason: String) : ImportError        // "entity", "tag_too_long", "too_deep", "too_many_feeds",
                                                                // "too_many_outlines", "zip_bomb", "zip_path", "zip_entries"
    data object Empty : ImportError                             // no feed entries found
    data object Storage : ImportError
    data object SessionGone : ImportError
}
sealed interface BackupError {
    data object NotABackup : BackupError
    data class NewerFormat(val minReaderVersion: Int) : BackupError
    data class Corrupt(val entry: String) : BackupError
    data object TooLarge : BackupError
    data object Io : BackupError
    data object Storage : BackupError
    data object RestoreRunning : BackupError
    data object DestinationUnavailable : BackupError
    data object Unsupported : BackupError                    // Android-only members called on the desktop
}
sealed interface ExportError { data object Io : ExportError; data object Storage : ExportError
                               data object DestinationUnavailable : ExportError }
```

| Failure | Behaviour | User sees |
|---|---|---|
| Payload > 50 MiB | rejected before parsing | "This file is too large to be a subscription list (limit 50 MB)." |
| Grant lost or provider error (Android) | `Unreadable` | "Couldn't open this file. Try choosing it from inside Neutrodyne." |
| Desktop: the file cannot be read (permissions, a refused macOS folder-privacy prompt, a drive that went away) | `Unreadable` | "Couldn't open this file. Check that Neutrodyne may read it." (macOS: with a pointer to Privacy & Security) |
| Entity declaration, overlong markup, depth, counts, ZIP caps, path-traversal entry names (incl. Android's `ZipException`) | `Hostile`, nothing written | "This file can't be imported safely." |
| Malformed XML | relaxed, then salvage | banner "This file is damaged; folders could not be read" |
| No feed outlines | `Empty` | "No podcasts found in this file." |
| A single feed instead of a list | `Unsupported("single_feed")` | "This is a podcast feed, not a list of subscriptions." |
| Process death during preview | session and items persisted | resume banner from `observeOpenSessions()` (08) |
| Process death during commit | per-chunk transactions; `confirm` is re-runnable (already `QUEUED` items skipped); `COMMITTED` only after the last chunk | progress resumes |
| Worker stopped by quota or constraints | `Result.retry()`; the attempt marker prevents double work | progress continues later |
| `nameKey` or `feedKey` conflict at commit (concurrent edit or subscribe) | reuse the group / treat as already subscribed ([02 Import commit](02-data-model.md#import-commit)) | — |
| Disk full (payload, backup, snapshot) | `Storage`; atomic rename keeps the previous snapshot | "Not enough storage space." |
| SAF destination gone or read-only (Android); a desktop target folder not writable, or the drive gone | `DestinationUnavailable`; on the desktop an existing target file stays untouched (atomic replace) | "Couldn't write to the chosen location." |
| Backup from a newer app | `NewerFormat` | "This backup was made by a newer version of Neutrodyne. Update the app to restore it." Once both M3 and M11a exist (M11a may land first), the dialog and notification 3004 add "Check for updates", opening Settings › Updates (`neutrodyne://open/settings/updates`, [08 Updates settings](08-ui-ux.md#updates-settings)), where "Check now" works even with "Check for updates" off and the update card links to the newer release on GitHub; the user downloads and installs it with Android's installer (or through Obtainium), on the desktop with the OS installer for the asset the card offers |
| Install signed with another key: a later switch to a private release key ([09 Public key trade-offs](09-quality-and-release.md#public-key-trade-offs), [D61](../PLAN.md#3-key-decisions)), or a third-party build of `ch.lkmc.neutrodyne` signed with its own key, installed after an uninstall | Android does not restore the Auto Backup data at install (signature check, [Platform constraints](#platform-constraints)); the app starts empty | onboarding; the user restores a manual backup ZIP |
| Damaged backup | `Corrupt(entry)` | "This backup file is damaged (episodes.jsonl)." |
| Restore interrupted (process death, system stop; on the desktop a quit) | the host re-runs `RestoreRunner` idempotently (`RestoreWorker`; the desktop lane at the next start) | banner continues |
| Replace requested on a linked device | the reach question first ([Restore while linked](#restore-while-linked)); with "this device only" the unlink completes before any write | "Replace the library on this device only or on all synced devices?" |
| Unlink fails before a "this device only" Replace (server unreachable) | the local unlink proceeds anyway (10: offline unlinks are allowed); the device stays in the server's list until revoked | — |
| Snapshot over 20 MB at level 3 | previous snapshot kept, error recorded | Settings › Backup: "Android backup couldn't be updated: library too large" |
| YouTube feeds paused during an import (`Deferred`) | item `FETCH_FAILED(DEFERRED)`, session reaches `DONE`; the periodic refresh loads the channels later | report group "Will load later" |
| Foreign snapshot waiting on an existing database | guard keeps it; `foreignPending` | banner "Restore your library from Android backup?" (Restore / Discard) |
| No screen lock (Android; cloud backup skipped by Android, PO-15) | nothing to do in-app | Settings › Backup note "Android backup needs a screen lock" when `KeyguardManager.isDeviceSecure` is false |
| Process death (or desktop quit) inside the undo window | delete stays final; orphan channel swept at start-up (Android) | — |
| `POST_NOTIFICATIONS` not granted | report notifications skipped; never requested for imports | the report screen is reachable from the import banner |

---

## Delivery by milestone

Shared code lands for both platforms in the milestone named; "Android" and "desktop" mark platform hosts.

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | M0a: `:feature:importexport` and `:feature:groups` as KMP feature stubs; 01 ships the backup rule files with a single `settings.preferences_pb` include (Android). M0b: nothing here (the desktop window shows empty destinations) |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | M1a: `FeedRepository.pagedFeed` for All and Podcast (ordering, paging configuration, `includeInAll`) on both platforms |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | `GroupNames` in `:feeds` (with the `expect` NFC helper), `GroupPalette`, `GroupIcons`; `GroupRepository` (CRUD, `orderKey` with one-row `reorder`, membership with member `orderKey`s from podcast screen, editor and library multi-select, delete with undo, `GroupMerger`); `GroupChannelSync` with `GroupNotificationChannels` (Android) and `NoGroupChannels` (desktop); complete `FeedRepository` (tabs, prefs, counts, visits, `countUnplayed`); `EffectiveSettingsResolver` for refresh interval and notifications; `ScopeSettingsRepository` and group/podcast settings screens for those fields; group actions Refresh and Mark all played; `groups.*` keys with their sync classification; the same group feeds, counts, filters and editor in the desktop window (M2 acceptance 14) |
| [M3](../PLAN.md#m3-import-export-and-backup) | Both platforms: `:feeds`: `OpmlWriter`, the `OpmlReader` and archive interfaces, `ImportSourceSniffer`, `BackupCodec` with the optional `syncId`, `orderKey`, `ok`, `lp` and `platform` fields; `:feeds:jvm`: `XmlPullOpmlReader`, `ZipGuard`, `ZipArchiveWriter`; `:core:data`: `ImportRepository`, `PayloadStore`, `ImportClassifier`, `ImportFetchRunner`, `OpmlExporter`, `ExportRepository`, `BackupRepository`, `BackupWriter`, `RestoreRunner`, `RestoreMerger` (restore policies), `LibraryMerger`, `ExportFilesCleaner`; `:feature:importexport` preview/progress/report, export dialog, backup and restore; Share (Android) or Export (desktop) group as OPML; `BackupCrossPlatformTest` (M3 acceptance 11). Android: `ImportFetchWorker`, `RestoreWorker`, `AutoSnapshotWorker`, `WorkManagerSnapshotScheduler`, `FirstLaunchRestoreInitializer`, the library watcher, the foreign-snapshot banner, `ExternalImportActivity` with alias, the shell-only `SnapshotNowReceiver` (the nightly `bmgr` job's trigger on the published release APK) and the debug build's "Write snapshot now" row, final rules XML, `cache/export/` FileProvider path, `import_backup` notifications 3001–3004. Desktop: `FilePayloadSource`, `FileExportDestination`, the `import-backup` lane (imports, restores, interim session cleanup), file dialogs and drops onto the window, `NoSnapshotScheduler` with `SnapshotStatus.supported = false`. Both: YouTube items reported as unsupported; interim import-session cleanup; `backup.*` keys |
| [M4](../PLAN.md#m4-playback-core) | `PlayContextResolver` (called by `:playback:core`'s `PlayStarter`, 06); resolver playback fields (speed, skip silence); scoped writes for 06's commands; group action Play |
| [M6](../PLAN.md#m6-downloads) | Auto-download and delete-after-played resolution, `observeAutoDownload`; `downloadAllEstimate` and "Download all unplayed"; re-download offer after restore (Android UIDT, desktop `downloads-manual` lane); `bmgr` check that downloads are excluded |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | YouTube in OPML import/export (`nd:source`, `nd:ytVariants`); NewPipe, LibreTube, Takeout and URL-list files through the pipeline; handle resolution in `ImportFetchRunner`; NewPipe JSON export; pre-filled "YouTube" target group |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) | M9a: with the engine, YouTube auto-download globals apply (capability `downloads` read from `YouTubeCapabilitiesSource`; `observeAutoDownload` re-emits on capability changes); YouTube in "Download all" and in the re-download offer; YouTube items in play contexts (`inAppPlayback`); restore into external mode tested; `youtube.engine_enabled` travels in backups. M9b: `youtube.engine_updates` travels in backups (no other change here). The desktop gets the same behaviour with its engine in MD3 (no change here) |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | No logic change; 08 renders mosaics to files (palette colours use `ArtColors` from M2) |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | M11a: `updates.check_enabled` travels in backups and in Auto Backup's `settings.preferences_pb` (through the derived whitelist, so nothing changes here if M11a lands before M3), the update check's device keys and `noBackupFilesDir/updates/last-check.json` never; "Check for updates" on `NewerFormat` (added by whichever of M3 and M11a lands later). M11b: `db-maintenance` (Android) and `DesktopMaintenanceLane` take over import-session cleanup from `AutoSnapshotWorker` and the `import-backup` lane; counts and feed budgets verified at the N5 scale; rules XML re-verified on API 26/28/31/36/37; restore at install of the published release APK, downloaded from the GitHub release page, on a newly set-up device checked; cross-device gate rows that touch groups and restores (M11 acceptance 14) |
| [MD1](../PLAN.md#md1-desktop-playback) | No change here: the desktop's `DesktopPlaybackController` runs `PlayStarter` over `PlayContextResolver` (MD1 acceptance 3) |
| [MD2](../PLAN.md#md2-desktop-shell-behaviours-and-os-integration) | The `.opml` association and the single-instance hand-off feed [Desktop inputs](#desktop-inputs) (11 registers them; `DesktopImportInputsTest`; MD2 acceptance 4) |
| [MS0](../PLAN.md#ms0-sync-groundwork) | No code here: 02's capture triggers cover this document's tables; `SyncCaptureTest` exercises group, member, override, import-commit and restore writes (MS0 acceptance 3) |
| [MS2](../PLAN.md#ms2-client-sync) | `RestoreMerger`'s `LINK_MERGE` and `LINK_REPLACE` policies with `onUnmatched` and ID adoption, reached through `LibraryMerger` by 10's `FirstLinkMerger`; `GroupChannelSync.onUuidChanged`; [Restore while linked](#restore-while-linked) (`captureAt` with backup timestamps, the reach question, the settings rule); the "Reconnect" path after Android's first-launch restore; `OverrideSyncClassTest`, `RestoreMergerTest` link cases, `RestoreWhileLinkedTest` (MS2 acceptance 3 and 6) |
| [M15](../PLAN.md#74-after-v10-v1x-themes) | Scheduled backup to a user folder (Android SAF tree, desktop folder through the `import-backup` lane); Overcast listening-history import if approved |

---

## New names introduced here

| Name | Kind | Module |
|---|---|---|
| `GroupDraft`, `GroupEdit`, `GroupPalette`, `GroupIcons`, `GroupCounts.WINDOW_DAYS` | group model and rules | `:core:model` |
| `GroupNames` (moved 2026-10-05 from `:core:model`; package `ch.lkmc.neutrodyne.feeds.groups`) | name rules shared with the sync server | `:feeds` |
| `GroupRepository.observeMemberships` (requested by 08) | addition to a canonical interface | `:core:domain` |
| `DeletedGroupToken`, `GroupError` | domain types | `:core:domain` |
| `FeedTab`, `FeedCounts`, `VirtualCounts`, `FeedPrefs`, `DownloadAllEstimate` | feed model | `:core:model` |
| `FeedRepository` members `observeVirtualCounts`, `observeTabs`, `observePrefs`, `setFilters`, `setHideOlderThanDays`, `setFeedOrder`, `markVisited`, `countUnplayed`, `downloadAllEstimate` | additions to a canonical interface | `:core:domain` |
| `ScopeSettingsRepository`, `ScopedSettingsView`, `SettingOverrides`, `SettingField`, `GroupPlaybackHint` | per-scope settings | `:core:domain` / `:core:model` |
| `SettingSource`, `Effective`, `EffectivePlayback`, `EffectiveAutoDownload`; `EffectiveSettingsResolver` members | resolver API | `:core:model` / `:core:domain` |
| `PlayContextResolver`, `PlayContextSpec` | play contexts | `:core:domain` / `:core:model` |
| `ImportOptions`, `SourceGroup`, `CreateImportOptions`, `ImportSessionView`, `ImportItemView`, `ImportItemFilter`, `GroupProposal`, `ImportWarning`, `ImportError` | import API | `:core:model` / `:core:domain` |
| `ExportRepository`, `ExportRequest`, `ExportFormat`, `ExportLayout`, `PreparedExport` | export API | `:core:domain` / `:core:model` |
| `RestoreMode` (`MERGE`, `REPLACE`), `RestoreCategory`, `RestoreRequest`, `RestoreProgress`, `BackupPreview`, `BackupSummary`, `SnapshotStatus`, `PrivacyCounts`, `BackupError`, `ExportError`; `BackupRepository` members `preflight`, `restoreAndroidBackup`, `discardAndroidBackup`, `writeSnapshotNow` | backup API | `:core:model` / `:core:domain` |
| `LinkedRestoreReach` (`THIS_DEVICE`, `ALL_DEVICES`), `RestoreRequest.linkedReach`, `BackupPreview.linkedServerHost` and `writtenOn`, `SnapshotStatus.supported`, `BackupError.Unsupported`, `PreparedExport.shareUri` nullable *(2026-10-05)* | backup API additions | `:core:model` / `:core:domain` |
| `LibraryMerger` (`mergeStaged`), `MergePolicy` (`RESTORE_MERGE`, `RESTORE_REPLACE`, `LINK_MERGE`, `LINK_REPLACE`), `MergeSummary`, `GroupChannelSync` (`sync`, `onUuidChanged`, `onDeleted`) *(2026-10-05)* | ports used by 10 | `:core:domain` / `:core:model` |
| `RestoreMerger`, `StagedLibrary`, `LibraryMergerImpl`, `RestoreRunner`, `ImportFetchRunner`, `GroupMerger`; ports `PayloadSource`, `ExportDestination`, `ImportWorkScheduler`, `SnapshotScheduler` *(2026-10-05)* | shared implementation | `:core:data` `commonMain` |
| `ContentPayloadSource`, `SafExportDestination`, `WorkManagerImportWorkScheduler`, `WorkManagerSnapshotScheduler`, `ExportShareUris`; `GroupNotificationChannels` now implements `GroupChannelSync` *(2026-10-05)* | Android hosts | `:core:data` `androidMain` |
| `DesktopImportBackupLane` (lane name `import-backup`), `FilePayloadSource`, `FileExportDestination`, `LaneImportWorkScheduler`, `NoSnapshotScheduler`, `NoGroupChannels` *(2026-10-05)* | desktop hosts | `:core:data` `desktopMain` |
| `ImportOptions` fields `restoreRequestedAt`, `linkedReach`, `laneAttempts` *(2026-10-05)* | session options | `:core:model` |
| `OpmlDocument`, `OpmlEntry`, `FolderRef`, `OpmlLimits`, `ParseMode`, `OpmlFailure`, `OpmlReadResult`, `OpmlReader` (an interface since 2026-10-05), `ExportDocument`, `ExportGroup`, `ExportFeed`, `OpmlLayout` | OPML formats | `:feeds` |
| `XmlPullOpmlReader` (name fixed by 03), `MarkupGapGuard`, `ZipGuard` (moved from `:feeds`), `ZipArchiveWriter` *(2026-10-05)* | JVM implementations | `:feeds:jvm` |
| `ImportEntry`, `ImportGroup`, `ImportDocument`, `ImportDocuments`, `SniffResult`; `ArchiveReaderFactory`, `ArchiveReader`, `ArchiveWriterFactory`, `ArchiveWriter`, `ArchiveCaps`, `ArchiveException` (replaces `ZipGuardException`, 2026-10-05) | import formats and archive interfaces | `:feeds` |
| `BackupManifest`, `BackupKind`, `AppInfoV1`, `CountsV1`, `EntryInfo`, `LibraryV1`, `PodcastV1`, `GroupV1`, `MemberV1`, `OverridesV1`, `CredentialV1`, `EpisodeLineV1`, `EpisodeRefV1`, `QueueV1`, `SessionV1`, `SettingsV1`, `SettingValueV1`; *2026-10-05:* `UpNextRefV1` and the optional fields `PodcastV1.syncId`, `GroupV1.orderKey`, `MemberV1.orderKey`, `UpNextRefV1.ok`, `EpisodeLineV1.lp`, `AppInfoV1.platform` | backup DTOs v1 | `:feeds` |
| `ImportClassifier`, `PayloadStore` (`adopt`), `OpmlExporter`, `BackupWriter`, `ExportFilesCleaner` (order 300) | implementation | `:core:data` `commonMain` |
| `GroupNotificationChannels`, `FirstLaunchRestoreInitializer` (order 110) | Android implementation | `:core:data` `androidMain` |
| `ExternalImportViewModel` | Android implementation | `:app` |
| `.OpmlByExtensionAlias`, `@bool/neutrodyne_api31_or_newer`, `@style/Theme.Neutrodyne.Translucent`, `@string/import_into_neutrodyne` | manifest and resources | `:app` |
| `SnapshotNowReceiver` (2026-10-05; exported, `android.permission.DUMP`, no intent filter; result data `ok at=… bytes=… level=…`, `disabled`, `guarded`, `pending`, `error …`) | shell-only snapshot trigger for the `bmgr` job | `:app` (`main`) |
| `res/xml-v28/backup_rules.xml`; `file_paths.xml` entry `export` | resources | `:app` |
| Notification IDs on `import_backup`: 3001 import report, 3002 import ready to review, 3003 restore result, 3004 Android backup needs a newer app or is waiting to be restored, 3010 scheduled-backup folder lost (M15) | constants | `:core:data` |
| `FETCH_FAILED` details `DEFERRED`, `NOT_ATTEMPTED`; `ImportError.Hostile` reasons | `import_item.errorDetail` and error values | `:core:data` / `:core:domain` |
| `groups.*` and `backup.*` keys of [Settings](#settings), with their `synced` flags (2026-10-05) | setting keys | `:core:model` registry |

---

## Open questions

Numbering is stable; resolved items stay listed with their resolution.

1. Resolved: PLAN 5.2 shows `import-{sessionId}` as expedited on API 31+ only (same choice as 03 for `refresh-now`, [D25](../PLAN.md#3-key-decisions)); `KEEP` at commit, `APPEND_OR_REPLACE` for fix-ups ([7. Fetch](#7-fetch)).
2. Resolved: no `cross-platform-transfer` section, as 01's P28 says. The element requires `<platform-specific-params bundleId teamId contentVersion>` naming an iOS app and transfers data only to that app; Neutrodyne has none, and a section without those parameters would be invalid ([Rules XML](#rules-xml)). Both Android sections must stay present, because a missing one means "fully enabled for all content".
3. Recorded in [PO-15](../PLAN.md#48-further-product-owner-decisions): devices on Android 8.0–8.1 and devices without a screen lock get no automatic library backup (default: accept; alternative: allow unencrypted backup on API 26–27).
4. Resolved: [D45](../PLAN.md#3-key-decisions) is amended with these rules (charging any `true`, video any `false`, dependent fields cleared while a group's auto-download is off, intro/outro skip podcast-only, context-group playback values only for member podcasts, Up next items included).
5. Resolved: [D33](../PLAN.md#3-key-decisions) states that opt-in passwords are stored in plain text in `library.json`; a passphrase-encrypted archive is v1.x.
6. Resolved: 02 defines `nameKey` as 05's `GroupNames` key, `NFC(normalized.lowercase(Locale.ROOT))` — since the scope revision computed in common `:feeds` as `NFC(normalized.lowercase())`, Kotlin's invariant-locale lowercase, which is the same on every JVM runtime ([Names](#names)).
7. **Owner 02:** resolved — `ImportDao.pagedItems(sessionId, statuses)`, `FeedDao.countUnplayed`, download-all candidates (02 open question 7 is answered in [Group actions](#group-actions): Up next and current are not excluded), `e.isNew = 0` in "played except newest", the null-anchor tail, cleanup callable by `AutoSnapshotWorker`, `BackupDao.observeLibraryShape()` ([02 Backup export](02-data-model.md#backup-export)), and `customTitle`, `credentialId` and (restore) `artworkUrl` in the [Import commit](02-data-model.md#import-commit) and restore inserts.
8. Resolved in 06 ([06 QueueProjector](06-playback.md#queueprojector) edge cases): a `play_session` rewritten by a restore is loaded paused and never auto-plays (D43).
9. Resolved: 07 consumes `observeAutoDownload()` and confirms the six `downloads.*` keys named in [Rules](#rules) (07 open question 10); `downloadAllEstimate` uses [07 Estimates](07-downloads.md#estimates).
10. Resolved: 03 provides `PodcastRepository.retry(podcastId, refresh = false)` and `FeedRefresher.run` with `force = true`, origins `IMPORT`/`RESTORE`; the add sheet's `SubscriptionList(url)` action calls `ImportRepository.create(ImportSource(url, null))`; 03's `FeedOutcome.Deferred` notes that an import item ends as `FETCH_FAILED(DEFERRED)`.
11. **Owner 08:** visit rule, banners, preview models, palette and icon rendering, `observeMemberships` and `writeSnapshotNow` are settled (08 open question 11; since 2026-10-05 the "Write snapshot now" row is for debug builds only, 18). New for 08: the export flow is `ExportRepository.prepare` → private-link warning → save or share ([Destinations](#destinations)); the backup flow adds `BackupRepository.preflight` before `CreateDocument`; the report gains a "Will load later" group (`FETCH_FAILED` with `DEFERRED`); the Library banner for `SnapshotStatus.foreignPending` with Restore / Discard.
12. Moved to [PO-25](../PLAN.md#48-further-product-owner-decisions) (defaults: Replace exposed behind a confirmation; Merge leaves settings unchecked; flat OPML export keeps no empty groups, group order, colours or icons).
13. **Unverified** (checked in their milestone): duplicate `<include>` paths with different `requireFlags`, an include of a non-existent path on API 26–27, and the API 28–30 local-transport key `fake_encryption_flag` (M3, `bmgr`); providers that reject `"wt"` (M3); Google Drive's file naming for `text/x-opml` (M3); Overcast basic export shape (partially verified), Podcast Addict's OPML shape and OPML support in Apple Podcasts on iOS 26 (fixtures added when samples exist).
14. Overcast "extended" OPML carries per-episode played state and progress; importing it as listening history (match by enclosure URL) is proposed for M15, not v1.
15. Resolved by [D70](../PLAN.md#3-key-decisions): the automatic first-launch restore runs in **Merge** mode; a foreign snapshot found on a non-fresh database is offered through a banner ([First-launch restore](#first-launch-restore)).
16. Resolved 2026-10-05 in [09 Notices](09-quality-and-release.md#notices) with the proposed default below (M11a; revised 2026-10-05 for PO-31): `updates.check_enabled` travels in backups and in Auto Backup's `settings.preferences_pb`, but `updates.first_run_choice_done` is a `DEVICE` key, so a restored or new device would show the first-run update card again. Proposed default: `UpdateNotices` raises `FIRST_RUN_CHOICE` only while `updates.check_enabled` is true; when the restored value is false it sets `updates.first_run_choice_done` without showing the card. A user who turned checks off is then neither asked again nor finds checks silently re-enabled, a user who kept them on sees the disclosure once more on the new device, and the card never says that checks are on while they are off ([Settings whitelist](#settings-whitelist)).
17. **Unverified** (M11b, two-device check in the `bmgr` row of [Testing](#testing)): with GitHub as the only channel, device setup never reinstalls Neutrodyne, so the library comes back only through the restore at install when the user later installs the APK. Whether that restore still reads the old device's data set (AOSP's "ancestral" set) after setup has finished is checked then. If it does not, Settings › Backup (08) and the README's "Install and update" section (09) tell users who change phones to carry a manual backup over ([Auto Backup](#auto-backup)). The second half of this question (data restore into an APK with a v3.1 rotation lineage) was removed 2026-10-05 (PO-35): there is no release key and no rotation, and every build carries the committed key's certificate ([Platform constraints](#platform-constraints)).
18. Resolved 2026-10-05 (scope revision). First decided for PO-35's debuggable published builds; PO-35 was re-resolved, and published APKs are now non-debuggable release builds. The shell-only `SnapshotNowReceiver` stays the `bmgr` job's snapshot trigger, because the shell holds `DUMP` whatever the app's flags ([Testing with bmgr](#testing-with-bmgr)). 08: the Settings › Backup row "Write snapshot now" is shown in debug builds only (`BuildInfo.debug`, which replaces `BuildInfo.devTools`). 09: `scripts/ci/bmgr-check.sh` runs on the published release `x86_64` APK (package `ch.lkmc.neutrodyne`), sends `am broadcast --include-stopped-packages -n ch.lkmc.neutrodyne/ch.lkmc.neutrodyne.SnapshotNowReceiver`, asserts `result=-1`, reinstalls without `-t` and no longer uses `run-as`. 01: done — the merged manifest lists the receiver, `AndroidAppGraph` declares `inject(target: SnapshotNowReceiver)`, and the framework-component rule and the start-up ordering test include it; PLAN N7, 7.2 and D2 name it as the one exception to the test-hook rule.
19. Resolved 2026-10-05 (scope revision): the hand check of what `adb backup` and `run-as` expose on a debuggable published APK (former [Platform constraints](#platform-constraints) row, risk P11) is dropped — published APKs are not debuggable, and P11 is retired.
20. **Owner 10 (MS2)** — answers the 05 part of [10 Open questions](10-sync.md#open-questions) 1 and resolves its question 5: `RestoreMerger` offers the link policies through `LibraryMerger.mergeStaged` with `onUnmatched` for parking ([RestoreMerger](#restoremerger)); `captureAt` with backup timestamps is used by restores while linked ([Restore while linked](#restore-while-linked)); group-`uuid` changes call `GroupChannelSync.onUuidChanged`, which copies importance, sound, lights, vibration and badge ([Notification channels](#notification-channels)). 10 should: write its staging directory under the archive's entry names with `BackupCodec`, map `lastPlayedAt` to the new optional `EpisodeLineV1.lp` and Up next keys to `UpNextRefV1.ok`, park the records handed to `onUnmatched`, and call `LibraryMerger` (`:core:domain`) rather than `RestoreMerger`, because `:sync:impl` may not depend on `:core:data`.
21. **Owners 11 and PLAN D85 (M3):** imports and restores need a host on the desktop that survives a quit; this document uses a `DesktopJobRunner` lane named `import-backup` (`DesktopImportBackupLane`, `:core:data` `desktopMain`; poked by confirm, fix-ups and restore requests; it also runs the interim import-session cleanup until M11b). 11's lane table and D85's lane list should name it; proposed position in the tick order: after `refresh`. **Resolved 2026-10-05 (scope revision):** 11's tick order, [Lanes](11-desktop.md#lanes) table, new names and M3 delivery row and PLAN D85 name it. Alternative: run both on `@ApplicationScope` with a band-200 initializer that resumes unfinished sessions (no lane, but no backoff or diagnostics).
22. **Owners 02 and 11, PO (M3)** — answers [02 Open questions](02-data-model.md#open-questions) 13: 05 supports a local desktop recovery snapshot. The `import-backup` lane would write `<data>/backup/recovery-snapshot.zip` with `BackupWriter` (kind `AUTO_SNAPSHOT`, no credentials, the size levels of [Snapshot production](#snapshot-production)) daily and 10 min after significant changes (a real desktop `SnapshotScheduler`), and 02's recovery path would restore it in Merge mode as on Android; cost: a few MB in the data directory, no network. It is not designed in, because PLAN D34 and R1.8 say the desktop has no automatic backup; until the PO decides, the desktop recovers from a manual backup or by reconnecting to sync (02's current text). PLAN registers the question as [PO-46](../PLAN.md#48-further-product-owner-decisions) (2026-10-05): the v1.0 default is no snapshot, stated as N1's desktop exception and checked by M11 AC14; the snapshot is the recommended v1.x addition.
23. **PO-37 / owner 10 (MS2):** this document classifies the All and Ungrouped view keys and `groups.show_ungrouped_tab` as `synced = true` ([Settings](#settings)), following PO-37's "feed sort/filter defaults" and the synced group view columns; 10's synced-keys table and the change brief's settings list do not name them yet. Alternative: keep them device-local (a phone and a desktop may want different filters).
24. **Unverified (M3):** macOS reading of files picked, dropped or opened from protected folders without a folder-privacy prompt ([Desktop inputs](#desktop-inputs)); Linux desktops without a file-chooser portal fall back to `JFileChooser` (08).

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise.

- OPML 2.0 specification (`text` required, `type="rss"` + `xmlUrl`, nested lists, `category` as comma-separated slash-delimited strings, `isComment`, `include`/`link`, namespaced extensions, RFC 822 dates, `text/x-opml`): http://opml.org/spec2.opml
- AntennaPod OPML writer/reader, backup agent, database exporter, automatic export worker, tags model (develop, commit 9c7ffa1): https://github.com/AntennaPod/AntennaPod · "Always add feeds from opml, even if download fails" (3.1): https://forum.antennapod.org/t/import-issues-with-opml/2676 · Google Podcasts malformed exports: https://forum.antennapod.org/t/cant-import-google-podcasts-opml-file/5363 · per-tag episode view request: https://github.com/AntennaPod/AntennaPod/issues/5222
- Pocket Casts OPML exporter, line-based importer, octet-stream filter removal, single `folder_uuid`: https://github.com/Automattic/pocket-casts-android · OPML import help (Podcast Addict, iTunes paths): https://support.pocketcasts.com/article/opml-import/ · folders: https://support.pocketcasts.com/knowledge-base/folders/
- gPodder OPML sections and `url` fallback: https://github.com/gpodder/gpodder (`src/gpodder/opml.py`, `src/gpodder/model.py`)
- Overcast extended export structure: https://github.com/hbmartin/overcast-to-sqlite · basic export sample (partially verified): https://metacast.app/blog/podcasting/opml-import-export
- FreshRSS import (innermost folder wins, `category` joined into one name): https://github.com/FreshRSS/FreshRSS/blob/edge/app/Services/ImportService.php
- Apple Podcasts without OPML (2019; iOS 26 Unverified): https://kaspars.net/blog/apple-podcasts-subscriptions
- NewPipe subscription JSON: https://github.com/TeamNewPipe/NewPipe · Takeout CSV: https://github.com/TeamNewPipe/NewPipeExtractor (`YoutubeSubscriptionExtractor.java`; a format reference only — the library is not used, [D72](../PLAN.md#3-key-decisions))
- Android MIME tables (no `.opml`): https://android.googlesource.com/platform/external/mime-support/+/refs/heads/main/mime.types · https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/mime/java-res/android.mime.types · `FileUtils.splitFileName`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/FileUtils.java
- `ContentResolver.openOutputStream` mode `"w"` may not truncate: https://developer.android.com/reference/android/content/ContentResolver · URI grant lifetime: https://developer.android.com/reference/androidx/core/content/FileProvider · `<data>` element (`pathSuffix` API 31, scheme + host): https://developer.android.com/guide/topics/manifest/data-element · Android 16 Safer Intents: https://developer.android.com/about/versions/16/behavior-changes-16
- Activity 1.13.0 and `CreateDocument(mimeType)`: https://developer.android.com/jetpack/androidx/releases/activity
- KXmlParser (BOM and declaration detection, relaxed feature, docdecl): https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java · KXmlSerializer illegal characters: https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlSerializer.java
- Auto Backup (25 MB quota, include semantics, restore at install "whether from the Play Store, during device setup …, or by running `adb` install", exclusion of `getCacheDir()`, `getCodeCacheDir()` and `getNoBackupFilesDir()`, restricted mode, `disableIfNoEncryptionCapabilities` from Android 12, `requireFlags` from Android 9 and "provide alternate resources" for 8.1 and lower, missing sections, cross-platform transfer from Android 16 QPR2 with required `platform-specific-params`): https://developer.android.com/identity/data/autobackup (re-checked 2026-10-05)
- Restore signature check ("checks the signature block of the package which uploaded the restore data against the signature of the package on-device"): https://android.googlesource.com/platform/frameworks/base/+/78dd4a7%5E%21/ · permission restore compares signing certificates and accepts signing history: https://android.googlesource.com/platform/cts/+/8282ae54186%5E%21/ (both checked 2026-10-05)
- Testing backup and restore (`bmgr`, local transport `is_encrypted=true`, uninstall and `install-multiple`, D2D test mode with the Play-services `D2dTransport`): https://developer.android.com/identity/data/testingbackup (re-checked 2026-10-05; its scripts reinstall with `install-multiple -t`, which ours omits)
- ADB access to app data (checked 2026-10-05; since the scope revision published APKs are non-debuggable, so both protect them): `run-as` rejects non-debuggable packages ("package not debuggable"): https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp · Android 12 `adb backup` excludes the data of apps targeting 31+ unless `android:debuggable="true"`: https://developer.android.com/about/versions/12/behavior-changes-12
- Shell-only receiver (checked 2026-10-05): `android.permission.DUMP` has protection level `signature|privileged|development`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml · the Shell package (`android.uid.shell`) requests `DUMP` and protects its own receivers with it: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/Shell/AndroidManifest.xml · `am broadcast` sends an ordered broadcast, waits for it to finish and prints "Broadcast completed: result=…, data=…": https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java · `--include-stopped-packages`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java · `goAsync()` still expects the receiver to finish "under 10 seconds": https://developer.android.com/develop/background-work/background-tasks/broadcasts
- Zip path traversal (`ZipFile`/`ZipInputStream` throw `ZipException` for `..` and leading `/`, apps targeting 34+): https://developer.android.com/about/versions/14/behavior-changes-14#zip-path-traversal (checked 2026-10-05)
- Preferences DataStore file location: https://github.com/androidx/androidx/blob/androidx-main/datastore/datastore/src/androidMain/kotlin/androidx/datastore/DataStoreFile.android.kt
- `NotificationManager.deleteNotificationChannel` (re-creation "un-deletes" with old settings) and `createNotificationChannel` (rename): https://developer.android.com/reference/android/app/NotificationManager (re-checked 2026-10-05) · channel limits: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/PreferencesHelper.java
- FGS types (`dataSync` for import/export and backup): https://developer.android.com/develop/background-work/services/fgs/service-types · expedited work (foreground service before Android 12, `getForegroundInfo` required, `RUN_AS_NON_EXPEDITED_WORK_REQUEST`; re-checked 2026-10-05): https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work · Android 16 job quotas: https://developer.android.com/about/versions/16/behavior-changes-all · WorkManager 2.12.0: https://dl.google.com/android/maven2/androidx/work/work-runtime/maven-metadata.xml
- Room 3 (`withoutRowId`, `@RawQuery`, paging converter, no `Uuid` converter before 3.1.0-alpha01): https://developer.android.com/jetpack/androidx/releases/room3 · `LimitOffsetPagingSource` invalidation: https://github.com/androidx/androidx/blob/androidx-main/room3/room3-paging/src/commonMain/kotlin/androidx/room3/paging/LimitOffsetPagingSource.kt · Paging 3.5.1: https://dl.google.com/android/maven2/androidx/paging/paging-runtime/maven-metadata.xml
- kotlinx.serialization 1.11.0 (stream APIs experimental, so JSONL is written line by line): https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml · https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/json/jvmMain/src/kotlinx/serialization/json/JvmStreams.kt
- SQLite row values (3.15) and `VACUUM INTO`: https://www.sqlite.org/rowvalue.html · https://www.sqlite.org/lang_vacuum.html
- `reorderable` 3.1.0 (Apache-2.0): https://repo1.maven.org/maven2/sh/calvin/reorderable/reorderable/maven-metadata.xml · https://github.com/Calvin-LL/Reorderable/blob/main/LICENSE
- Notification permission (contextual request): https://developer.android.com/develop/ui/views/notifications/notification-permission

Scope revision, checked 2026-10-05:

- `NotificationChannel` setters (importance, sound, lights, vibration and badge "only modifiable before the channel is submitted"; lockscreen visibility and, without DND access, bypass DND "only modifiable by the system and notification ranker"): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/NotificationChannel.java
- AWT `FileDialog` ("Filename filters do not function in Sun's reference implementation for Microsoft Windows"; `SAVE` mode; `getFiles`): https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/FileDialog.html
- XDG desktop portal `FileChooser` (`OpenFile`, `SaveFile` with `current_name` and `filters`, one `file://` URI in the response): https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html
- Kotlin `String.lowercase()` in common code uses the invariant locale: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html · `kotlin.uuid.Uuid.random()`: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/-uuid/-companion/random.html
- R8 keeps manifest-declared components as entry points: https://developer.android.com/build/shrink-code
- Room KMP (no multi-instance invalidation off Android): https://developer.android.com/kotlin/multiplatform/room
- Okio in common code (`HashingSink`/`HashingSource` on every platform since 2.10.0): https://raw.githubusercontent.com/square/okio/master/CHANGELOG.md
- Fractional indexing (CC0-1.0), the basis of `OrderKey`: https://github.com/rocicorp/fractional-indexing
- The rest of the scope revision's facts (KMP source sets, Metro, desktop paths and inputs, sync) are cited in the owning documents: [01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands), [10 Sources](10-sync.md#sources), [11 Sources](11-desktop.md#sources).
