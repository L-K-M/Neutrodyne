#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Resolve the latest yt-dlp stable tag without the GitHub REST API.

Prints the tag in the `Location` header of
`https://github.com/yt-dlp/yt-dlp/releases/latest` — the same header the
apps read for policy UPSTREAM_STABLE (04 Update flow). Only stable
`yyyy.mm.dd` names pass; prereleases and yt-dlp's rebuild (`yyyy.mm.dd.n`)
tags are rejected because the approval manifest cannot represent them
(EngineManifestVerifier.versionPattern).

`latest_tag(fetch)` takes a callable returning the redirect target for a
URL so tests never touch the network.
"""

import re
import sys
import urllib.request

RELEASES_LATEST = 'https://github.com/yt-dlp/yt-dlp/releases/latest'
TAG_PATTERN = re.compile(r'20[0-9]{2}\.[0-9]{2}\.[0-9]{2}')
TAG_PREFIX = 'https://github.com/yt-dlp/yt-dlp/releases/tag/'


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def github_redirect(url):
    """The `Location` header of an unfollowed GitHub redirect."""
    opener = urllib.request.build_opener(_NoRedirect)
    request = urllib.request.Request(url, method='HEAD')
    try:
        opener.open(request, timeout=30)
    except urllib.error.HTTPError as redirect:
        if redirect.code in (301, 302, 303, 307, 308):
            return redirect.headers.get('Location', '')
        raise
    return ''


def tag_from_location(location):
    """The stable tag named by a `releases/tag/<tag>` Location, else None."""
    if not location.startswith(TAG_PREFIX):
        return None
    tag = location[len(TAG_PREFIX):].split('?', 1)[0].split('#', 1)[0]
    if TAG_PATTERN.fullmatch(tag):
        return tag
    return None


def latest_tag(fetch=github_redirect):
    """The latest stable yt-dlp tag, or None when the redirect is unexpected."""
    return tag_from_location(fetch(RELEASES_LATEST))


if __name__ == '__main__':
    tag = latest_tag()
    if not tag:
        sys.exit('latest-ytdlp-tag: no stable tag in the releases/latest redirect')
    print(tag)
