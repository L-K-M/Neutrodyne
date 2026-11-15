# Layer A replay provenance

Prepared 2026-10-19 for M8 W2. These small shape fixtures contain no cookies,
visitor identifiers, nonces, authentication, executable scripts or account data.
They are packaged into both desktopTest and androidHostTest resources.

The successful channel, canonical, watch and oEmbed fixtures are reconstructed
from the selectors and response shapes recorded in design 04's 2026-10-04
research, rather than represented as fresh successful network recordings.
Sources: `https://www.youtube.com/@mkbhd`, the corresponding canonical channel,
`https://www.youtube.com/watch?v=3iRUwVzRDZQ`, and its `/oembed` request.
Only the public channel/video IDs and author handle/name are retained; artwork
paths, descriptions and video titles are replaced with inert examples. The
canonical-only case is an intentionally reduced selector alternative. Entity,
attribute, streaming and size-boundary mutations are generated in tests.

Live capture attempted on 2026-10-19 from this Linux worker, with SOCS=CAE=,
Accept: text/html, English locale and the app User-Agent. Both `@mkbhd` and
`/channel/UCLA_DiR1FfKNvjuUpBHmylQ` redirected to `consent.youtube.com` despite
the cookie. Each final consent head measured 19,890 UTF-8 bytes through
`</head>`; the consent fixture retains only its title and structural tags.
Redirect query parameters and all scripts/styles were discarded. These are
consent measurements, not channel-head measurements. No valid channel-head
median was obtained and the 512 KiB metered-avatar decision remains pending
a successful owner capture. Scrubbed fixture sizes cannot answer that question.
Replay and PR tests make no live network requests.

## Mobile channel metadata after the head

`mobile-channel.html` is a reduced, scrubbed response shape captured successfully
on 2026-11-14 from `https://www.youtube.com/@TheTechReportTR`, following its
mobile redirect with an Android Neutrodyne User-Agent, SOCS=CAE= and de-CH
locale. The 561,098-byte response ended its initial head at byte 2,865; its RSS
link and channel metadata appeared around byte 281,000 in the body. There was
no `externalId` field. Retained public values are the handle, channel ID and
title; the avatar and description are inert replacements. Scripts, styles,
request-specific values and unrelated markup were removed. The fixture retains
the metadata order, not the original response sizes.

A successful oEmbed response for `8Y-fS_55pD0`, the video reported alongside the
handle, identified `https://www.youtube.com/@TheTechReportTR` as `author_url`.
The replay test supplies only that author URL and public author name. These
captures explain why both inputs reached the same unreadable-page failure;
they do not establish a channel-head median or guarantee live availability.

## Consent cookie padding

Measured 2026-11-15 from Switzerland (consent redirects named `gl=CH`) with
Neutrodyne Android and macOS User-Agents, `Accept: text/html` and de-CH, en
and fr-FR locales. Requests for `/@TheTechReportTR` and
`/channel/UCPiMR-Ize9p3dgjzZ8bo9ZQ` that sent a base64-padded SOCS value
(`CAE=`, `CAI=`, `CAESAA==`, or `"CAE="` quoted) were redirected to
`consent.youtube.com` on both the `www` and `m` hosts: six of six repeats for
`CAE=`. The unpadded forms of the same bytes (`CAE`, `CAI`, `CAESAA`) returned
the channel page with its RSS `channel_id` link: six of six repeats for `CAE`.
Atom feeds and the watch page answered either form without consent. The
2026-10-19 consent redirects above were made with the padded value; the
unpadded value was not tried then. `reportedHandleLinkGetsPastTheConsentWall`
replays this rule. No response body from these checks is stored.
