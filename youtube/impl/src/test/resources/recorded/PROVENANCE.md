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
