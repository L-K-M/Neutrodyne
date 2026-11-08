#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Feed canary bounds and failure reporting, entirely offline."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("canary", Path(__file__).parents[1] / "youtube-feed-canary.py")
canary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(canary)


def feed(channel):
    return (f'<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">'
            f'<author><name>Control</name></author><yt:channelId>{channel}</yt:channelId></feed>').encode()


class CanaryTest(unittest.TestCase):
    def test_exactly_five_bounded_feed_only_requests(self):
        calls = []
        def get(url):
            calls.append(url)
            channel = canary.CONTROLS[1] if canary.CONTROLS[1] in url else canary.CONTROLS[0]
            return 200, feed(channel)
        report = canary.run(get)
        self.assertTrue(report["ok"])
        self.assertEqual(5, len(calls))
        self.assertTrue(all(url.startswith(canary.BASE) for url in calls))
        self.assertFalse(report["applicationClientCapture"])

    def test_optional_empty_and_primary_fallback_reuse_canonical(self):
        def get(url):
            if "playlist_id=" in url:
                return 404, b""
            channel = canary.CONTROLS[1] if canary.CONTROLS[1] in url else canary.CONTROLS[0]
            return 200, feed(channel)
        report = canary.run(get)
        self.assertTrue(report["ok"])
        self.assertIn("canonicalFallback", report["requests"][2])
        self.assertTrue(report["requests"][3]["emptyVariant"])

    def test_failure_shapes_and_cap_are_not_success(self):
        for status, body in ((200, b"<html/>"), (200, feed("UCwrong")), (429, b""),
                             (302, b""), (200, b"x" * (canary.CAP + 1)),
                             (200, b'<!DOCTYPE feed><feed/>')):
            with self.subTest(status=status, size=len(body)):
                self.assertFalse(canary.run(lambda url: (status, body))["ok"])

    def test_deadline_and_transport_failures_are_reported(self):
        def broken(url):
            raise OSError("recorded connection failure")
        self.assertFalse(canary.run(broken)["ok"])
        times = iter([0, 51, 51, 51, 51, 51])
        report = canary.run(lambda url: self.fail("deadline must prevent request"), lambda: next(times))
        self.assertFalse(report["ok"])
        self.assertEqual(5, len(report["requests"]))


if __name__ == "__main__":
    unittest.main()
