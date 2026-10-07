<!-- SPDX-License-Identifier: Unlicense -->
# Golden corpus

The parser's golden fixtures (03 Feeds and discovery, Golden corpus). Every `<name>.xml` has a
`<name>.golden.json`: the `ParsedFeed` serialised with sorted keys and no explicit nulls
(`./gradlew :feeds:jvm:test -PupdateGoldens` rewrites them locally; refused when `CI` is set).
Failures pin only their `ParseFailure` reason. The tree stays at this path (a plain data directory
of the `:feeds` project, consumed as test resources by `:feeds:jvm` and, later, by the Android
parser tests of `:core:data`) so 04's and 05's fixtures keep sharing it.

## Provenance

**Every fixture is synthetic.** All show titles, descriptions, names and URLs are invented for this
corpus; no copyrighted show notes are copied verbatim. Structures mirror the quirks the design
lists (real-world failure modes of feed generators, reproduced with generated content):

| Fixture | Structure mirrored after |
|---|---|
| `pc20-github-alias-ns.xml` | the Podcasting 2.0 reference feed's quirks: the GitHub-alias namespace URI, `application.x-mpegURL` (sic), `application/srt` transcripts, chapters typed `application/json`, deprecated `podcast:images` |
| `large-831-items.xml` | the shape of 99% Invisible's feed (831 items, weekly cadence 2010–2026, `content:encoded` + `description`, GUID-per-episode); generated with a deterministic script, lorem-ipsum-style text, ~3.5 MB |
| `windows1252-declared-utf8.xml` | feeds whose CMS sends windows-1252 bytes with a UTF-8 XML declaration |
| `itunes-undeclared-prefix.xml` | feeds that use `itunes:` without declaring the namespace |
| `youtube-atom-*.xml` | YouTube's channel and playlist Atom feeds (`yt:` namespace, `media:group`) |
| everything else | the failure mode named in 03's corpus table, with synthetic content |

Encodings are deliberate: `windows1252-declared-utf8.xml` is windows-1252 bytes,
`utf16-bom.xml` UTF-16LE with BOM, `utf8-bom-utf16-declaration.xml` a UTF-8 BOM over a
UTF-16 declaration (the BOM wins), `latin1-declared.xml` ISO-8859-1. Treat them as binary.

## Regeneration

Goldens are byte-comparable output, not hand-edited. To change a fixture, edit the XML and run:

```sh
./gradlew :feeds:jvm:test -PupdateGoldens
```

then review the golden diff like code.
