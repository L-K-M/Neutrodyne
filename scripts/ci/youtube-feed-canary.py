#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""One report-only feed canary. No extraction, Python host, or engine update requests."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

CONTROLS = ("UCBR8-60-B28hp2BmDPdntcQ", "UCK8sQmJBp8GCxrOtXWBpyEA")
BASE = "https://www.youtube.com/feeds/videos.xml?"
CAP = 2 * 1024 * 1024
TIMEOUT = 8
DEADLINE = 50
ATOM = "{http://www.w3.org/2005/Atom}"
YT = "{http://www.youtube.com/xml/schemas/2015}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url):
    # Redirects (including consent) are diagnostics, never an unbounded follow chain.
    request = urllib.request.Request(url, headers={
        "User-Agent": "Neutrodyne/feed-canary (+https://github.com/L-K-M/Neutrodyne)",
        "Accept": "application/atom+xml", "Cookie": "SOCS=CAE=",
    })
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=TIMEOUT) as response:
            deadline = time.monotonic() + TIMEOUT
            body = bytearray()
            while len(body) <= CAP:
                if time.monotonic() >= deadline:
                    raise TimeoutError("feed body deadline exceeded")
                chunk = response.read1(min(8192, CAP + 1 - len(body)))
                if not chunk:
                    break
                body.extend(chunk)
            return response.status, bytes(body)
    except urllib.error.HTTPError as error:
        with error:
            return error.code, b""


def validate(body, channel):
    if len(body) > CAP or b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
        raise ValueError("oversize or unsafe XML")
    feed = ET.fromstring(body)
    if feed.tag != ATOM + "feed" or not feed.findtext(ATOM + "author/" + ATOM + "name"):
        raise ValueError("missing Atom feed/author")
    actual = feed.findtext(YT + "channelId", "").removeprefix("UC")
    if actual != channel.removeprefix("UC"):
        raise ValueError("wrong channel identity")
    for entry in feed.findall(ATOM + "entry"):
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", entry.findtext(YT + "videoId", "")):
            raise ValueError("missing/invalid video identity")
    return len(feed.findall(ATOM + "entry"))


def run(get=fetch, monotonic=time.monotonic):
    started = monotonic()
    results = []
    samples = [(channel, "channel_id=" + channel, False) for channel in CONTROLS]
    # One control supplies the tiny LONG_FORM/SHORTS/LIVE sample. Empty variants are valid.
    samples += [(CONTROLS[0], "playlist_id=" + prefix + CONTROLS[0][2:], optional)
                for prefix, optional in (("UULF", False), ("UUSH", True), ("UULV", True))]
    for channel, query, optional in samples:
        result = {"url": BASE + query, "ok": False}
        try:
            if monotonic() - started >= DEADLINE:
                raise ValueError("canary deadline exceeded")
            status, body = get(BASE + query)
            result["status"] = status
            if status in (404, 410) and optional:
                result.update(ok=True, emptyVariant=True)
            elif status == 200:
                result.update(ok=True, entries=validate(body, channel))
            elif status in (404, 410) and query.startswith("playlist_id="):
                # Primary fallback only; two earlier canonical requests are reused as evidence.
                control = results[0]
                result.update(ok=control["ok"], canonicalFallback=control["url"])
            else:
                result["error"] = "HTTP response (availability/consent may be environment-specific)"
        except (OSError, ValueError, ET.ParseError) as error:
            result["error"] = str(error)
        results.append(result)
    return {"capturedAt": datetime.now(timezone.utc).isoformat(), "kind": "live-feed-only-canary",
            "applicationClientCapture": False, "requests": results,
            "ok": all(item["ok"] for item in results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = run()
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    raise SystemExit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
