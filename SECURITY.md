<!-- SPDX-License-Identifier: Unlicense -->

# Security policy

Report vulnerabilities through GitHub's
[private vulnerability reporting](https://github.com/L-K-M/Neutrodyne/security/advisories/new)
for this repository — never in a public issue. We acknowledge a report within
7 days. Fixes ship as PATCH releases through the normal pipeline (every product
of the tag).

## Scope

In scope: parsing of feeds, OPML, backups and import files; the exported
`ArtworkProvider`; intent and desktop link handling (URL schemes, file
associations, the single-instance handshake); credential storage on both
platforms; the update check's manifest and link validation; the engine-update
trust chain and the desktop engine child; the sync client's handling of server
data; and the sync server (authentication and device linking, input caps, rate
limits, the web UI, the image).

## Public signing key — not a vulnerability

The APK signing key is public **by design** (`signing/neutrodyne-public.keystore`,
D61/PO-35): a matching signature proves nothing about who built an APK, and the
desktop builds and server carry no publisher signature at all. A report of a
Neutrodyne-signed APK offered outside the GitHub release page is answered with
the download guidance, not treated as a key compromise — download only from
[this repository's releases](https://github.com/L-K-M/Neutrodyne/releases) and
check files against `SHA256SUMS`, `gh release verify-asset` and
`gh attestation verify`. The same holds for unsigned desktop packages offered
elsewhere. A suspected compromise of the engine-manifest signing key (the
project's only private key) follows the rotation in `engine-canary.yml`.
