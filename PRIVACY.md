<!-- SPDX-License-Identifier: Unlicense -->

# Privacy

Updated for MS1 server operation (2026-10-19). The outline of 09's Privacy section; updated whenever a document adds
a network destination, final in M11b. The in-app "What Neutrodyne connects to"
list uses the same IDs as the inventory below.

## Summary

No analytics, advertising, tracking, Firebase or Google Play services in any
build; no Neutrodyne-operated server and no account at the project. The
optional sync server is the user's own. Network traffic goes only to hosts you
chose or opted into — including GitHub for the app-update and YouTube-engine
checks, both disclosed and each with an off switch. Crash reports leave a
device only through your own mail app after per-crash consent. Private feed
URLs, feed tokens and sync tokens never appear in logs, crash reports or
diagnostics.

## What stays on the device

Your library, groups, history, positions, Up next, downloads and settings stay
on the device. Credentials — private-feed passwords, the sync token — are
stored encrypted with an Android Keystore key on Android; on Windows protected
with DPAPI for the user; on macOS and Linux in a file only you can read
(PO-44).

## Where the apps connect

The full network inventory is maintained in 09's
[network inventory](docs/design/09-quality-and-release.md) (IDs `feeds`,
`add-input`, `media`, `artwork`, `chapters`, `notes-images`, `apple`, `fyyd`,
`podcastindex`, `youtube-subscriptions`, `youtube-streams`, `youtube-engine`,
`app-updates`). In short: subscribed feeds and their enclosures, artwork and
chapter hosts, the search directories you enabled, `www.youtube.com` and its
media hosts when you use YouTube channels, and `github.com` /
`{owner}.github.io` for the update check and engine updates. A connection
carries the app's version and a standard user agent; no identifier, cookie or
token leaves the device for it.

## Sync (optional)

A linked server stores your subscriptions (including private, tokenised feed
URLs), groups, played state, positions, favourites, Up next, the now-playing
episode and synced settings — Basic-auth feed passwords only while "Share feed
passwords" is on. It never receives audio, downloads, feed contents, artwork,
crash data or device-local settings. **There is no end-to-end encryption:**
whoever runs the server can read what it stores, and TLS comes from the
server's reverse proxy. The server makes no outbound request except its
optional daily update check, and logs no tokens, passwords, feed URLs or
payloads. You can unlink a device, revoke it from another device, and use
"Delete my data on the server". A server on your home network needs Android's
local-network permission (API 33+) / macOS's Local Network prompt.

The server keeps account usernames, optional Argon2id password hashes, hashed
authentication tokens, device names/platforms, link requests and a security audit
trail with IP addresses. Request logs contain route templates, counts, status
and timing, without credentials, library payloads or raw URLs. Server operators
can inspect the database and any feed passwords you chose to share.

The daily update check contacts this repository's GitHub release manifest and
GitHub's release-asset host. Turn it off with
`NEUTRODYNE_SERVER_UPDATE_CHECK=false`; the server then makes no outbound
requests. Your TLS proxy may separately contact certificate authorities.
No analytics or Neutrodyne-operated endpoint is involved.

Database backups retain authentication hashes, audit history and any shared
feed passwords. Per-account ZIP exports omit authentication state and feed
passwords, but contain private feed URLs and listening history. Both stay on
the operator's disk unless copied elsewhere. Account purge removes live library
data and its account ZIPs, and revokes credentials. Whole-server historical
backups still contain prior data until their configured retention expires.
Operators choose where off-machine copies live and when to delete them.

## Desktop

Data lives in the per-OS directories of 11's AppDirs; log files are redacted
and rotated; crash files surface in the next-start email dialog. Uninstalling
keeps the data (11's uninstall section says how to remove it). There is no
Auto Backup — a backup ZIP or a sync server moves a library.

## Backups

Android Auto Backup carries a daily library snapshot (feed URLs included,
possibly with private tokens) to your Google account, only on devices with
backup encryption (PO-15). Manual backup files contain passwords only on
opt-in (R1.9). The sync token never travels in a backup. The sync server's own
nightly backups and per-account ZIPs stay on the server's disk.

## Crash reports and diagnostics

Reports carry only allow-listed fields (versions, states, redacted log lines)
and leave through your mail app after per-crash consent (ACRA on Android; the
next-start dialog on the desktop). To request deletion of a report you sent,
reply to that mail thread.

## Permissions

Only the permissions of 01's manifest table are declared — notifications,
foreground services for playback and downloads, and `ACCESS_LOCAL_NETWORK`
only when you link a sync server on your local network. No install permission
is declared anywhere: the app never downloads or installs an update.

## YouTube engine and update checks

The engine update check contacts `{owner}.github.io` (approved manifest),
`github.com` and `release-assets.githubusercontent.com`; turn it off in
Settings › YouTube › "Engine updates". The app update check makes one
`releases/latest/download/neutrodyne-update.json` request per day to
`github.com`; turn it off in Settings › Updates › "Check for updates". The apps
download no update: your browser does, from GitHub. On the `armeabi-v7a` APK
YouTube always opens in the YouTube app (external mode).

## Contact and change history

Questions through the issue tracker. This file's history is its git log.
