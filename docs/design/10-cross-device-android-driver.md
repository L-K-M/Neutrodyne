# 10.A Cross-device Android driver (E12 Android half)

> Status: implementation slice, 2026-11-10. **Android driver only.** The desktop half, the host
> coordinator, the nightly `sync-convergence` job and the real emulator/device proof are owned
> elsewhere and remain UNVERIFIED. This is not the E12 acceptance criterion and not a manual-only
> claim.

`app/src/androidTest/kotlin/ch/lkmc/neutrodyne/CrossDeviceSyncAndroidTest.kt` is the Android half
of E12's `CrossDeviceSyncTest` (09 End-to-end journeys). It drives the real `AndroidAppGraph`,
Room and `SyncController`/`KtorSyncClient` through the public sync service flow, with no Activity
and no test-only graph override. `NeutrodyneTestRunner` excludes the class before discovery unless
the instrumentation argument `crossDevice=true` is present; AGP's test engine treats assumptions
as failures, so the exclusion must happen before discovery.

## Invocation

The host installs the config into the app's private files with `adb run-as` (stdin, so the
password never reaches the shell command or logcat):

```sh
adb shell run-as ch.lkmc.neutrodyne.debug sh -c \
  'mkdir -p files/cross-device && cat > files/cross-device/config.json' < config.json
adb shell am instrument -e crossDevice true -e crossDevicePhase seed \
  -w ch.lkmc.neutrodyne.test/ch.lkmc.neutrodyne.NeutrodyneTestRunner
```

Phases run in order: `seed`, `link`, `edit`, `sync`, `dump`. Each phase writes
`files/cross-device/result.json`; the host reads it with `run-as`.

## Config contract (`files/cross-device/config.json`)

| Field | Required | Rule |
|---|---|---|
| `runId` | yes | non-blank; names the run and derives every fixture UUID |
| `serverUrl` | yes | `http://127.0.0.1:<port>` or `http://localhost:<port>`, no credentials/query/fragment |
| `username` | yes | non-blank account name |
| `password` | yes | non-empty; never logged or written to the result |
| `feedBaseUrl` | yes | same loopback rule; the seed's fixture feed URLs are built under it |
| `edits` | no | bounded operation list (below); a deterministic representative list is used when absent |

The loopback rule is enforced before any request. An arbitrary remote server is refused.

## Ownership marker (`files/cross-device/ownership.json`)

`seed` runs on a fresh app only: an existing library, link or sync residue fails the phase rather
than being cleared. It writes the marker with `runId`, `seededAtMs` and the fixture counts. Every
later phase validates the marker's `runId` before touching the database.

## Fixture (seed)

50 RSS podcasts, one episode each, two groups of 25 members, and representative played /
in-progress / favourite / Up next values. Every `syncId`, group `uuid` and episode GUID is derived
from `runId` plus the record key; order keys come from `OrderKey.rewrite`, so the seed is
byte-deterministic for the host and both devices. The writes go through the production DAOs while
`sync_state.enabled = 0`, so the capture triggers stay silent and the seed is bootstrap state.

## Link

`startLink(serverUrl, PASSWORD)` -> `confirmInsecure()` on the loopback `InsecureConnection`
state -> `submitPassword(username, password)` -> `chooseFirstLink(MERGE)` on `ChooseFirstLink` or
`ConfirmUpload`. The actual `LinkState` machine drives the answers; no token or profile row is
inserted directly. The password `CharArray` is cleared after use. A typed `Failed` state or a
timeout fails the phase.

## Edit operations

Operations are keyed by fixture index. Every mutation writes through the normal DAO writers, so
the existing capture triggers own the HLC and outbox; no outbox or accepted-clock row is injected.

| `op` | Fields | Writer |
|---|---|---|
| `played` | `podcast`, `episode`, `value` (playedAt ms) | `episodeStateDao.ensure` + `markPlayed` |
| `unplayed` | `podcast`, `episode` | `episodeStateDao.markUnplayed` |
| `position` | `podcast`, `episode`, `value` (position ms) | `episodeStateDao.markStarted` + `positionDao.upsert` |
| `fav` | `podcast`, `episode`, `fav` | `episodeStateDao.setFavorite` |
| `membership_add` | `group`, `podcast` | `groupDao.insertMember` |
| `membership_remove` | `group`, `podcast` | `groupDao.deleteMembers` |
| `group_order` | `group`, `before` | `planOrderKeyWrites` + `groupDao.setGroupOrderKey` |
| `queue_add` | `podcast`, `episode`, `before?` | `queueDao.insert` with an order key |
| `queue_remove` | `podcast`, `episode` | `queueDao.removeEpisodes` |
| `unsubscribe` | `podcast` | `podcastDao.deleteCascade` |

The phase fails when the sequence captures no outbox row, so a broken capture path cannot pass.

## Sync

`controller.syncNow()` is armed with a diagnostics baseline before the call; the completion
barrier is a new `SYNC_NOW` round summary. A round that never ran leaves no summary, so an empty
outbox cannot pass without an RPC. The phase fails on a round failure (including `clock_skew`), a
persisted `Revoked` marker, or a still-running round.

## Result contract (`files/cross-device/result.json`)

| Field | Meaning |
|---|---|
| `runId`, `phase`, `ok` | run identity and phase outcome |
| `error` | `field: reason` on failure, field/phase only; never a secret |
| `counts` | safe Row counts (podcasts, episodes, groups, members, upNext, outbox, clock, held, parked) |
| `wire` | canonical accepted wire state (`canonicalSyncSnapshot().canonicalRaw()`), `auth` fields stripped |
| `library` | materialized fixture podcasts, episodes (played/position/favourite/Up next), groups (order + members), Up next |
| `held` | held batch summary (id, heldAt, podcast/group titles) |
| `skippedClockRows` | malformed `sync_clock` rows skipped |

The wire and library objects are canonicalized with sorted object keys; array order is preserved.

## Unverified

- The host coordinator, the desktop half and the nightly job are not implemented here.
- No emulator or physical device has run this driver; the loopback server, the host-driven phase
  sequence and the real `LinkState` transitions remain unverified on Android.
- The release R8 instrumentation ABI keeps were inspected, not re-traced on a release class set
  produced from this branch.
