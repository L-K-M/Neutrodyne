# SPDX-License-Identifier: Unlicense
import json
import unittest

from neutrodyne_ytx.record import minimize_body, minimize


class RecordingTests(unittest.TestCase):
    def test_json_scrubs_nested_secrets_and_prose(self):
        value = minimize({'signatureCipher': 'secret', 'title': 'copyrighted', 'context': {'visitorData': 'visitor-secret'},
                          'url': 'https://example.invalid?a=1&sig=signature-secret&ip=192.0.2.1&expire=12'})
        self.assertNotIn('secret', str(value))
        self.assertNotIn('copyrighted', str(value))
        self.assertIn('0.0.0.0', value['url'])

    def test_html_keeps_only_scrubbed_bootstrap_json(self):
        html = b'<html>Copyrighted prose<script>var ytInitialData = {"title":"private", "visitorData":"secret"};</script></html>'
        result = minimize_body(html)
        self.assertNotIn('secret', result)
        self.assertNotIn('private', result)
        self.assertNotIn('Copyrighted', result)
        self.assertIn('ytInitialData', result)

    def test_unknown_text_fails_closed(self):
        with self.assertRaises(ValueError):
            minimize_body(b'<html>private consent page</html>')
