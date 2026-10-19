<!-- SPDX-License-Identifier: Unlicense -->

# Privacy

v0 (M0a). The outline of 09's Privacy section; updated whenever a document adds
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
on the device. Secrets — private-feed passwords, Podcast Index API secrets and the sync token —
are encrypted with an Android Keystore key on Android; on Windows the secrets
file is protected with user-bound DPAPI; on macOS and Linux it is a file only
you can read (PO-44). Your Podcast Index API key identifier is also stored
unencrypted as a username in local credential metadata.

## Where the apps connect

Every app request exposes your IP address and the User-Agent (app version,
platform and project URL), without a tracking identifier. Directory cookies
are not stored. Authentication is separate from tracking: private feeds can
receive Basic credentials, Podcast Index receives authentication headers, and
your linked sync server receives its device token.

The inventory below uses the same stable IDs as Settings › Privacy. Provider
switches stop automatic directory requests. An explicit Apple-link lookup asks
for one-time consent when Apple is off; Podcast Index lookups require its
switch and an effective key. URLs are never automatically sent as searches.
Searches, charts and previews remain in memory until eviction or app exit.

| ID | Destination and what it receives |
|---|---|
| `feeds` | **Feeds and publishers.** Subscribed feeds, preview feeds and redirect targets receive the requested URL path and query, conditional cache headers, and Basic credentials only for the matching origin. |
| `add-input` | **Pages and feed candidates.** The page you enter or share, up to five well-known feed paths on that site, and publisher candidates receive their requested URL path and query. Candidates can use other hosts. Neutrodyne verifies up to five candidates; it does not send them to a directory. |
| `media` | **Audio hosts.** Enclosure hosts and publisher measurement redirects receive the media URL, range requests, and matching-origin credentials when needed. They can measure playback or downloads by IP and User-Agent. |
| `artwork` | **Artwork hosts.** Feed and directory artwork hosts, including Apple image CDNs, fyyd image hosts and Podcast Index artwork URLs, receive the requested image path when a cover is displayed. Directory switches do not block images already returned. |
| `chapters` | **Chapter hosts.** Hosts referenced by episode chapters receive the chapter-file URL when chapters are loaded. |
| `notes-images` | **Show-notes image hosts.** Image hosts receive the image URL when images are allowed or you choose to load them. Change this in Feeds settings. |
| `apple` | **Apple.** itunes.apple.com receives search text, country and search parameters; explicit Apple, pod.link or Overcast lookups send Apple podcast IDs. rss.marketingtools.apple.com receives the country for overall charts. Genre charts send country and genre ID to itunes.apple.com; chart resolution sends podcast IDs there. Apple receives no subscriptions, feed URLs or Podcast Index credentials. |
| `fyyd` | **fyyd.** api.fyyd.de receives search text and result-count parameters. It receives no country, subscriptions, feed URLs or API credentials. A fyyd page entered in Add is fetched as a web page. |
| `podcastindex` | **Podcast Index.** api.podcastindex.org receives search text and result-count parameters, or an explicit Podcast Index feed ID. Every request includes X-Auth-Key (API key), X-Auth-Date (timestamp), and Authorization (SHA-1 of key, secret and timestamp); the secret itself is not sent. Verifying a pair sends the fixed query “podcast”. No country, subscriptions or feed URLs are sent. |
| `youtube-subscriptions` | **YouTube subscriptions.** When you add or refresh a YouTube channel, www.youtube.com receives channel IDs and page/feed requests. When all subscribed channels fail in a library of one or two YouTube channels, the app also checks the canonical feeds of the official YouTube (UCBR8-60-B28hp2BmDPdntcQ) and Google (UCK8sQmJBp8GCxrOtXWBpyEA) channels to distinguish an outage from a deleted channel. Checks stop at the first success and results are cached for one hour. These channels are not added to your subscriptions. YouTube thumbnail hosts (i.ytimg.com, yt3.googleusercontent.com, yt3.ggpht.com) receive image paths. |
| `youtube-streams` | **YouTube playback and channel search.** With the engine, www.youtube.com receives video/channel IDs, explicit channel searches, locale, client headers and an in-memory consent cookie. googlevideo.com hosts receive media requests. Podcast search text is not sent to YouTube. |
| `youtube-engine` | **YouTube engine updates.** When enabled, the project GitHub Pages host receives approved-manifest requests; github.com and release-assets.githubusercontent.com receive engine download paths. |
| `app-updates` | **App updates.** When enabled, github.com and release-assets.githubusercontent.com receive update-manifest requests with the app User-Agent. Tapping a release or download opens your browser. |
| `sync-server` | **Your sync server.** Only after linking: your server receives its device token, device name, platform/version and synced library state, including private feed URLs. Feed passwords are shared only when enabled. It receives no audio or feed contents. The server operator can read stored data. |
| `links` | **Links you open.** Your browser or chosen app receives a link you tap, including release/download links. Its own privacy settings apply. |
| `issue-tracker` | **Issue tracker.** Reporting a problem opens github.com in your browser. Review any report before sending it. |
| `server-updates` | **Sync server updates.** The optional sync server, rather than this app, checks github.com and release-assets.githubusercontent.com for updates when its operator enables the check. |

Provider choices and country travel in explicit settings backups, never sync.
API keys and secrets stay in SecretStore, outside settings backups and sync.
A user pair overrides any build pair; removing it falls back to a build pair
if present. Builds remain keyless by default; any build-supplied pair is
extractable from the app. Android encrypts your entered API secret with
Keystore; Windows protects the secrets file with user-bound DPAPI; macOS/Linux
protect a file with 0600 permissions, without encryption. Your API key identifier
also appears unencrypted in local credential metadata. See the full [design network
inventory](docs/design/09-quality-and-release.md#network-inventory).

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
