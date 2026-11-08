#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fake-GitHub tests for the immutable engine-approval publisher and index reader.

No network, no real token, no release, tag, asset or repository mutation.  The
publisher runs on a fake GitHub transport that rejects post-publication
uploads, asset replacement/deletion and make_latest=true; the reader runs on
fake index and snapshot documents.  The production HTTPS adapter is exercised
through an injected opener, never a live endpoint.
"""

import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse

ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / "scripts" / "engine"
sys.path.insert(0, str(SCRIPTS))

import approval_release as ar  # noqa: E402

REPO_API = "https://api.github.com/repos/L-K-M/Neutrodyne"
REPO_PATH = "/repos/L-K-M/Neutrodyne"
RELEASES = f"{REPO_API}/releases"
RELEASES_PATH = f"{REPO_PATH}/releases"
UPLOADS = "https://uploads.github.com/repos/L-K-M/Neutrodyne/releases"
DOWNLOAD_BASE = "https://github.com/L-K-M/Neutrodyne/releases/download"
DOWNLOAD_PATH = "/L-K-M/Neutrodyne/releases/download"
INDEX_TAG = "engine-approvals"
SHA = "a" * 40


def make_files(
    manifest=b'{"schema":1,"version":"2026.08.19","sequence":2}',
    heartbeat=b'{"kind":"heartbeat","lastRunAt":"2026-11-08T00:00:00Z"}',
):
    return ar.SnapshotFiles(
        manifest=manifest,
        manifest_signature=b"m" * 64,
        heartbeat=heartbeat,
        heartbeat_signature=b"h" * 64,
    )


def index_document(
    snapshot_tag="engine-approvals-1-1",
    *,
    release_tag=INDEX_TAG,
    draft=False,
    prerelease=True,
    url=f"{REPO_API}/releases/42",
    body=None,
):
    if body is None:
        body = json.dumps(
            {"schema": 1, "snapshotTag": snapshot_tag}, separators=(",", ":")
        )
    return {
        "id": 42,
        "url": url,
        "tag_name": release_tag,
        "draft": draft,
        "prerelease": prerelease,
        "body": body,
    }


class FakeGitHub:
    """A small immutable-release fake with violation tracking.

    It returns tuples internally and enforces the caller's response bound, so
    the reader's size caps are exercised without a live endpoint.
    """

    def __init__(self, *, immutable=True, index_snapshot_tag=None, index_immutable=False):
        self.immutable = immutable
        self.releases = {}
        self.assets = {}
        self.asset_bodies = {}
        self.documents = {}
        self.requests = []
        self.violations = []
        self.failure = None
        self.override_release = None
        self.corrupt_assets = set()
        self.index_document = None
        self.index_status = 200
        self.index_id = None
        self._next_release_id = 100
        self._next_asset_id = 500
        if index_snapshot_tag is not None:
            self.install_index(
                index_snapshot_tag, immutable=index_immutable
            )

    # -- fixture helpers -----------------------------------------------------

    def install_index(self, snapshot_tag, *, immutable=False, assets=()):
        for existing_id, existing in list(self.releases.items()):
            if existing["tag_name"] == INDEX_TAG:
                del self.releases[existing_id]
                self.assets.pop(existing_id, None)
        release_id = self._next_release_id
        self._next_release_id += 1
        body = json.dumps(
            {"schema": 1, "snapshotTag": snapshot_tag}, separators=(",", ":")
        )
        self.releases[release_id] = {
            "id": release_id,
            "url": f"{REPO_API}/releases/{release_id}",
            "tag_name": INDEX_TAG,
            "target_commitish": "0" * 40,
            "name": "Engine approvals",
            "body": body,
            "draft": False,
            "prerelease": True,
            "make_latest": "false",
            "immutable": immutable,
        }
        self.assets[release_id] = []
        self.index_id = release_id
        for name, content in assets:
            self.add_asset(release_id, name, content)
        return release_id

    def add_release(
        self,
        tag,
        *,
        target_commitish=SHA,
        draft,
        prerelease=True,
        immutable=None,
        make_latest="false",
        body="",
    ):
        release_id = self._next_release_id
        self._next_release_id += 1
        self.releases[release_id] = {
            "id": release_id,
            "url": f"{REPO_API}/releases/{release_id}",
            "tag_name": tag,
            "target_commitish": target_commitish,
            "name": tag,
            "body": body,
            "draft": draft,
            "prerelease": prerelease,
            "make_latest": make_latest,
            "immutable": self.immutable if immutable is None else immutable,
        }
        self.assets[release_id] = []
        return release_id

    def add_asset(self, release_id, name, content):
        asset_id = self._next_asset_id
        self._next_asset_id += 1
        self.assets[release_id].append(
            {
                "id": asset_id,
                "name": name,
                "size": len(content),
                "state": "uploaded",
                "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
                "url": f"{REPO_API}/releases/assets/{asset_id}",
            }
        )
        self.asset_bodies[asset_id] = content
        return asset_id

    def serve_snapshot(self, tag, files):
        base = f"{DOWNLOAD_PATH}/{tag}"
        self.documents[f"{base}/ytdlp-approved.json"] = files.manifest
        self.documents[f"{base}/ytdlp-approved.json.sig"] = files.manifest_signature
        self.documents[f"{base}/ytdlp-heartbeat.json"] = files.heartbeat
        self.documents[f"{base}/ytdlp-heartbeat.json.sig"] = files.heartbeat_signature

    def find_by_tag(self, tag):
        for release in self.releases.values():
            if release["tag_name"] == tag:
                return release
        return None

    def index_body_tag(self):
        release = self.releases.get(self.index_id)
        return json.loads(release["body"])["snapshotTag"]

    # -- transport -----------------------------------------------------------

    def request(
        self,
        method,
        url,
        *,
        headers=None,
        body=None,
        max_response_bytes,
        timeout_seconds=None,
    ):
        self.requests.append(
            {
                "method": method,
                "url": url,
                "headers": dict(headers or {}),
                "body": body,
            }
        )
        if self.failure is not None:
            status = self.failure(method, url, body)
            if status is not None:
                return ar.TransportResponse(status=status, headers={}, body=b"")
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname
        if host == "api.github.com":
            status, response_headers, payload = self._api(
                method, parts.path, urllib.parse.parse_qs(parts.query), body, headers or {}
            )
        elif host == "uploads.github.com":
            status, response_headers, payload = self._uploads(
                method, parts.path, urllib.parse.parse_qs(parts.query), body, headers or {}
            )
        elif host == "github.com":
            status, response_headers, payload = self._download(parts.path)
        else:
            status, response_headers, payload = 404, {}, b""
        if len(payload) > max_response_bytes:
            raise ar.ResponseTooLargeError(
                f"response exceeds {max_response_bytes} bytes"
            )
        return ar.TransportResponse(
            status=status, headers=response_headers, body=payload
        )

    def _json(self, status, document, headers=None):
        return status, headers or {}, json.dumps(document).encode("utf-8")

    def _release_response(self, release):
        if release is None:
            return 404, {}, b""
        document = dict(release)
        if self.override_release:
            document.update(self.override_release)
        return self._json(200, document, {"ETag": f'W/"{release["id"]}"'})

    def _api(self, method, path, query, body, headers):
        if method in ("POST", "PUT", "PATCH", "DELETE") and path == REPO_PATH:
            self.violations.append(("repository-settings", method, path))
            return 405, {}, b""
        if method == "GET" and path.startswith(RELEASES_PATH + "/tags/"):
            tag = urllib.parse.unquote(path[len(RELEASES_PATH + "/tags/"):])
            if tag == INDEX_TAG and self.index_document is not None:
                document = self.index_document
                if isinstance(document, bytes):
                    return self.index_status, {}, document
                return self._json(self.index_status, document)
            return self._release_response(self.find_by_tag(tag))
        if method in ("DELETE", "PUT", "PATCH") and path.startswith(RELEASES_PATH + "/assets/"):
            self.violations.append(("asset-mutation", method, path))
            return 405, {}, b""
        if method == "GET" and path.startswith(RELEASES_PATH + "/assets/"):
            asset_id = int(path[len(RELEASES_PATH + "/assets/"):])
            return self._get_asset(asset_id, headers)
        if method == "GET" and path.startswith(RELEASES_PATH + "/") and path.endswith("/assets"):
            release_id = int(path[len(RELEASES_PATH + "/") : -len("/assets")])
            return self._list_assets(release_id)
        if method == "POST" and path == RELEASES_PATH:
            return self._create_release(body)
        if method in ("GET", "PATCH") and path.startswith(RELEASES_PATH + "/"):
            release_id = int(path[len(RELEASES_PATH + "/") :])
            if method == "GET":
                return self._release_response(self.releases.get(release_id))
            return self._patch_release(release_id, body)
        if method == "GET" and path == REPO_PATH:
            return self._json(
                200, {"immutable_releases": self.immutable}
            )
        return 404, {}, b""

    def _uploads(self, method, path, query, body, headers):
        if method != "POST":
            self.violations.append(("uploads-mutation", method, path))
            return 405, {}, b""
        if not (path.startswith(RELEASES_PATH + "/") and path.endswith("/assets")):
            return 404, {}, b""
        release_id = int(path[len(RELEASES_PATH + "/") : -len("/assets")])
        name = query.get("name", [""])[0]
        release = self.releases.get(release_id)
        if release is None:
            return 404, {}, b""
        if not release["draft"]:
            self.violations.append(("post-publication-upload", name))
            return 403, {}, b""
        if any(asset["name"] == name for asset in self.assets[release_id]):
            self.violations.append(("overwrite-asset", name))
            return 409, {}, b""
        asset_id = self._next_asset_id
        self._next_asset_id += 1
        self.assets[release_id].append(
            {
                "id": asset_id,
                "name": name,
                "size": len(body),
                "state": "uploaded",
                "digest": "sha256:" + hashlib.sha256(body).hexdigest(),
                "url": f"{REPO_API}/releases/assets/{asset_id}",
            }
        )
        self.asset_bodies[asset_id] = body
        return self._json(
            201,
            {
                "id": asset_id,
                "name": name,
                "size": len(body),
                "state": "uploaded",
                "digest": "sha256:" + hashlib.sha256(body).hexdigest(),
                "url": f"{REPO_API}/releases/assets/{asset_id}",
            },
        )

    def _download(self, path):
        if path in self.documents:
            content = self.documents[path]
            if content is None:
                return 404, {}, b""
            return 200, {}, content
        return 404, {}, b""

    def _create_release(self, body):
        payload = json.loads(body.decode("utf-8"))
        if payload.get("make_latest") not in (None, "false", "legacy"):
            self.violations.append(("make_latest", payload.get("make_latest")))
            return 422, {}, b""
        release_id = self._next_release_id
        self._next_release_id += 1
        release = {
            "id": release_id,
            "url": f"{REPO_API}/releases/{release_id}",
            "tag_name": payload["tag_name"],
            "target_commitish": payload["target_commitish"],
            "name": payload.get("name", ""),
            "body": payload.get("body", ""),
            "draft": payload.get("draft", False),
            "prerelease": payload.get("prerelease", False),
            "make_latest": payload.get("make_latest", "false"),
            "immutable": self.immutable,
        }
        self.releases[release_id] = release
        self.assets[release_id] = []
        if release["tag_name"] == INDEX_TAG:
            self.index_id = release_id
        return self._json(201, release)

    def _patch_release(self, release_id, body):
        release = self.releases.get(release_id)
        if release is None:
            return 404, {}, b""
        payload = json.loads(body.decode("utf-8"))
        if payload.get("make_latest") not in (None, "false", "legacy"):
            self.violations.append(("make_latest", payload.get("make_latest")))
            return 422, {}, b""
        for key in ("draft", "prerelease", "make_latest", "body", "name"):
            if key in payload:
                release[key] = payload[key]
        return self._json(200, release)

    def _list_assets(self, release_id):
        if release_id not in self.assets:
            return 404, {}, b""
        assets = self.assets[release_id]
        headers = {}
        if len(assets) >= 100:
            headers["Link"] = f'<{REPO_API}/releases/{release_id}/assets?page=2>; rel="next"'
        return self._json(200, assets, headers)

    def _get_asset(self, asset_id, headers):
        for release_id, assets in self.assets.items():
            for asset in assets:
                if asset["id"] == asset_id:
                    if "octet-stream" in headers.get("Accept", ""):
                        if asset["name"] in self.corrupt_assets:
                            return 200, {}, b"corrupt"
                        return 200, {}, self.asset_bodies[asset_id]
                    return self._json(200, asset)
        return 404, {}, b""


class RecordingEnv(dict):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.read = []

    def get(self, key, default=None):
        self.read.append(key)
        return super().get(key, default)


class IndexReaderTests(unittest.TestCase):
    def setUp(self):
        self.files = make_files()
        self.fake = FakeGitHub()
        self.fake.serve_snapshot("engine-approvals-4242-3", self.files)

    def test_resolve_derives_fixed_github_urls_from_the_tag(self):
        self.fake.install_index("engine-approvals-4242-3")
        index = ar.resolve_index(self.fake)
        self.assertIsNotNone(index)
        self.assertEqual("engine-approvals-4242-3", index.snapshot_tag)
        self.assertEqual(
            f"{DOWNLOAD_BASE}/engine-approvals-4242-3",
            index.snapshot_base_url,
        )
        self.assertEqual(
            f"{DOWNLOAD_BASE}/engine-approvals-4242-3/ytdlp-approved.json",
            f"{index.snapshot_base_url}/ytdlp-approved.json",
        )
        self.assertEqual(1, len(self.fake.requests))
        self.assertEqual("GET", self.fake.requests[0]["method"])

    def test_missing_index_is_none_not_a_bootstrap(self):
        self.assertIsNone(ar.resolve_index(self.fake))
        self.assertEqual(1, len(self.fake.requests))

    def test_malformed_index_json_is_an_error(self):
        self.fake.index_document = b"garbage"
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_missing_index_field_is_an_error(self):
        document = index_document()
        del document["body"]
        self.fake.index_document = document
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_wrong_field_type_is_an_error(self):
        document = index_document()
        document["draft"] = "false"
        self.fake.index_document = document
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_draft_index_is_rejected(self):
        self.fake.index_document = index_document(draft=True)
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_non_prerelease_index_is_rejected(self):
        self.fake.index_document = index_document(prerelease=False)
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_wrong_index_tag_is_rejected(self):
        self.fake.index_document = index_document(release_tag="nightly")
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_foreign_index_identity_is_rejected(self):
        self.fake.index_document = index_document(
            url=f"https://api.github.com/repos/evil/mirror/releases/1"
        )
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_oversized_index_body_is_rejected(self):
        body = json.dumps(
            {
                "schema": 1,
                "snapshotTag": "engine-approvals-4242-3",
                "pad": "x" * 5000,
            }
        )
        self.fake.index_document = index_document(body=body)
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_unknown_index_schema_is_rejected(self):
        body = json.dumps({"schema": 2, "snapshotTag": "engine-approvals-4242-3"})
        self.fake.index_document = index_document(body=body)
        with self.assertRaises(ar.IndexDiscoveryError):
            ar.resolve_index(self.fake)

    def test_bad_snapshot_tag_grammar_is_rejected(self):
        for tag in (
            "latest",
            "engine-approvals-1",
            "engine-approvals-1-x",
            "engine-approvals-1-2-3",
            "../etc",
            "engine-approvals--1",
            "engine-approvals-1-2 ",
        ):
            with self.subTest(tag=tag):
                self.fake.index_document = index_document(
                    body=json.dumps({"schema": 1, "snapshotTag": tag})
                )
                with self.assertRaises(ar.IndexDiscoveryError):
                    ar.resolve_index(self.fake)

    def test_foreign_url_in_the_body_is_never_followed(self):
        body = json.dumps(
            {
                "schema": 1,
                "snapshotTag": "engine-approvals-4242-3",
                "url": "https://evil.example/download",
            }
        )
        self.fake.index_document = index_document(body=body)
        index = ar.resolve_index(self.fake)
        self.assertEqual(
            f"{DOWNLOAD_BASE}/engine-approvals-4242-3",
            index.snapshot_base_url,
        )
        self.assertFalse(
            any("evil.example" in request["url"] for request in self.fake.requests)
        )

    def test_all_four_documents_come_from_one_snapshot(self):
        self.fake.install_index("engine-approvals-4242-3")
        index = ar.resolve_index(self.fake)
        # The index moves right after resolution; a second index read would mix.
        self.fake.index_document = index_document(snapshot_tag="engine-approvals-7777-2")
        documents = ar.read_snapshot_documents(self.fake, index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertEqual(self.files.manifest_signature, documents.manifest_signature)
        self.assertEqual(self.files.heartbeat, documents.heartbeat)
        self.assertEqual(self.files.heartbeat_signature, documents.heartbeat_signature)
        self.assertEqual(1, len([r for r in self.fake.requests if r["url"] == ar.INDEX_URL]))
        download_requests = [
            r["url"] for r in self.fake.requests if r["url"].startswith(DOWNLOAD_BASE)
        ]
        self.assertEqual(4, len(download_requests))
        self.assertTrue(
            all("/engine-approvals-4242-3/" in url for url in download_requests)
        )
        self.assertFalse(
            any("engine-approvals-7777-2" in url for url in download_requests)
        )

    def test_new_snapshot_discovery_after_an_index_advance(self):
        self.fake.install_index("engine-approvals-4242-3")
        self.fake.serve_snapshot("engine-approvals-4242-3", self.files)
        first = ar.resolve_index(self.fake)
        first_documents = ar.read_snapshot_documents(self.fake, first)
        self.assertEqual(self.files.manifest, first_documents.manifest)

        new_files = make_files(
            heartbeat=b'{"kind":"heartbeat","lastRunAt":"2026-11-09T00:00:00Z"}'
        )
        self.fake.serve_snapshot("engine-approvals-7777-2", new_files)
        self.fake.install_index("engine-approvals-7777-2")
        second = ar.resolve_index(self.fake)
        self.assertEqual("engine-approvals-7777-2", second.snapshot_tag)
        second_documents = ar.read_snapshot_documents(self.fake, second)
        self.assertEqual(new_files.heartbeat, second_documents.heartbeat)

    def test_missing_heartbeat_is_diagnostic_only(self):
        self.fake.install_index("engine-approvals-4242-3")
        self.fake.documents[f"{DOWNLOAD_PATH}/engine-approvals-4242-3/ytdlp-heartbeat.json"] = None
        index = ar.resolve_index(self.fake)
        documents = ar.read_snapshot_documents(self.fake, index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertIsNone(documents.heartbeat)

    def test_missing_manifest_is_an_error(self):
        self.fake.install_index("engine-approvals-4242-3")
        self.fake.documents[f"{DOWNLOAD_PATH}/engine-approvals-4242-3/ytdlp-approved.json"] = None
        index = ar.resolve_index(self.fake)
        with self.assertRaises(ar.SnapshotReadError):
            ar.read_snapshot_documents(self.fake, index)

    def test_oversized_index_response_is_rejected(self):
        self.fake.index_document = {
            "id": 42,
            "url": f"{REPO_API}/releases/42",
            "tag_name": INDEX_TAG,
            "draft": False,
            "prerelease": True,
            "body": "{\"schema\":1,\"snapshotTag\":\"engine-approvals-4242-3\"}",
            "pad": "x" * (ar.MAX_INDEX_JSON_BYTES + 1),
        }
        with self.assertRaises(ar.ResponseTooLargeError):
            ar.resolve_index(self.fake)

    def test_document_size_caps_are_enforced(self):
        self.fake.install_index("engine-approvals-4242-3")
        index = ar.resolve_index(self.fake)
        base = f"{DOWNLOAD_PATH}/engine-approvals-4242-3"
        cases = (
            (f"{base}/ytdlp-approved.json", ar.MAX_MANIFEST_BYTES + 1),
            (f"{base}/ytdlp-approved.json.sig", ar.MAX_SIGNATURE_BYTES + 1),
            (f"{base}/ytdlp-heartbeat.json", ar.MAX_HEARTBEAT_BYTES + 1),
            (f"{base}/ytdlp-heartbeat.json.sig", ar.MAX_SIGNATURE_BYTES + 1),
        )
        for url, size in cases:
            with self.subTest(url=url):
                self.fake.documents[url] = b"x" * size
                with self.assertRaises(ar.ResponseTooLargeError):
                    ar.read_snapshot_documents(self.fake, index)


class FakeGitHubGuardTests(unittest.TestCase):
    """The fake itself rejects the operations the publisher must never make."""

    def setUp(self):
        self.fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
        self.release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )

    def test_post_publication_upload_is_rejected(self):
        self.fake.releases[self.release_id]["draft"] = False
        response = self.fake.request(
            "POST",
            f"{UPLOADS}/{self.release_id}/assets?name=ytdlp-approved.json",
            max_response_bytes=1024,
            body=b"x",
        )
        self.assertEqual(403, response.status)
        self.assertIn("post-publication-upload", self.fake.violations[0])

    def test_asset_overwrite_is_rejected(self):
        self.fake.add_asset(self.release_id, "ytdlp-approved.json", b"original")
        response = self.fake.request(
            "POST",
            f"{UPLOADS}/{self.release_id}/assets?name=ytdlp-approved.json",
            max_response_bytes=1024,
            body=b"replacement",
        )
        self.assertEqual(409, response.status)
        self.assertIn("overwrite-asset", self.fake.violations[0])

    def test_make_latest_true_is_rejected(self):
        response = self.fake.request(
            "POST",
            RELEASES,
            max_response_bytes=1024,
            body=json.dumps(
                {"tag_name": "x", "draft": True, "make_latest": "true"}
            ).encode(),
        )
        self.assertEqual(422, response.status)
        self.assertIn("make_latest", self.fake.violations[0])

    def test_asset_mutation_is_rejected(self):
        asset_id = self.fake.add_asset(
            self.release_id, "ytdlp-approved.json", b"original"
        )
        response = self.fake.request(
            "DELETE",
            f"{RELEASES}/assets/{asset_id}",
            max_response_bytes=1024,
        )
        self.assertEqual(405, response.status)
        self.assertIn("asset-mutation", self.fake.violations[0])

    def test_repository_settings_mutation_is_rejected(self):
        response = self.fake.request(
            "PATCH",
            REPO_API,
            max_response_bytes=1024,
            body=b"{}",
        )
        self.assertEqual(405, response.status)
        self.assertIn("repository-settings", self.fake.violations[0])


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.files = make_files()
        self.fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")

    def publish(self, *, run_id=4242, run_attempt=3, files=None, **kwargs):
        return ar.SnapshotPublisher(self.fake).publish(
            files or self.files, run_id, run_attempt, SHA, **kwargs
        )

    def uploads(self):
        return [
            request
            for request in self.fake.requests
            if "uploads.github.com" in request["url"]
        ]

    def index_patch(self):
        return [
            request
            for request in self.fake.requests
            if request["method"] == "PATCH"
            and f"/releases/{self.fake.index_id}"
            in request["url"]
        ]

    def test_happy_path_requests_bytes_and_index_body(self):
        result = self.publish()
        self.assertEqual("engine-approvals-4242-3", result.snapshot_tag)
        self.assertTrue(result.created)
        self.assertTrue(result.published)
        self.assertFalse(result.reused)
        self.assertFalse(result.index_created)
        self.assertEqual(self.fake.index_id, result.index_release_id)
        self.assertEqual([], self.fake.violations)

        create = next(
            request
            for request in self.fake.requests
            if request["method"] == "POST" and request["url"] == RELEASES
        )
        payload = json.loads(create["body"])
        self.assertEqual("engine-approvals-4242-3", payload["tag_name"])
        self.assertEqual(SHA, payload["target_commitish"])
        self.assertTrue(payload["draft"])
        self.assertTrue(payload["prerelease"])
        self.assertEqual("false", payload["make_latest"])

        uploads = self.uploads()
        self.assertEqual(4, len(uploads))
        for request, (name, content) in zip(uploads, self.files.by_name().items()):
            self.assertIn(f"name={name}", request["url"])
            self.assertEqual(content, request["body"])
            self.assertEqual(
                "application/octet-stream", request["headers"].get("Content-Type")
            )

        snapshot = self.fake.find_by_tag("engine-approvals-4242-3")
        patch = next(
            request
            for request in self.fake.requests
            if request["method"] == "PATCH"
            and request["url"] == f"{RELEASES}/{snapshot['id']}"
        )
        patch_payload = json.loads(patch["body"])
        self.assertFalse(patch_payload["draft"])
        self.assertTrue(patch_payload["prerelease"])
        self.assertEqual("false", patch_payload["make_latest"])

        # No upload happens at or after the snapshot publish.
        publish_index = self.fake.requests.index(patch)
        self.assertFalse(
            any(
                "uploads.github.com" in request["url"]
                for request in self.fake.requests[publish_index:]
            )
        )

        index_patch = self.index_patch()
        self.assertEqual(1, len(index_patch))
        index_payload = json.loads(index_patch[0]["body"])
        self.assertEqual(
            {"schema": 1, "snapshotTag": "engine-approvals-4242-3"},
            json.loads(index_payload["body"]),
        )
        self.assertTrue(index_payload["prerelease"])
        self.assertEqual("false", index_payload["make_latest"])
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_reuse_complete_published_snapshot(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(release_id, name, content)
        result = self.publish()
        self.assertFalse(result.created)
        self.assertFalse(result.published)
        self.assertTrue(result.reused)
        self.assertFalse(
            any(
                request["method"] == "POST" and request["url"] == RELEASES
                for request in self.fake.requests
            )
        )
        self.assertEqual([], self.uploads())
        self.assertFalse(
            any(
                request["method"] == "PATCH"
                and request["url"] == f"{RELEASES}/{release_id}"
                for request in self.fake.requests
            )
        )
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_published_snapshot_missing_asset_fails_without_uploads(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in list(self.files.by_name().items())[:3]:
            self.fake.add_asset(release_id, name, content)
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_published_snapshot_wrong_target_sha_fails(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3",
            target_commitish="b" * 40,
            draft=False,
            immutable=True,
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(release_id, name, content)
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())

    def test_published_snapshot_asset_mismatch_fails(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(
                release_id,
                name,
                b"tampered" if name == "ytdlp-approved.json" else content,
            )
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())

    def test_published_snapshot_not_immutable_fails_closed(self):
        self.fake.immutable = False
        with self.assertRaises(ar.SnapshotNotImmutableError):
            self.publish()
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_snapshot_without_immutability_report_fails_closed(self):
        # A release object with no immutability field cannot prove sealing.
        self.fake.immutable = None
        with self.assertRaises(ar.SnapshotNotImmutableError):
            self.publish()
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_partial_draft_uploads_only_missing_assets(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        present = ("ytdlp-approved.json", "ytdlp-heartbeat.json")
        for name in present:
            self.fake.add_asset(release_id, name, self.files.by_name()[name])
        result = self.publish()
        self.assertFalse(result.created)
        self.assertTrue(result.published)
        uploads = self.uploads()
        self.assertEqual(
            ["ytdlp-approved.json.sig", "ytdlp-heartbeat.json.sig"],
            [urllib.parse.parse_qs(urllib.parse.urlsplit(u["url"]).query)["name"][0] for u in uploads],
        )
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_partial_draft_existing_asset_mismatch_fails_without_upload(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        self.fake.add_asset(release_id, "ytdlp-approved.json", b"tampered")
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])

    def test_complete_draft_publishes_without_uploads(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(release_id, name, content)
        result = self.publish()
        self.assertTrue(result.published)
        self.assertEqual([], self.uploads())
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_same_attempt_retry_completes_a_partial_draft(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        self.fake.add_asset(release_id, "ytdlp-approved.json", self.files.manifest)
        result = self.publish(run_attempt=3)
        self.assertFalse(result.created)
        self.assertTrue(result.published)
        self.assertEqual(3, len(self.uploads()))

    def test_new_attempt_retry_creates_a_new_tag(self):
        self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        result = self.publish(run_attempt=4)
        self.assertTrue(result.created)
        self.assertEqual("engine-approvals-4242-4", result.snapshot_tag)
        self.assertIsNotNone(self.fake.find_by_tag("engine-approvals-4242-4"))
        self.assertEqual("engine-approvals-4242-4", self.fake.index_body_tag())

    def test_bootstrap_missing_index_after_public_snapshot(self):
        fake = FakeGitHub()
        result = ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
        self.assertTrue(result.index_created)
        self.assertIsNotNone(fake.index_id)
        index = fake.releases[fake.index_id]
        self.assertEqual(INDEX_TAG, index["tag_name"])
        self.assertFalse(index["draft"])
        self.assertTrue(index["prerelease"])
        self.assertEqual("false", index["make_latest"])
        self.assertEqual(
            {"schema": 1, "snapshotTag": "engine-approvals-4242-3"},
            json.loads(index["body"]),
        )
        self.assertEqual([], fake.assets[fake.index_id])
        snapshot_publish = next(
            i
            for i, request in enumerate(fake.requests)
            if request["method"] == "PATCH" and "/releases/" in request["url"]
        )
        index_create = next(
            i
            for i, request in enumerate(fake.requests)
            if request["method"] == "POST"
            and request["url"] == RELEASES
            and json.loads(request["body"])["tag_name"] == INDEX_TAG
        )
        self.assertLess(snapshot_publish, index_create)

    def test_index_patch_failure_leaves_old_pointer_and_published_snapshot(self):
        self.fake.failure = (
            lambda method, url, body: 500
            if method == "PATCH" and f"/releases/{self.fake.index_id}" in url
            else None
        )
        with self.assertRaises(ar.IndexAdvanceError):
            self.publish()
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())
        snapshot = self.fake.find_by_tag("engine-approvals-4242-3")
        self.assertFalse(snapshot["draft"])

    def test_index_create_failure_leaves_published_snapshot_and_no_index(self):
        fake = FakeGitHub()
        fake.failure = (
            lambda method, url, body: 500
            if method == "POST"
            and url == RELEASES
            and json.loads(body)["tag_name"] == INDEX_TAG
            else None
        )
        with self.assertRaises(ar.IndexAdvanceError):
            ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
        self.assertIsNone(fake.index_id)
        self.assertFalse(fake.find_by_tag("engine-approvals-4242-3")["draft"])

    def test_publish_failure_leaves_draft_and_unchanged_index(self):
        self.fake.failure = (
            lambda method, url, body: 500
            if method == "PATCH" and "/releases/" in url and "/tags/" not in url
            else None
        )
        with self.assertRaises(ar.ApprovalReleaseError):
            self.publish()
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_upload_failure_leaves_draft_and_unchanged_index(self):
        self.fake.failure = (
            lambda method, url, body: 500
            if method == "POST"
            and "uploads.github.com" in url
            and "ytdlp-heartbeat.json" in url
            and ".sig" not in url
            else None
        )
        with self.assertRaises(ar.ApprovalReleaseError):
            self.publish()
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])
        self.assertFalse(self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_each_asset_upload_failure_is_a_boundary(self):
        for name in ar.ASSET_NAMES:
            with self.subTest(name=name):
                fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
                fake.failure = (
                    lambda method, url, body, name=name: 500
                    if method == "POST"
                    and "uploads.github.com" in url
                    and f"name={name}" in url
                    else None
                )
                with self.assertRaises(ar.ApprovalReleaseError):
                    ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
                self.assertTrue(fake.find_by_tag("engine-approvals-4242-3")["draft"])
                self.assertFalse(
                    any(
                        request["method"] == "PATCH"
                        and "/releases/" in request["url"]
                        and "/tags/" not in request["url"]
                        for request in fake.requests
                    )
                )
                self.assertEqual("engine-approvals-1-1", fake.index_body_tag())

    def test_missing_index_with_expected_tag_fails(self):
        fake = FakeGitHub()
        with self.assertRaises(ar.IndexChangedError):
            ar.SnapshotPublisher(fake).publish(
                self.files, 4242, 3, SHA, expected_index_tag="engine-approvals-1-1"
            )
        self.assertIsNone(fake.index_id)

    def test_create_failure_leaves_no_release_and_unchanged_index(self):
        self.fake.failure = (
            lambda method, url, body: 500
            if method == "POST" and url == RELEASES
            else None
        )
        with self.assertRaises(ar.ApprovalReleaseError):
            self.publish()
        self.assertIsNone(self.fake.find_by_tag("engine-approvals-4242-3"))
        self.assertEqual([], self.uploads())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_verification_failure_after_publish_leaves_index_unchanged(self):
        self.fake.corrupt_assets.add("ytdlp-approved.json")
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertFalse(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_cancellation_before_pointer_write_leaves_old_pointer(self):
        def cancel(method, url, body):
            if method == "PATCH" and f"/releases/{self.fake.index_id}" in url:
                raise KeyboardInterrupt
            return None

        self.fake.failure = cancel
        with self.assertRaises(KeyboardInterrupt):
            self.publish()
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_stale_expected_index_tag_fails_without_patch(self):
        with self.assertRaises(ar.IndexChangedError):
            self.publish(expected_index_tag="engine-approvals-9-9")
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_matching_expected_index_tag_advances(self):
        self.publish(expected_index_tag="engine-approvals-1-1")
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_no_success_until_the_new_pointer_is_visible(self):
        old_body = self.fake.releases[self.fake.index_id]["body"]

        def hide_new_pointer(method, url, body):
            if method == "PATCH" and f"/releases/{self.fake.index_id}" in url:
                self.fake.override_release = {"body": old_body}
            return None

        self.fake.failure = hide_new_pointer
        with self.assertRaises(ar.IndexAdvanceError) as context:
            self.publish()
        self.assertIn("engine-approvals-1-1", str(context.exception))

    def test_legacy_index_immutable_false_is_body_only_discovery(self):
        self.assertFalse(self.fake.releases[self.fake.index_id]["immutable"])
        self.publish()
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_legacy_index_assets_are_untouched(self):
        legacy_id = self.fake.add_asset(
            self.fake.index_id, "legacy-manifest.json", b"legacy"
        )
        self.publish()
        self.assertFalse(
            any(
                request["method"] == "GET"
                and f"/releases/{self.fake.index_id}/assets" in request["url"]
                for request in self.fake.requests
            )
        )
        self.assertFalse(
            any(
                request["method"] == "POST"
                and f"/releases/{self.fake.index_id}/assets" in request["url"]
                for request in self.fake.requests
            )
        )
        self.assertFalse(
            any(f"/releases/assets/{legacy_id}" in request["url"] for request in self.fake.requests)
        )

    def test_unchanged_version_fresh_heartbeat_publishes_new_snapshot(self):
        old_heartbeat = b'{"kind":"heartbeat","lastRunAt":"2026-11-07T00:00:00Z"}'
        old_id = self.fake.add_release(
            "engine-approvals-1-1", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(
                old_id, name, old_heartbeat if name == "ytdlp-heartbeat.json" else content
            )
        fresh = make_files(
            heartbeat=b'{"kind":"heartbeat","lastRunAt":"2026-11-08T00:00:00Z"}'
        )
        result = self.publish(files=fresh)
        self.assertTrue(result.created)
        self.assertEqual("engine-approvals-4242-3", result.snapshot_tag)
        heartbeat_uploads = [
            request
            for request in self.uploads()
            if "ytdlp-heartbeat.json" in request["url"]
            and ".sig" not in request["url"]
        ]
        self.assertEqual(1, len(heartbeat_uploads))
        self.assertEqual(fresh.heartbeat, heartbeat_uploads[0]["body"])
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_dry_run_plan_is_distinct_and_makes_no_requests(self):
        publisher = ar.SnapshotPublisher(None)
        plan = publisher.plan(self.files, 4242, 3, SHA)
        self.assertEqual("engine-approvals-4242-3", plan.snapshot_tag)
        self.assertIn("create draft prerelease", plan.summary())
        self.assertIn("ytdlp-approved.json", plan.summary())
        self.assertEqual([], self.fake.requests)


class HttpsTransportTests(unittest.TestCase):
    class FakeResponse:
        def __init__(self, body, status=200, headers=None):
            self.status = status
            self.headers = headers or {}
            self._body = body
            self.closed = False

        def read(self, size=-1):
            if size < 0:
                size = len(self._body)
            chunk, self._body = self._body[:size], self._body[size:]
            return chunk

        def close(self):
            self.closed = True

    class FakeOpener:
        def __init__(self, response=None, error=None):
            self.requests = []
            self.response = response
            self.error = error

        def __call__(self, request, timeout=None):
            self.requests.append((request, timeout))
            if self.error is not None:
                raise self.error
            return self.response

    def test_rejects_non_https_url(self):
        transport = ar.HttpsGitHubTransport(opener=self.FakeOpener())
        with self.assertRaises(ar.TransportError):
            transport.request(
                "GET", "http://api.github.com/x", max_response_bytes=100
            )

    def test_rejects_non_github_host(self):
        transport = ar.HttpsGitHubTransport(opener=self.FakeOpener())
        with self.assertRaises(ar.TransportError):
            transport.request(
                "GET", "https://evil.example/x", max_response_bytes=100
            )

    def test_rejects_non_443_port(self):
        transport = ar.HttpsGitHubTransport(opener=self.FakeOpener())
        with self.assertRaises(ar.TransportError):
            transport.request(
                "GET", "https://api.github.com:8443/x", max_response_bytes=100
            )

    def test_rejects_invalid_port(self):
        transport = ar.HttpsGitHubTransport(opener=self.FakeOpener())
        with self.assertRaises(ar.TransportError):
            transport.request(
                "GET", "https://api.github.com:notaport/x", max_response_bytes=100
            )

    def test_bounded_read_rejects_oversized_response(self):
        opener = self.FakeOpener(response=self.FakeResponse(b"x" * 11))
        transport = ar.HttpsGitHubTransport(opener=opener)
        with self.assertRaises(ar.ResponseTooLargeError):
            transport.request(
                "GET", "https://api.github.com/x", max_response_bytes=10
            )

    def test_authorization_header_is_sent_and_never_logged(self):
        opener = self.FakeOpener(response=self.FakeResponse(b"{}"))
        transport = ar.HttpsGitHubTransport(token="secret-token", opener=opener)
        transport.request("GET", "https://api.github.com/x", max_response_bytes=100)
        request = opener.requests[0][0]
        self.assertEqual("Bearer secret-token", request.get_header("Authorization"))
        self.assertEqual("2022-11-28", request.headers.get("X-github-api-version"))

        failing = self.FakeOpener(error=urllib.error.URLError("boom"))
        transport = ar.HttpsGitHubTransport(token="secret-token", opener=failing)
        with self.assertRaises(ar.TransportError) as context:
            transport.request(
                "GET", "https://api.github.com/x", max_response_bytes=100
            )
        self.assertNotIn("secret-token", str(context.exception))

    def test_rejects_non_positive_timeout(self):
        with self.assertRaises(ValueError):
            ar.HttpsGitHubTransport(opener=self.FakeOpener(), timeout_seconds=0)
        transport = ar.HttpsGitHubTransport(opener=self.FakeOpener())
        with self.assertRaises(ar.TransportError):
            transport.request(
                "GET", "https://api.github.com/x", max_response_bytes=100,
                timeout_seconds=-1,
            )

    def test_timeout_is_passed_to_the_opener(self):
        opener = self.FakeOpener(response=self.FakeResponse(b"{}"))
        transport = ar.HttpsGitHubTransport(opener=opener, timeout_seconds=3.5)
        transport.request("GET", "https://api.github.com/x", max_response_bytes=100)
        self.assertEqual(3.5, opener.requests[0][1])

    def test_redirect_handler_allows_github_asset_host(self):
        handler = ar._AllowedRedirectHandler(ar.ALLOWED_HOSTS)
        request = urllib.request.Request("https://github.com/x")
        result = handler.redirect_request(
            request, None, 302, "Found",
            {"Location": "https://release-assets.githubusercontent.com/x"},
            "https://release-assets.githubusercontent.com/x",
        )
        self.assertIsNotNone(result)

    def test_redirect_handler_rejects_foreign_host_and_port(self):
        handler = ar._AllowedRedirectHandler(ar.ALLOWED_HOSTS)
        request = urllib.request.Request("https://github.com/x")
        for target in (
            "https://evil.example/x",
            "https://github.com:8443/x",
            "http://github.com/x",
        ):
            with self.subTest(target=target):
                with self.assertRaises(ar.TransportError):
                    handler.redirect_request(
                        request, None, 302, "Found", {"Location": target}, target
                    )

    def test_redirect_handler_rejects_too_many_redirects(self):
        handler = ar._AllowedRedirectHandler(ar.ALLOWED_HOSTS)
        handler._redirects = ar.MAX_REDIRECTS
        request = urllib.request.Request("https://github.com/x")
        with self.assertRaises(ar.TransportError):
            handler.redirect_request(
                request, None, 302, "Found", {"Location": "https://github.com/y"},
                "https://github.com/y",
            )

    def test_response_headers_are_normalized_lowercase(self):
        opener = self.FakeOpener(
            response=self.FakeResponse(
                b"{}", headers={"ETag": "abc", "Content-Type": "application/json"}
            )
        )
        transport = ar.HttpsGitHubTransport(opener=opener)
        response = transport.request(
            "GET", "https://api.github.com/x", max_response_bytes=100
        )
        self.assertEqual("abc", response.header("etag"))
        self.assertTrue(opener.response.closed)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.files = make_files()
        for name, content in self.files.by_name().items():
            (self.directory / name).write_bytes(content)
        self.fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
        self.env = RecordingEnv()
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()

    def run_cli(self, *args, env=None, factory=None):
        return ar.main(
            list(args),
            env=self.env if env is None else env,
            transport_factory=factory or (lambda token: self.fake),
            stdout=self.stdout,
            stderr=self.stderr,
        )

    def test_dry_run_does_not_read_token_or_touch_the_network(self):
        calls = []

        def factory(*, token):
            calls.append(token)
            return self.fake

        code = self.run_cli(
            "publish",
            "--run-id",
            "4242",
            "--run-attempt",
            "3",
            "--commit-sha",
            SHA,
            "--snapshot-dir",
            str(self.directory),
            "--dry-run",
            env=RecordingEnv(),
            factory=factory,
        )
        self.assertEqual(0, code)
        self.assertEqual([], calls)
        self.assertEqual([], self.fake.requests)
        self.assertIn("engine-approvals-4242-3", self.stdout.getvalue())
        self.assertIn("dry-run", self.stdout.getvalue())

    def test_publish_without_token_fails_before_any_request(self):
        code = self.run_cli(
            "publish",
            "--run-id",
            "4242",
            "--run-attempt",
            "3",
            "--commit-sha",
            SHA,
            "--snapshot-dir",
            str(self.directory),
            env=RecordingEnv(),
        )
        self.assertEqual(1, code)
        self.assertIn("GITHUB_TOKEN", self.stderr.getvalue())
        self.assertEqual([], self.fake.requests)

    def test_publish_success_outputs_the_result(self):
        self.env["GITHUB_TOKEN"] = "token"
        code = self.run_cli(
            "publish",
            "--run-id",
            "4242",
            "--run-attempt",
            "3",
            "--commit-sha",
            SHA,
            "--snapshot-dir",
            str(self.directory),
        )
        self.assertEqual(0, code)
        self.assertIn("published", self.stdout.getvalue())
        self.assertIn("engine-approvals-4242-3", self.stdout.getvalue())
        self.assertEqual([], self.fake.violations)

    def test_resolve_reads_no_secret(self):
        env = RecordingEnv()
        code = self.run_cli("resolve", env=env)
        self.assertEqual(0, code)
        self.assertIn("resolved", self.stdout.getvalue())
        self.assertIn("engine-approvals-1-1", self.stdout.getvalue())
        self.assertNotIn("GITHUB_TOKEN", env.read)

    def test_non_positive_timeout_fails(self):
        code = self.run_cli(
            "publish",
            "--run-id",
            "4242",
            "--run-attempt",
            "3",
            "--commit-sha",
            SHA,
            "--snapshot-dir",
            str(self.directory),
            "--dry-run",
            "--timeout",
            "0",
            env=RecordingEnv(),
        )
        self.assertEqual(1, code)
        self.assertIn("timeout", self.stderr.getvalue())

    def test_publish_error_exits_nonzero(self):
        self.fake.failure = (
            lambda method, url, body: 500
            if method == "POST" and url == RELEASES
            else None
        )
        self.env["GITHUB_TOKEN"] = "token"
        code = self.run_cli(
            "publish",
            "--run-id",
            "4242",
            "--run-attempt",
            "3",
            "--commit-sha",
            SHA,
            "--snapshot-dir",
            str(self.directory),
        )
        self.assertEqual(1, code)
        self.assertIn("approval_release:", self.stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
