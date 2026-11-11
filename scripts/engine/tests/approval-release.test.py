#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fake-GitHub tests for the immutable engine-approval publisher and index reader.

No network, no real token, no release, tag, asset or repository mutation.  The
publisher runs on a fake GitHub transport that rejects post-publication
uploads, asset replacement/deletion and make_latest=true; the reader runs on
fake index and snapshot documents.  The production HTTPS adapter is exercised
through an injected opener, never a live endpoint.
"""

import contextlib
import email.message
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import traceback
import unittest
import urllib.error
import urllib.parse
import urllib.request

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
CANARY = "canary-token-0123456789"


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
        self.tag_refs = {}
        self.tag_objects = {}
        self.ref_overrides = {}
        self.tag_object_overrides = {}
        self.document_failures = {}
        self.create_target_overrides = {}
        self.release_list_overrides = {}
        self.release_get_overrides = {}
        self.hides_drafts = False
        self.repository_missing = False
        self.graphql_override = None
        self.graphql_payloads = []
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
        self.tag_refs.setdefault(INDEX_TAG, {"type": "commit", "sha": "0" * 40})
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
        # A draft owns no git ref; only a published release materializes it.
        if not draft:
            self.tag_refs.setdefault(tag, {"type": "commit", "sha": target_commitish})
        return release_id

    def set_tag_ref(self, tag, object_type, sha):
        self.tag_refs[tag] = {"type": object_type, "sha": sha}

    def add_tag_object(self, sha, tag_name, object_type, object_sha):
        self.tag_objects[sha] = {
            "tag": tag_name,
            "type": object_type,
            "sha": object_sha,
        }

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
            if parts.path == "/graphql":
                status, response_headers, payload = self._graphql(method, body)
            else:
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
        normalized = {
            str(name).lower(): str(value)
            for name, value in response_headers.items()
        }
        return ar.TransportResponse(status=status, headers=normalized, body=payload)

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
            release = self.find_by_tag(tag)
            if release is not None and release["draft"]:
                release = None
            return self._release_response(release)
        if method in ("DELETE", "PUT", "PATCH") and path.startswith(RELEASES_PATH + "/assets/"):
            self.violations.append(("asset-mutation", method, path))
            return 405, {}, b""
        if method == "GET" and path.startswith(REPO_PATH + "/git/ref/tags/"):
            tag = urllib.parse.unquote(path[len(REPO_PATH + "/git/ref/tags/"):])
            if tag in self.ref_overrides:
                return self.ref_overrides[tag]
            ref = self.tag_refs.get(tag)
            if ref is None:
                return 404, {}, b""
            return self._json(
                200,
                {
                    "ref": f"refs/tags/{tag}",
                    "node_id": f"refs/tags/{tag}",
                    "url": f"{REPO_API}/git/ref/tags/{tag}",
                    "object": {
                        "type": ref["type"],
                        "sha": ref["sha"],
                        "url": f"{REPO_API}/git/{ref['type']}s/{ref['sha']}",
                    },
                },
            )
        if method == "GET" and path.startswith(REPO_PATH + "/git/tags/"):
            sha = path[len(REPO_PATH + "/git/tags/"):]
            if sha in self.tag_object_overrides:
                return self.tag_object_overrides[sha]
            obj = self.tag_objects.get(sha)
            if obj is None:
                return 404, {}, b""
            return self._json(
                200,
                {
                    "sha": sha,
                    "tag": obj["tag"],
                    "object": {"type": obj["type"], "sha": obj["sha"]},
                },
            )
        if method == "GET" and path.startswith(RELEASES_PATH + "/assets/"):
            asset_id = int(path[len(RELEASES_PATH + "/assets/"):])
            return self._get_asset(asset_id, headers)
        if method == "GET" and path.startswith(RELEASES_PATH + "/") and path.endswith("/assets"):
            release_id = int(path[len(RELEASES_PATH + "/") : -len("/assets")])
            return self._list_assets(release_id)
        if method == "GET" and path == RELEASES_PATH:
            return self._list_releases(query)
        if method == "POST" and path == RELEASES_PATH:
            return self._create_release(body)
        if method in ("GET", "PATCH") and path.startswith(RELEASES_PATH + "/"):
            release_id = int(path[len(RELEASES_PATH + "/") :])
            if method == "GET":
                if release_id in self.release_get_overrides:
                    return self.release_get_overrides[release_id]
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
        failure = self.document_failures.get(path)
        if failure is not None:
            if isinstance(failure, Exception):
                raise failure
            return failure, {}, b""
        if path in self.documents:
            content = self.documents[path]
            if content is None:
                return 404, {}, b""
            return 200, {}, content
        return 404, {}, b""

    def _list_releases(self, query):
        try:
            per_page = int(query.get("per_page", ["30"])[0])
            page = int(query.get("page", ["1"])[0])
        except (ValueError, IndexError):
            return 400, {}, b""
        if per_page < 1 or per_page > 100 or page < 1:
            return 422, {}, b""
        if page in self.release_list_overrides:
            return self.release_list_overrides[page]
        # The authenticated list returns drafts; an unauthenticated or
        # read-only listing hides them.  Newest releases come first.
        releases = [
            release
            for release in reversed(list(self.releases.values()))
            if not (release["draft"] and self.hides_drafts)
        ]
        start = (page - 1) * per_page
        items = releases[start : start + per_page]
        headers = {}
        if start + per_page < len(releases):
            headers["Link"] = (
                f'<{REPO_API}/releases?per_page={per_page}&page={page + 1}>; rel="next"'
            )
        return self._json(200, items, headers)

    def _graphql(self, method, body):
        # GraphQL only accepts POST.  The single modeled query resolves
        # repository.release(tagName:) -> {databaseId, isDraft}, the same
        # exact pending-tag lookup the official GitHub CLI performs: it
        # returns drafts by their pending tag to a push-access caller and
        # never enumerates release history.  hides_drafts models a token
        # without that access; repository_missing models an unresolvable
        # repository node.
        if method != "POST":
            return 404, {}, b""
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
            return 400, {}, b""
        self.graphql_payloads.append(payload)
        override = self.graphql_override
        if callable(override):
            override = override(payload)
        if override is not None:
            status, response_headers, response_body = override
            return status, response_headers, response_body
        variables = payload.get("variables") if isinstance(payload, dict) else None
        tag_name = variables.get("tagName") if isinstance(variables, dict) else None
        if not isinstance(tag_name, str):
            return self._json(
                200, {"errors": [{"message": "Variable $tagName is required"}]}
            )
        if self.repository_missing:
            return self._json(200, {"data": {"repository": None}})
        release = self.find_by_tag(tag_name)
        if release is not None and release["draft"] and self.hides_drafts:
            release = None
        node = None
        if release is not None:
            node = {"databaseId": release["id"], "isDraft": release["draft"]}
        return self._json(200, {"data": {"repository": {"release": node}}})

    def _create_release(self, body):
        payload = json.loads(body.decode("utf-8"))
        if payload.get("make_latest") not in (None, "false", "legacy"):
            self.violations.append(("make_latest", payload.get("make_latest")))
            return 422, {}, b""
        # Tag names are unique across releases, drafts included: a second
        # create for an existing tag is rejected with 422 already_exists.
        if any(
            release["tag_name"] == payload["tag_name"]
            for release in self.releases.values()
        ):
            return self._json(
                422,
                {
                    "message": "Validation Failed",
                    "errors": [{"field": "tag_name", "code": "already_exists"}],
                },
            )
        release_id = self._next_release_id
        self._next_release_id += 1
        target_commitish = self.create_target_overrides.get(
            payload["tag_name"], payload["target_commitish"]
        )
        release = {
            "id": release_id,
            "url": f"{REPO_API}/releases/{release_id}",
            "tag_name": payload["tag_name"],
            "target_commitish": target_commitish,
            "name": payload.get("name", ""),
            "body": payload.get("body", ""),
            "draft": payload.get("draft", False),
            "prerelease": payload.get("prerelease", False),
            "make_latest": payload.get("make_latest", "false"),
            "immutable": self.immutable,
        }
        self.releases[release_id] = release
        self.assets[release_id] = []
        # GitHub materializes the tag ref only for a published release; a
        # draft leaves the ref absent or unchanged.  GitHub ignores
        # target_commitish when the tag already exists.
        if not release["draft"]:
            self.tag_refs.setdefault(
                release["tag_name"],
                {"type": "commit", "sha": payload["target_commitish"]},
            )
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
        was_draft = release["draft"]
        for key in ("draft", "prerelease", "make_latest", "body", "name"):
            if key in payload:
                release[key] = payload[key]
        if was_draft and not release["draft"]:
            # Publication materializes the tag ref at target_commitish; a
            # pre-existing ref wins and target_commitish is ignored.
            self.tag_refs.setdefault(
                release["tag_name"],
                {"type": "commit", "sha": release["target_commitish"]},
            )
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

    def test_required_document_size_caps_are_enforced(self):
        base = f"{DOWNLOAD_PATH}/engine-approvals-4242-3"
        cases = (
            (f"{base}/ytdlp-approved.json", ar.MAX_MANIFEST_BYTES + 1),
            (f"{base}/ytdlp-approved.json.sig", ar.MAX_SIGNATURE_BYTES + 1),
        )
        for url, size in cases:
            with self.subTest(url=url):
                fake = FakeGitHub()
                fake.install_index("engine-approvals-4242-3")
                fake.serve_snapshot("engine-approvals-4242-3", self.files)
                index = ar.resolve_index(fake)
                fake.documents[url] = b"x" * size
                with self.assertRaises(ar.ResponseTooLargeError):
                    ar.read_snapshot_documents(fake, index)


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

    def test_by_tag_hides_draft_releases(self):
        response = self.fake.request(
            "GET",
            f"{RELEASES}/tags/engine-approvals-4242-3",
            max_response_bytes=1024,
        )
        self.assertEqual(404, response.status)

    def test_draft_creation_does_not_materialize_the_tag_ref(self):
        response = self.fake.request(
            "POST",
            RELEASES,
            max_response_bytes=4096,
            body=json.dumps(
                {
                    "tag_name": "engine-approvals-5-1",
                    "target_commitish": SHA,
                    "draft": True,
                    "make_latest": "false",
                }
            ).encode(),
        )
        self.assertEqual(201, response.status)
        self.assertNotIn("engine-approvals-5-1", self.fake.tag_refs)

    def test_publication_materializes_the_tag_ref(self):
        response = self.fake.request(
            "PATCH",
            f"{RELEASES}/{self.release_id}",
            max_response_bytes=4096,
            body=json.dumps({"draft": False, "make_latest": "false"}).encode(),
        )
        self.assertEqual(200, response.status)
        self.assertEqual(
            {"type": "commit", "sha": SHA},
            self.fake.tag_refs["engine-approvals-4242-3"],
        )

    def test_publication_keeps_a_preexisting_tag_ref(self):
        self.fake.set_tag_ref("engine-approvals-4242-3", "commit", "0" * 40)
        response = self.fake.request(
            "PATCH",
            f"{RELEASES}/{self.release_id}",
            max_response_bytes=4096,
            body=json.dumps({"draft": False, "make_latest": "false"}).encode(),
        )
        self.assertEqual(200, response.status)
        self.assertEqual(
            {"type": "commit", "sha": "0" * 40},
            self.fake.tag_refs["engine-approvals-4242-3"],
        )

    def test_second_create_for_an_existing_tag_is_rejected(self):
        response = self.fake.request(
            "POST",
            RELEASES,
            max_response_bytes=4096,
            body=json.dumps(
                {
                    "tag_name": "engine-approvals-4242-3",
                    "target_commitish": SHA,
                    "draft": True,
                    "make_latest": "false",
                }
            ).encode(),
        )
        self.assertEqual(422, response.status)

    def test_release_listing_contains_drafts(self):
        response = self.fake.request(
            "GET",
            f"{RELEASES}?per_page=30&page=1",
            max_response_bytes=65536,
        )
        self.assertEqual(200, response.status)
        tags = [item["tag_name"] for item in json.loads(response.body)]
        self.assertIn("engine-approvals-4242-3", tags)

    def graphql_lookup(self, tag_name):
        return self.fake.request(
            "POST",
            ar.GRAPHQL_URL,
            max_response_bytes=65536,
            body=json.dumps(
                {
                    "query": ar.RELEASE_BY_TAG_QUERY,
                    "variables": {
                        "owner": "L-K-M",
                        "name": "Neutrodyne",
                        "tagName": tag_name,
                    },
                }
            ).encode(),
        )

    def test_graphql_resolves_a_pending_tag_draft(self):
        response = self.graphql_lookup("engine-approvals-4242-3")
        self.assertEqual(200, response.status)
        document = json.loads(response.body)
        self.assertEqual(
            {"databaseId": self.release_id, "isDraft": True},
            document["data"]["repository"]["release"],
        )

    def test_graphql_returns_null_for_an_absent_tag(self):
        response = self.graphql_lookup("engine-approvals-9-9")
        self.assertEqual(200, response.status)
        document = json.loads(response.body)
        self.assertIsNone(document["data"]["repository"]["release"])

    def test_graphql_reports_a_published_release_as_not_a_draft(self):
        published_id = self.fake.add_release(
            "engine-approvals-7-7", target_commitish=SHA, draft=False
        )
        response = self.graphql_lookup("engine-approvals-7-7")
        document = json.loads(response.body)
        self.assertEqual(
            {"databaseId": published_id, "isDraft": False},
            document["data"]["repository"]["release"],
        )

    def test_graphql_hides_drafts_without_push_access(self):
        self.fake.hides_drafts = True
        response = self.graphql_lookup("engine-approvals-4242-3")
        document = json.loads(response.body)
        self.assertIsNone(document["data"]["repository"]["release"])

    def test_graphql_null_release_is_normal_absence(self):
        self.assertIsNone(
            ar.SnapshotPublisher(self.fake)._discover_draft_release(
                "engine-approvals-9-9"
            )
        )


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
        # Publication materialized the new tag ref at the workflow commit.
        self.assertEqual(
            {"type": "commit", "sha": SHA},
            self.fake.tag_refs["engine-approvals-4242-3"],
        )

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

    def test_release_by_tag_wrong_tag_fails_closed(self):
        self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        self.fake.override_release = {"tag_name": "engine-approvals-9-9"}
        with self.assertRaises(ar.SnapshotStateError):
            ar.SnapshotPublisher(self.fake)._get_release_by_tag("engine-approvals-4242-3")
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_release_by_id_wrong_id_fails_closed(self):
        def wrong_id(method, url, body):
            if method == "PATCH" and "/releases/" in url and "/tags/" not in url:
                self.fake.override_release = {"id": 999999}
            return None

        self.fake.failure = wrong_id
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

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

    def test_paginated_asset_listing_fails_closed(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for index in range(101):
            self.fake.add_asset(release_id, f"extra-{index}.bin", b"x")
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
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

    def _draft_discovery_requests(self):
        return [
            request
            for request in self.fake.requests
            if request["method"] == "POST" and request["url"] == ar.GRAPHQL_URL
        ]

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
        # The draft is invisible to the by-tag endpoint; it was recovered
        # through the exact pending-tag lookup, never recreated and never
        # by enumerating release history.
        self.assertTrue(self._draft_discovery_requests())
        self.assertFalse(
            any(
                request["url"].startswith(f"{RELEASES}?")
                for request in self.fake.requests
            )
        )
        self.assertFalse(
            any(
                request["method"] == "POST" and request["url"] == RELEASES
                for request in self.fake.requests
            )
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

    def test_legacy_publish_without_precondition_advances_existing_index(self):
        # Preserved legacy behavior: a caller that states neither precondition
        # still advances whatever index exists.  The canary workflow never
        # uses this path; it always states the verified index state.
        result = self.publish()
        self.assertFalse(result.index_created)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_expect_no_index_creates_a_missing_index(self):
        fake = FakeGitHub()
        result = ar.SnapshotPublisher(fake).publish(
            self.files, 4242, 3, SHA, expect_no_index=True
        )
        self.assertTrue(result.index_created)
        self.assertEqual("engine-approvals-4242-3", fake.index_body_tag())

    def test_expect_no_index_refuses_an_existing_index_without_patch(self):
        with self.assertRaises(ar.IndexChangedError):
            self.publish(expect_no_index=True)
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())
        # The preflight refused before any snapshot was created: no orphan.
        self.assertIsNone(self.fake.find_by_tag("engine-approvals-4242-3"))
        self.assertEqual([], self.uploads())

    def test_expect_no_index_refuses_a_moved_index_without_patch(self):
        # A bootstrap detect output reused after the index appeared must fail
        # closed, not repoint the live index at the stale snapshot.
        self.fake.install_index("engine-approvals-7-7")
        with self.assertRaises(ar.IndexChangedError):
            self.publish(expect_no_index=True)
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-7-7", self.fake.index_body_tag())

    def test_stale_expected_index_tag_fails_before_snapshot_creation(self):
        with self.assertRaises(ar.IndexChangedError):
            self.publish(expected_index_tag="engine-approvals-9-9")
        self.assertIsNone(self.fake.find_by_tag("engine-approvals-4242-3"))
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())

    def test_expected_and_expect_no_index_are_mutually_exclusive(self):
        with self.assertRaises(ar.ValidationError):
            self.publish(
                expected_index_tag="engine-approvals-1-1", expect_no_index=True
            )
        self.assertEqual([], self.fake.requests)

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


class DraftDiscoveryTests(unittest.TestCase):
    """Drafts are invisible to by-tag; recovery is one exact bounded query.

    The official GitHub CLI contract resolves a pending-tag draft through
    ``repository.release(tagName:)`` (databaseId + isDraft) and a REST
    by-id fetch; release history is never enumerated, so discovery does
    not depend on how many immutable snapshots have accumulated.
    """

    def setUp(self):
        self.files = make_files()
        self.fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
        self.publisher = ar.SnapshotPublisher(self.fake)

    def publish(self, **kwargs):
        return self.publisher.publish(self.files, 4242, 3, SHA, **kwargs)

    def graphql_requests(self):
        return [
            request
            for request in self.fake.requests
            if request["url"] == ar.GRAPHQL_URL
        ]

    def list_requests(self):
        return [
            request
            for request in self.fake.requests
            if request["url"].startswith(f"{RELEASES}?")
        ]

    def mutations(self):
        # The GraphQL POST is a read-only query, never a mutation.
        return [
            request
            for request in self.fake.requests
            if request["method"] != "GET" and request["url"] != ar.GRAPHQL_URL
        ]

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
            and f"/releases/{self.fake.index_id}" in request["url"]
        ]

    def fill_releases(self, count):
        for index in range(count):
            self.fake.add_release(
                f"engine-approvals-9{index:04d}-1",
                target_commitish=SHA,
                draft=False,
                immutable=True,
            )

    def pending_draft(self, target_commitish=SHA):
        return self.fake.add_release(
            "engine-approvals-4242-3",
            target_commitish=target_commitish,
            draft=True,
            immutable=True,
        )

    def assert_exact_discovery_only(self, graphql_count=1):
        self.assertEqual(graphql_count, len(self.graphql_requests()))
        self.assertEqual([], self.list_requests())

    def test_fresh_tag_publishes_past_200_releases(self):
        # Immutable snapshots accumulate forever; a fresh publish must not
        # depend on release-history depth.
        self.fill_releases(250)
        result = self.publish()
        self.assertTrue(result.created)
        self.assertTrue(result.published)
        self.assert_exact_discovery_only()
        self.assertEqual([], self.fake.violations)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())
        self.assertEqual(
            {"type": "commit", "sha": SHA},
            self.fake.tag_refs["engine-approvals-4242-3"],
        )

    def test_fresh_tag_publishes_even_with_the_release_list_broken(self):
        # The legacy list endpoint failing cannot affect discovery.
        self.fake.release_list_overrides[1] = (500, {}, b"")
        self.fill_releases(250)
        result = self.publish()
        self.assertTrue(result.created)
        self.assert_exact_discovery_only()

    def test_partial_draft_past_200_releases_resumes_without_enumeration(self):
        release_id = self.pending_draft()
        present = ("ytdlp-approved.json", "ytdlp-heartbeat.json")
        for name in present:
            self.fake.add_asset(release_id, name, self.files.by_name()[name])
        self.fill_releases(250)
        result = self.publish()
        self.assertFalse(result.created)
        self.assertTrue(result.published)
        self.assert_exact_discovery_only()
        # Matching assets already on the draft are reused byte-exact, not
        # re-uploaded; only the missing pair is uploaded before publish.
        self.assertEqual(
            ["ytdlp-approved.json.sig", "ytdlp-heartbeat.json.sig"],
            [
                urllib.parse.parse_qs(urllib.parse.urlsplit(u["url"]).query)[
                    "name"
                ][0]
                for u in self.uploads()
            ],
        )
        self.assertFalse(
            any(
                request["method"] == "POST" and request["url"] == RELEASES
                for request in self.fake.requests
            )
        )
        by_id_gets = [
            request
            for request in self.fake.requests
            if request["method"] == "GET"
            and request["url"] == f"{RELEASES}/{release_id}"
        ]
        self.assertTrue(by_id_gets)
        self.assertEqual([], self.fake.violations)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_graphql_request_is_the_fixed_bounded_query(self):
        self.publish()
        requests = self.graphql_requests()
        self.assertEqual(1, len(requests))
        request = requests[0]
        self.assertEqual("POST", request["method"])
        self.assertEqual("https://api.github.com/graphql", request["url"])
        self.assertEqual(
            "application/json", request["headers"].get("Content-Type")
        )
        payload = json.loads(request["body"])
        self.assertEqual(ar.RELEASE_BY_TAG_QUERY, payload["query"])
        self.assertIn("RepositoryReleaseByTag", payload["query"])
        self.assertIn("repository(owner: $owner, name: $name)", payload["query"])
        self.assertIn("release(tagName: $tagName)", payload["query"])
        self.assertIn("databaseId", payload["query"])
        self.assertIn("isDraft", payload["query"])
        self.assertEqual(
            {
                "owner": "L-K-M",
                "name": "Neutrodyne",
                "tagName": "engine-approvals-4242-3",
            },
            payload["variables"],
        )
        self.assert_exact_discovery_only()

    def test_published_reuse_never_queries_graphql_or_lists(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(release_id, name, content)
        result = self.publish()
        self.assertTrue(result.reused)
        self.assertEqual([], self.graphql_requests())
        self.assertEqual([], self.list_requests())

    def test_graphql_failure_responses_fail_closed_without_mutation(self):
        cases = (
            ("http-500", (500, {}, b"")),
            ("malformed-json", (200, {}, b"garbage")),
            ("top-level-array", (200, {}, b"[]")),
            ("graphql-errors", (200, {}, b'{"errors":[{"message":"boom"}]}')),
            (
                "errors-with-data",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":null}},'
                    b'"errors":[{"message":"partial"}]}',
                ),
            ),
            ("errors-not-a-list", (200, {}, b'{"errors":"boom"}')),
            ("no-data-no-errors", (200, {}, b"{}")),
            ("data-not-an-object", (200, {}, b'{"data":[]}')),
            ("repository-null", (200, {}, b'{"data":{"repository":null}}')),
            ("repository-missing", (200, {}, b'{"data":{}}')),
            ("repository-array", (200, {}, b'{"data":{"repository":[]}}')),
            (
                "release-array",
                (200, {}, b'{"data":{"repository":{"release":[]}}}'),
            ),
            (
                "release-scalar",
                (200, {}, b'{"data":{"repository":{"release":42}}}'),
            ),
            (
                "databaseId-string",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":'
                    b'{"databaseId":"7","isDraft":true}}}}',
                ),
            ),
            (
                "databaseId-bool",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":'
                    b'{"databaseId":true,"isDraft":true}}}}',
                ),
            ),
            (
                "databaseId-zero",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":'
                    b'{"databaseId":0,"isDraft":true}}}}',
                ),
            ),
            (
                "databaseId-missing",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":{"isDraft":true}}}}',
                ),
            ),
            (
                "isDraft-missing",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":{"databaseId":7}}}}',
                ),
            ),
            (
                "isDraft-false",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":'
                    b'{"databaseId":7,"isDraft":false}}}}',
                ),
            ),
            (
                "isDraft-string",
                (
                    200,
                    {},
                    b'{"data":{"repository":{"release":'
                    b'{"databaseId":7,"isDraft":"true"}}}}',
                ),
            ),
        )
        for name, override in cases:
            with self.subTest(name=name):
                fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
                fake.graphql_override = override
                publisher = ar.SnapshotPublisher(fake)
                with self.assertRaises(ar.SnapshotStateError):
                    publisher.publish(self.files, 4242, 3, SHA)
                self.assertIsNone(fake.find_by_tag("engine-approvals-4242-3"))
                self.assertFalse(
                    any(
                        request["method"] != "GET"
                        and request["url"] != ar.GRAPHQL_URL
                        for request in fake.requests
                    )
                )
                self.assertEqual("engine-approvals-1-1", fake.index_body_tag())

    def test_graphql_oversize_response_fails_closed(self):
        self.fake.graphql_override = (
            200,
            {},
            b"x" * (ar.MAX_GRAPHQL_JSON_BYTES + 1),
        )
        with self.assertRaises(ar.ResponseTooLargeError):
            self.publish()
        self.assertEqual([], self.mutations())

    def test_discovery_rest_lookup_errors_fail_closed(self):
        for status in (404, 500):
            with self.subTest(status=status):
                fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
                release_id = fake.add_release(
                    "engine-approvals-4242-3",
                    target_commitish=SHA,
                    draft=True,
                    immutable=True,
                )
                fake.release_get_overrides[release_id] = (status, {}, b"")
                with self.assertRaises(ar.ApprovalReleaseError) as context:
                    ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
                self.assertIn(str(status), str(context.exception))
                self.assertTrue(fake.find_by_tag("engine-approvals-4242-3")["draft"])
                self.assertEqual("engine-approvals-1-1", fake.index_body_tag())

    def test_discovery_rest_malformed_release_fails_closed(self):
        release_id = self.pending_draft()
        self.fake.release_get_overrides[release_id] = (200, {}, b"garbage")
        with self.assertRaises(ar.ApprovalReleaseError):
            self.publish()
        self.assertEqual([], self.mutations())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_discovery_rest_id_mismatch_fails_closed(self):
        self.pending_draft()
        self.fake.override_release = {"id": 999999}
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.mutations())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_discovery_rest_tag_mismatch_fails_closed(self):
        self.pending_draft()
        self.fake.override_release = {"tag_name": "engine-approvals-9-9"}
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.mutations())
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_discovery_rest_release_not_a_draft_fails_closed(self):
        self.pending_draft()
        self.fake.override_release = {"draft": False}
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.mutations())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_draft_hidden_from_exact_lookup_fails_closed_via_422(self):
        # A token without push access gets no pending-tag node either:
        # absence cannot be proven, so the create attempt 422s instead of
        # clobbering.
        self.pending_draft()
        self.fake.hides_drafts = True
        with self.assertRaises(ar.ApprovalReleaseError):
            self.publish()
        self.assertTrue(self.graphql_requests())
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_discovered_draft_targeting_another_commit_fails_before_uploads(self):
        self.pending_draft(target_commitish="b" * 40)
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.uploads())
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_publish_response_that_stays_a_draft_fails_closed(self):
        self.fake.override_release = {"draft": True}
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        # The state transition applied even though the response lied.
        self.assertFalse(
            self.fake.find_by_tag("engine-approvals-4242-3")["draft"]
        )
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_publish_response_with_wrong_tag_identity_fails_closed(self):
        release_id = self.pending_draft()

        def lie_about_tag(method, url, body):
            if method == "PATCH" and url == f"{RELEASES}/{release_id}":
                self.fake.override_release = {"tag_name": "engine-approvals-9-9"}
            return None

        self.fake.failure = lie_about_tag
        with self.assertRaises(ar.SnapshotStateError):
            self.publish()
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_fresh_publish_with_a_preexisting_correct_ref_keeps_it(self):
        self.fake.set_tag_ref("engine-approvals-4242-3", "commit", SHA)
        result = self.publish()
        self.assertTrue(result.created)
        self.assertEqual(
            {"type": "commit", "sha": SHA},
            self.fake.tag_refs["engine-approvals-4242-3"],
        )
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_resolve_and_dry_run_never_enumerate_releases(self):
        self.pending_draft()
        index = ar.resolve_index(self.fake)
        self.assertIsNotNone(index)
        self.assertEqual([], self.list_requests())
        self.assertEqual([], self.graphql_requests())
        plan = ar.SnapshotPublisher(None).plan(self.files, 4242, 3, SHA)
        self.assertIn("engine-approvals-4242-3", plan.snapshot_tag)


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


class _FakeHTTPHandler(urllib.request.HTTPHandler, urllib.request.HTTPSHandler):
    """A real urllib HTTP(S) handler backed by a fake transport (no sockets)."""

    def __init__(self, responder):
        super().__init__()
        self._responder = responder

    def do_open(self, http_class, req):
        return self._responder(req)

    def http_open(self, req):
        return self.do_open(None, req)

    def https_open(self, req):
        return self.do_open(None, req)


class _FakeHTTPResponse:
    def __init__(self, status, headers=None, body=b""):
        self.status = status
        self.code = status
        self.msg = "reason"
        self.headers = headers or {}
        self._body = io.BytesIO(body)
        self.url = ""

    def read(self, size=-1):
        return self._body.read(size)

    def info(self):
        return self.headers

    def close(self):
        pass


def _redirect_responder(hops, redirects):
    """Record each hop and answer from a fixed redirect map."""

    def respond(req):
        hops.append(
            {
                "url": req.full_url,
                "authorization": req.get_header("Authorization"),
                "cookie": req.get_header("Cookie"),
            }
        )
        target = redirects.get(req.full_url)
        if target is not None:
            headers = email.message.Message()
            headers["Location"] = target
            return _FakeHTTPResponse(302, headers, b"")
        return _FakeHTTPResponse(200, {}, b"body")

    return respond


def _real_opener(responder):
    handler = ar._AllowedRedirectHandler(ar.ALLOWED_HOSTS)
    opener = urllib.request.build_opener(handler, _FakeHTTPHandler(responder))
    return handler, opener


class RedirectChainTests(unittest.TestCase):
    """F1: the redirect budget is per chain, on one reusable transport."""

    def test_four_independent_redirect_chains_pass_on_one_transport(self):
        redirects = {
            f"https://api.github.com/releases/assets/{asset_id}": (
                "https://github.com/L-K-M/Neutrodyne/releases/download/"
                "engine-approvals-4242-3/ytdlp-approved.json"
            )
            for asset_id in range(1, 5)
        }
        hops = []
        _, opener = _real_opener(_redirect_responder(hops, redirects))
        transport = ar.HttpsGitHubTransport(opener=opener.open)
        for asset_id in range(1, 5):
            response = transport.request(
                "GET",
                f"https://api.github.com/releases/assets/{asset_id}",
                headers={"Accept": "application/octet-stream"},
                max_response_bytes=1024,
            )
            self.assertEqual(200, response.status)
            self.assertEqual(b"body", response.body)
        self.assertEqual(8, len(hops))
        self.assertEqual(
            [f"https://api.github.com/releases/assets/{i}" for i in range(1, 5)],
            [hop["url"] for hop in hops[::2]],
        )

    def test_redirect_limit_is_bounded_and_transport_stays_usable(self):
        redirects = {
            "https://api.github.com/a": "https://api.github.com/b",
            "https://api.github.com/b": "https://api.github.com/c",
            "https://api.github.com/c": "https://api.github.com/d",
            "https://api.github.com/d": "https://api.github.com/e",
        }
        hops = []
        _, opener = _real_opener(_redirect_responder(hops, redirects))
        with self.assertRaises(ar.TransportError) as context:
            opener.open(
                urllib.request.Request("https://api.github.com/a"), timeout=5
            )
        self.assertIn("too many redirects", str(context.exception))
        self.assertEqual(4, len(hops))
        # The same opener still serves a fresh chain after the rejected one.
        response = opener.open(
            urllib.request.Request("https://api.github.com/ok"), timeout=5
        )
        self.assertEqual(200, response.status)


class RedirectAuthorizationTests(unittest.TestCase):
    """F4: credentials stay on the API origin; redirects never carry them."""

    def _make_opener(self, redirects):
        hops = []
        _, opener = _real_opener(_redirect_responder(hops, redirects))
        return opener, hops

    def _open(self, opener, url, headers=None):
        return opener.open(
            urllib.request.Request(url, headers=headers or {}), timeout=5
        )

    def test_direct_200_keeps_authorization_and_cookie(self):
        opener, hops = self._make_opener({})
        response = self._open(
            opener,
            "https://api.github.com/x",
            {"Authorization": f"Bearer {CANARY}", "Cookie": "session=canary"},
        )
        self.assertEqual(200, response.status)
        self.assertEqual(1, len(hops))
        self.assertEqual(f"Bearer {CANARY}", hops[0]["authorization"])
        self.assertEqual("session=canary", hops[0]["cookie"])

    def test_same_origin_redirect_preserves_authorization(self):
        opener, hops = self._make_opener(
            {"https://api.github.com/a": "https://api.github.com/b"}
        )
        response = self._open(
            opener,
            "https://api.github.com/a",
            {"Authorization": f"Bearer {CANARY}", "Cookie": "session=canary"},
        )
        self.assertEqual(200, response.status)
        self.assertEqual(2, len(hops))
        self.assertEqual(f"Bearer {CANARY}", hops[1]["authorization"])
        self.assertEqual("session=canary", hops[1]["cookie"])

    def test_cross_origin_redirect_strips_authorization_and_cookie(self):
        opener, hops = self._make_opener(
            {
                "https://api.github.com/a": (
                    "https://release-assets.githubusercontent.com/b"
                )
            }
        )
        response = self._open(
            opener,
            "https://api.github.com/a",
            {"Authorization": f"Bearer {CANARY}", "Cookie": "session=canary"},
        )
        self.assertEqual(200, response.status)
        self.assertEqual(2, len(hops))
        self.assertIsNone(hops[1]["authorization"])
        self.assertIsNone(hops[1]["cookie"])
        self.assertNotIn(CANARY, repr(hops[1]))

    def test_cross_origin_to_objects_host_strips_authorization(self):
        opener, hops = self._make_opener(
            {"https://github.com/a": "https://objects.githubusercontent.com/b"}
        )
        response = self._open(
            opener,
            "https://github.com/a",
            {"Authorization": f"Bearer {CANARY}"},
        )
        self.assertEqual(200, response.status)
        self.assertIsNone(hops[1]["authorization"])

    def test_disallowed_redirect_host_fails_closed(self):
        opener, _ = self._make_opener(
            {"https://api.github.com/a": "https://evil.example/b"}
        )
        with self.assertRaises(ar.TransportError):
            self._open(opener, "https://api.github.com/a")

    def test_disallowed_redirect_scheme_and_port_fail_closed(self):
        for target in ("http://github.com/b", "https://github.com:8443/b"):
            with self.subTest(target=target):
                opener, _ = self._make_opener(
                    {"https://api.github.com/a": target}
                )
                with self.assertRaises(ar.TransportError):
                    self._open(opener, "https://api.github.com/a")

    def test_malformed_redirect_target_fails_closed(self):
        opener, _ = self._make_opener(
            {"https://api.github.com/a": "https://github.com:notaport/b"}
        )
        with self.assertRaises(ar.TransportError):
            self._open(opener, "https://api.github.com/a")


class SnapshotTagBindingTests(unittest.TestCase):
    """F2: snapshot tags bind the exact workflow commit, index tag does not."""

    def setUp(self):
        self.files = make_files()
        self.fake = FakeGitHub(index_snapshot_tag="engine-approvals-1-1")
        self.publisher = ar.SnapshotPublisher(self.fake)

    def index_patch(self):
        return [
            request
            for request in self.fake.requests
            if request["method"] == "PATCH"
            and f"/releases/{self.fake.index_id}" in request["url"]
        ]

    def uploads(self):
        return [
            request
            for request in self.fake.requests
            if "uploads.github.com" in request["url"]
        ]

    def test_preexisting_wrong_tag_with_lying_target_field_fails_before_uploads(self):
        self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        self.fake.set_tag_ref("engine-approvals-4242-3", "commit", "b" * 40)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())
        self.assertTrue(self.fake.find_by_tag("engine-approvals-4242-3")["draft"])

    def test_new_snapshot_tag_preexists_wrong_and_request_target_lies(self):
        self.fake.set_tag_ref("engine-approvals-4242-3", "commit", "b" * 40)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        # The wrong ref is rejected before any mutation: no draft is created,
        # nothing is uploaded and the index is untouched.
        self.assertFalse(
            any(request["method"] != "GET" for request in self.fake.requests)
        )
        self.assertIsNone(self.fake.find_by_tag("engine-approvals-4242-3"))
        self.assertEqual([], self.uploads())
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_reused_published_snapshot_wrong_tag_fails_before_index_advance(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(release_id, name, content)
        self.fake.set_tag_ref("engine-approvals-4242-3", "commit", "b" * 40)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_annotated_tag_peels_to_the_exact_commit(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.add_tag_object("c" * 40, tag, "commit", SHA)
        result = self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual(tag, result.snapshot_tag)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_annotated_tag_wrong_peel_target_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.add_tag_object("c" * 40, tag, "commit", "b" * 40)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_unknown_tag_object_type_fails_closed(self):
        self.fake.set_tag_ref("engine-approvals-4242-3", "blob", SHA)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_malformed_tag_ref_sha_fails_closed(self):
        self.fake.set_tag_ref("engine-approvals-4242-3", "commit", "not-a-sha")
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_tag_object_peel_cycle_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.add_tag_object("c" * 40, tag, "tag", "d" * 40)
        self.fake.add_tag_object("d" * 40, tag, "tag", "c" * 40)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_tag_peel_limit_is_bounded(self):
        tag = "engine-approvals-4242-3"
        shas = [f"{index}" * 40 for index in range(1, ar.MAX_TAG_PEElS + 2)]
        self.fake.set_tag_ref(tag, "tag", shas[0])
        for index, sha in enumerate(shas):
            if index + 1 < len(shas):
                self.fake.add_tag_object(sha, tag, "tag", shas[index + 1])
            else:
                self.fake.add_tag_object(sha, tag, "commit", SHA)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_oversize_tag_ref_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.ref_overrides[tag] = (
            200, {}, b"x" * (ar.MAX_GIT_REF_JSON_BYTES + 1)
        )
        with self.assertRaises(ar.ResponseTooLargeError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_malformed_tag_ref_json_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.ref_overrides[tag] = (200, {}, b"garbage")
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_missing_tag_ref_fails_closed_for_snapshot(self):
        # A snapshot tag that stays missing after publication blocks the
        # index advance even though the publish itself succeeded.
        tag = "engine-approvals-4242-3"
        self.fake.ref_overrides[tag] = (404, {}, b"")
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertFalse(self.fake.find_by_tag(tag)["draft"])
        self.assertEqual([], self.index_patch())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_oversize_tag_object_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.tag_object_overrides["c" * 40] = (
            200, {}, b"x" * (ar.MAX_TAG_OBJECT_JSON_BYTES + 1)
        )
        with self.assertRaises(ar.ResponseTooLargeError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_malformed_tag_object_json_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.tag_object_overrides["c" * 40] = (200, {}, b"garbage")
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_missing_tag_object_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.tag_object_overrides["c" * 40] = (404, {}, b"")
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_tag_object_name_mismatch_fails_closed(self):
        tag = "engine-approvals-4242-3"
        self.fake.set_tag_ref(tag, "tag", "c" * 40)
        self.fake.add_tag_object("c" * 40, "engine-approvals-9-9", "commit", SHA)
        with self.assertRaises(ar.SnapshotStateError):
            self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual([], self.index_patch())

    def test_index_tag_is_not_required_to_match_each_new_commit(self):
        # The stable index is legacy discovery: its tag/assets stay while the
        # body pointer advances across workflow commits.
        self.assertEqual(
            {"type": "commit", "sha": "0" * 40}, self.fake.tag_refs[INDEX_TAG]
        )
        result = self.publisher.publish(self.files, 4242, 3, SHA)
        self.assertEqual("engine-approvals-4242-3", result.snapshot_tag)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())
        self.assertEqual(
            {"type": "commit", "sha": "0" * 40}, self.fake.tag_refs[INDEX_TAG]
        )
        self.assertEqual(
            "0" * 40, self.fake.releases[self.fake.index_id]["target_commitish"]
        )
        self.assertFalse(
            any(
                request["method"] in ("POST", "PATCH", "DELETE")
                and f"/releases/{self.fake.index_id}" in request["url"]
                and "/assets" in request["url"]
                for request in self.fake.requests
            )
        )

    def test_bootstrap_newly_created_index_tag_is_verified(self):
        fake = FakeGitHub()
        self.assertNotIn(INDEX_TAG, fake.tag_refs)
        ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
        self.assertEqual({"type": "commit", "sha": SHA}, fake.tag_refs[INDEX_TAG])
        self.assertEqual(SHA, fake.releases[fake.index_id]["target_commitish"])

    def test_bootstrap_reuses_existing_index_tag_without_requiring_new_commit(self):
        fake = FakeGitHub()
        fake.set_tag_ref(INDEX_TAG, "commit", "0" * 40)
        result = ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
        self.assertTrue(result.index_created)
        self.assertEqual({"type": "commit", "sha": "0" * 40}, fake.tag_refs[INDEX_TAG])
        self.assertEqual("engine-approvals-4242-3", fake.index_body_tag())

    def test_bootstrap_existing_index_tag_response_target_may_be_legacy(self):
        # GitHub can report the pre-existing tag's commit in the response even
        # though the request named the new workflow commit.  That must not
        # make the bootstrap impossible.
        fake = FakeGitHub()
        fake.set_tag_ref(INDEX_TAG, "commit", "0" * 40)
        fake.create_target_overrides[INDEX_TAG] = "0" * 40
        result = ar.SnapshotPublisher(fake).publish(self.files, 4242, 3, SHA)
        self.assertTrue(result.index_created)
        self.assertEqual({"type": "commit", "sha": "0" * 40}, fake.tag_refs[INDEX_TAG])
        self.assertEqual("engine-approvals-4242-3", fake.index_body_tag())


class TokenRedactionTests(unittest.TestCase):
    """F3: a malformed token never reaches a request, log or traceback."""

    def test_newline_token_is_rejected_before_any_request(self):
        opener = HttpsTransportTests.FakeOpener()
        with self.assertRaises(ar.ValidationError) as context:
            ar.HttpsGitHubTransport(token=CANARY + "\nX-Evil: 1", opener=opener)
        self.assertNotIn(CANARY, str(context.exception))
        self.assertNotIn("X-Evil", str(context.exception))
        self.assertNotIn(CANARY, repr(context.exception))
        self.assertEqual([], opener.requests)

    def test_carriage_return_token_is_rejected_before_any_request(self):
        opener = HttpsTransportTests.FakeOpener()
        with self.assertRaises(ar.ValidationError) as context:
            ar.HttpsGitHubTransport(token=CANARY + "\rX-Evil: 1", opener=opener)
        self.assertNotIn(CANARY, str(context.exception))
        self.assertEqual([], opener.requests)

    def test_non_latin1_token_is_rejected_before_any_request(self):
        opener = HttpsTransportTests.FakeOpener()
        with self.assertRaises(ar.ValidationError) as context:
            ar.HttpsGitHubTransport(token=CANARY + "\u2603", opener=opener)
        self.assertNotIn(CANARY, str(context.exception))
        self.assertNotIn("\u2603", str(context.exception))
        self.assertEqual([], opener.requests)

    def test_header_value_error_is_sanitized(self):
        opener = HttpsTransportTests.FakeOpener(
            error=ValueError(f"Invalid header value b'Bearer {CANARY}'")
        )
        transport = ar.HttpsGitHubTransport(token=CANARY, opener=opener)
        with self.assertRaises(ar.TransportError) as context:
            transport.request(
                "GET", "https://api.github.com/x", max_response_bytes=100
            )
        self.assertNotIn(CANARY, str(context.exception))
        self.assertNotIn(CANARY, repr(context.exception))
        self.assertIsNone(context.exception.__cause__)
        self.assertTrue(context.exception.__suppress_context__)
        formatted = "".join(
            traceback.format_exception(
                type(context.exception),
                context.exception,
                context.exception.__traceback__,
            )
        )
        self.assertNotIn(CANARY, formatted)


class HeartbeatToleranceTests(unittest.TestCase):
    """F5: the reader treats heartbeat as diagnostic; the publisher does not."""

    def setUp(self):
        self.files = make_files()
        self.fake = FakeGitHub()
        self.fake.install_index("engine-approvals-4242-3")
        self.fake.serve_snapshot("engine-approvals-4242-3", self.files)
        self.index = ar.resolve_index(self.fake)
        self.base = f"{DOWNLOAD_PATH}/engine-approvals-4242-3"

    def test_heartbeat_http_500_is_tolerated(self):
        self.fake.document_failures[f"{self.base}/ytdlp-heartbeat.json"] = 500
        documents = ar.read_snapshot_documents(self.fake, self.index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertEqual(self.files.manifest_signature, documents.manifest_signature)
        self.assertIsNone(documents.heartbeat)
        self.assertIsNone(documents.heartbeat_signature)

    def test_heartbeat_signature_500_yields_no_pair(self):
        self.fake.document_failures[f"{self.base}/ytdlp-heartbeat.json.sig"] = 500
        documents = ar.read_snapshot_documents(self.fake, self.index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertEqual(self.files.manifest_signature, documents.manifest_signature)
        self.assertIsNone(documents.heartbeat)
        self.assertIsNone(documents.heartbeat_signature)

    def test_heartbeat_timeout_is_tolerated(self):
        self.fake.document_failures[f"{self.base}/ytdlp-heartbeat.json"] = TimeoutError(
            "timed out"
        )
        documents = ar.read_snapshot_documents(self.fake, self.index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertIsNone(documents.heartbeat)
        self.assertIsNone(documents.heartbeat_signature)

    def test_heartbeat_oversize_is_tolerated(self):
        self.fake.documents[f"{self.base}/ytdlp-heartbeat.json"] = b"x" * (
            ar.MAX_HEARTBEAT_BYTES + 1
        )
        documents = ar.read_snapshot_documents(self.fake, self.index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertEqual(self.files.manifest_signature, documents.manifest_signature)
        self.assertIsNone(documents.heartbeat)
        self.assertIsNone(documents.heartbeat_signature)

    def test_heartbeat_transport_error_is_tolerated(self):
        self.fake.document_failures[f"{self.base}/ytdlp-heartbeat.json"] = (
            ar.TransportError("boom")
        )
        documents = ar.read_snapshot_documents(self.fake, self.index)
        self.assertEqual(self.files.manifest, documents.manifest)
        self.assertIsNone(documents.heartbeat)

    def test_manifest_http_500_still_fails(self):
        self.fake.document_failures[f"{self.base}/ytdlp-approved.json"] = 500
        with self.assertRaises(ar.SnapshotReadError):
            ar.read_snapshot_documents(self.fake, self.index)

    def test_manifest_signature_http_500_still_fails(self):
        self.fake.document_failures[f"{self.base}/ytdlp-approved.json.sig"] = 500
        with self.assertRaises(ar.SnapshotReadError):
            ar.read_snapshot_documents(self.fake, self.index)

    def test_publisher_still_requires_all_four_assets(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            if name != "ytdlp-heartbeat.json":
                self.fake.add_asset(release_id, name, content)
        with self.assertRaises(ar.SnapshotStateError):
            ar.SnapshotPublisher(self.fake).publish(self.files, 4242, 3, SHA)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_publisher_still_verifies_heartbeat_bytes(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=False, immutable=True
        )
        for name, content in self.files.by_name().items():
            self.fake.add_asset(release_id, name, content)
        self.fake.corrupt_assets.add("ytdlp-heartbeat.json")
        with self.assertRaises(ar.SnapshotStateError):
            ar.SnapshotPublisher(self.fake).publish(self.files, 4242, 3, SHA)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())


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

    def test_publish_via_main_with_deep_release_history(self):
        # A real repository accumulates immutable snapshots forever; the
        # CLI publish must not depend on release-history depth.
        for index in range(250):
            self.fake.add_release(
                f"engine-approvals-8{index:04d}-1",
                target_commitish=SHA,
                draft=False,
                immutable=True,
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
        self.assertEqual(0, code)
        self.assertIn("published", self.stdout.getvalue())
        self.assertEqual([], self.fake.violations)
        self.assertFalse(
            any(
                request["url"].startswith(f"{RELEASES}?")
                for request in self.fake.requests
            )
        )

    def test_publish_via_main_resumes_a_partial_draft_with_matching_assets(self):
        release_id = self.fake.add_release(
            "engine-approvals-4242-3", target_commitish=SHA, draft=True, immutable=True
        )
        for name in ("ytdlp-approved.json", "ytdlp-heartbeat.json"):
            self.fake.add_asset(release_id, name, self.files.by_name()[name])
        for index in range(210):
            self.fake.add_release(
                f"engine-approvals-8{index:04d}-1",
                target_commitish=SHA,
                draft=False,
                immutable=True,
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
        self.assertEqual(0, code)
        self.assertIn("published", self.stdout.getvalue())
        uploads = [
            request
            for request in self.fake.requests
            if "uploads.github.com" in request["url"]
        ]
        self.assertEqual(2, len(uploads))
        self.assertFalse(
            any(
                request["url"].startswith(f"{RELEASES}?")
                for request in self.fake.requests
            )
        )
        self.assertEqual([], self.fake.violations)

    def test_invalid_token_makes_no_requests_and_is_never_printed(self):
        self.env["GITHUB_TOKEN"] = CANARY + "\nX-Evil: 1"
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
        self.assertNotIn(CANARY, self.stdout.getvalue())
        self.assertNotIn(CANARY, self.stderr.getvalue())
        self.assertNotIn("X-Evil", self.stderr.getvalue())
        self.assertEqual([], self.fake.requests)
        self.assertIn("GITHUB_TOKEN", self.env.read)

    def test_invalid_token_is_not_read_by_dry_run_or_resolve(self):
        env = RecordingEnv()
        env["GITHUB_TOKEN"] = CANARY + "\nX-Evil: 1"
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
            env=env,
        )
        self.assertEqual(0, code)
        self.assertNotIn("GITHUB_TOKEN", env.read)
        self.assertNotIn(CANARY, self.stdout.getvalue())
        self.assertNotIn(CANARY, self.stderr.getvalue())
        self.assertEqual([], self.fake.requests)

    def test_main_sanitizes_unexpected_value_error(self):
        canary = CANARY + "\u2603"

        def factory(*, token):
            raise ValueError(f"underlying failure: {canary}")

        self.env["GITHUB_TOKEN"] = CANARY
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
            factory=factory,
        )
        self.assertEqual(1, code)
        self.assertNotIn(canary, self.stderr.getvalue())
        self.assertNotIn(CANARY, self.stderr.getvalue())

    def test_success_output_never_contains_the_token(self):
        self.env["GITHUB_TOKEN"] = CANARY
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
        self.assertNotIn(CANARY, self.stdout.getvalue())
        self.assertNotIn(CANARY, self.stderr.getvalue())

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

    def test_publish_with_expect_no_index_creates_a_missing_index(self):
        self.fake = FakeGitHub()
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
            "--expect-no-index",
        )
        self.assertEqual(0, code)
        self.assertIn("published", self.stdout.getvalue())
        self.assertIsNotNone(self.fake.index_id)
        self.assertEqual("engine-approvals-4242-3", self.fake.index_body_tag())

    def test_publish_with_expect_no_index_refuses_an_existing_index(self):
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
            "--expect-no-index",
        )
        self.assertEqual(1, code)
        self.assertIn("index exists", self.stderr.getvalue())
        self.assertEqual("engine-approvals-1-1", self.fake.index_body_tag())

    def test_expected_index_tag_and_expect_no_index_are_mutually_exclusive(self):
        self.env["GITHUB_TOKEN"] = "token"
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as context:
            self.run_cli(
                "publish",
                "--run-id",
                "4242",
                "--run-attempt",
                "3",
                "--commit-sha",
                SHA,
                "--snapshot-dir",
                str(self.directory),
                "--expected-index-tag",
                "engine-approvals-1-1",
                "--expect-no-index",
            )
        self.assertEqual(2, context.exception.code)
        self.assertIn("not allowed with argument", stderr.getvalue())
        self.assertEqual([], self.fake.requests)

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
