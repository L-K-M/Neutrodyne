#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Publish immutable engine-approval snapshots and read the stable index.

The stable ``engine-approvals`` GitHub release is a discovery index: its body
is a schema-1 JSON record naming exactly one immutable snapshot tag.  Every
snapshot is a separate published prerelease carrying the four already-signed
documents ``ytdlp-approved.json(.sig)`` and ``ytdlp-heartbeat.json(.sig)``.
This module publishes those snapshots and reads the index.  It never signs,
never verifies a signature and never reads a private key; the protected
canary workflow owns signing and the clients own verification.

Publisher state machine (one serialized deployment at a time):

    unique draft prerelease engine-approvals-<run_id>-<run_attempt>
      -> upload all four unmodified files
      -> publish (draft=false, prerelease=true, make_latest=false)
      -> verify the complete published snapshot
      -> advance the stable index body to the snapshot tag

The helper never uploads after publication, never replaces or deletes an
asset, never marks a snapshot or the index as latest, never mutates the
repository settings and never touches the legacy index assets.  A failed
pointer write leaves the completed snapshot unindexed and the old pointer
intact; success is reported only after the new pointer is visible.  A newly
published snapshot must be reported immutable by the API; the legacy index
release may stay body-only discovery with ``immutable: false``.

Concurrency precondition (not a linearizable CAS): GitHub's release-body
PATCH has no compare-and-swap.  ``expected_index_tag`` is checked before the
PATCH and a missing index is a mismatch when the caller supplied one; a
caller that needs exclusivity must serialize publishing (for example one
workflow concurrency group with ``cancel-in-progress: false``).  The
later protected canary workflow must verify the current signed manifest and
its sequence under that serial group and reject stale approve artifacts.
The unsigned index is discovery metadata only, never an approval authority.

CLI:
    approval_release.py publish --run-id N --run-attempt N --commit-sha SHA \
        --snapshot-dir DIR [--dry-run] [--expected-index-tag TAG]
    approval_release.py resolve

``publish`` reads the token only from ``GITHUB_TOKEN`` and only for a real
publish; ``--dry-run`` and ``resolve`` never read a secret.  Tests use a fake
transport and never perform a real publication.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Protocol

# --- Fixed repository and endpoint constants --------------------------------

REPO_OWNER = "L-K-M"
REPO_NAME = "Neutrodyne"
REPO_API_PREFIX = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
INDEX_TAG = "engine-approvals"
INDEX_URL = f"{REPO_API_PREFIX}/releases/tags/{INDEX_TAG}"
APPROVAL_BASE_URL = f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases"
UPLOADS_BASE = "https://uploads.github.com"
API_VERSION = "2022-11-28"
USER_AGENT = "neutrodyne-engine-approval-publisher/1.0"

SCHEMA = 1
MAX_INDEX_BODY_CHARS = 4 * 1024
MAX_INDEX_JSON_BYTES = 64 * 1024
MAX_RELEASE_JSON_BYTES = 64 * 1024
MAX_ASSET_LIST_BYTES = 64 * 1024
MAX_ASSET_JSON_BYTES = 64 * 1024
MAX_MANIFEST_BYTES = 64 * 1024
MAX_SIGNATURE_BYTES = 4 * 1024
MAX_HEARTBEAT_BYTES = 4 * 1024
DEFAULT_TIMEOUT_SECONDS = 15.0
MAX_REDIRECTS = 3

ASSET_NAMES = (
    "ytdlp-approved.json",
    "ytdlp-approved.json.sig",
    "ytdlp-heartbeat.json",
    "ytdlp-heartbeat.json.sig",
)
ASSET_LIMITS = {
    "ytdlp-approved.json": MAX_MANIFEST_BYTES,
    "ytdlp-approved.json.sig": MAX_SIGNATURE_BYTES,
    "ytdlp-heartbeat.json": MAX_HEARTBEAT_BYTES,
    "ytdlp-heartbeat.json.sig": MAX_SIGNATURE_BYTES,
}
SNAPSHOT_TAG_RE = re.compile(r"engine-approvals-[0-9]+-[0-9]+")
SHA_RE = re.compile(r"[0-9a-f]{40}(?:[0-9a-f]{24})?")
ALLOWED_HOSTS = frozenset(
    {
        "api.github.com",
        "uploads.github.com",
        "github.com",
        "www.github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    }
)


# --- Errors -----------------------------------------------------------------


class ApprovalReleaseError(Exception):
    """A publication or index-advance failure with a safe, actionable message."""


class ValidationError(ApprovalReleaseError):
    """An input failed local validation."""


class SnapshotStateError(ApprovalReleaseError):
    """An existing snapshot release is incomplete, wrong or unexpected."""


class SnapshotNotImmutableError(ApprovalReleaseError):
    """The API reported a newly published snapshot as not immutable."""


class IndexAdvanceError(ApprovalReleaseError):
    """The stable index body could not be advanced or verified."""


class IndexChangedError(IndexAdvanceError):
    """The index pointed elsewhere than the caller's expected tag."""


class MissingTokenError(ApprovalReleaseError):
    """A real publish was requested without GITHUB_TOKEN."""


class IndexDiscoveryError(Exception):
    """The index release exists but is not the exact discovery record."""


class SnapshotReadError(Exception):
    """A snapshot document could not be fetched within its bound."""


class TransportError(Exception):
    """A bounded transport failure (network, timeout, status, size)."""


class ResponseTooLargeError(TransportError):
    """The response exceeded the caller's bound."""


# --- Transport --------------------------------------------------------------


class GitHubTransport(Protocol):
    """The small HTTP seam the publisher and reader run on."""

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        body: bytes | None = None,
        max_response_bytes: int,
        timeout_seconds: float | None = None,
    ) -> "TransportResponse":
        """Perform one bounded request and return the response."""
        ...


@dataclass(frozen=True)
class TransportResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes

    def header(self, name: str, default: str | None = None) -> str | None:
        return self.headers.get(name.lower(), default)

    def json(self) -> object:
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as invalid:
            raise TransportError("response is not UTF-8 JSON") from invalid


class _AllowedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Follow GitHub asset redirects only, never an arbitrary host."""

    def __init__(self, allowed_hosts: frozenset[str]):
        super().__init__()
        self._allowed_hosts = allowed_hosts
        self._redirects = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if self._redirects >= MAX_REDIRECTS:
            raise TransportError("too many redirects")
        target = urllib.parse.urlsplit(newurl)
        try:
            port = target.port
        except ValueError as invalid:
            raise TransportError("redirect target has an invalid port") from invalid
        if (
            target.scheme != "https"
            or target.hostname not in self._allowed_hosts
            or port not in (None, 443)
        ):
            raise TransportError("redirect target is not an allowed GitHub host")
        self._redirects += 1
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _read_bounded(stream, max_bytes: int) -> bytes:
    """Read to EOF or fail once the body exceeds ``max_bytes``."""
    chunks: list[bytes] = []
    total = 0
    while total <= max_bytes:
        chunk = stream.read(min(65536, max_bytes - total + 1))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        if total > max_bytes:
            raise ResponseTooLargeError(f"response exceeds {max_bytes} bytes")
    return b"".join(chunks)


class HttpsGitHubTransport:
    """Production transport: fixed HTTPS GitHub endpoints, bounded reads.

    The token is placed in the Authorization header and never logged.  Only
    the fixed GitHub API, upload and release-asset hosts are reachable; the
    request URL is validated before opening, so a body-supplied URL can never
    redirect the helper off GitHub.
    """

    def __init__(
        self,
        *,
        token: str | None = None,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        user_agent: str = USER_AGENT,
        opener=None,
    ):
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        self._token = token
        self._timeout = timeout_seconds
        self._user_agent = user_agent
        if opener is None:
            opener = urllib.request.build_opener(
                _AllowedRedirectHandler(ALLOWED_HOSTS)
            ).open
        self._open = opener

    @staticmethod
    def _validate_url(url: str) -> None:
        parts = urllib.parse.urlsplit(url)
        try:
            port = parts.port
        except ValueError as invalid:
            raise TransportError(f"refusing URL with an invalid port: {url}") from invalid
        if parts.scheme != "https" or not parts.hostname:
            raise TransportError(f"refusing non-HTTPS request: {url}")
        if parts.username or parts.password:
            raise TransportError(f"refusing URL with credentials: {url}")
        if parts.fragment:
            raise TransportError(f"refusing URL with a fragment: {url}")
        if parts.hostname not in ALLOWED_HOSTS:
            raise TransportError(f"refusing non-GitHub host: {parts.hostname}")
        if port not in (None, 443):
            raise TransportError(f"refusing non-HTTPS port: {url}")

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        body: bytes | None = None,
        max_response_bytes: int,
        timeout_seconds: float | None = None,
    ) -> TransportResponse:
        self._validate_url(url)
        merged = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": API_VERSION,
            "User-Agent": self._user_agent,
        }
        if self._token:
            merged["Authorization"] = f"Bearer {self._token}"
        if headers:
            merged.update(headers)
        request = urllib.request.Request(url, data=body, headers=merged, method=method)
        timeout = self._timeout if timeout_seconds is None else timeout_seconds
        if timeout <= 0:
            raise TransportError("timeout must be positive")
        try:
            response = self._open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        except urllib.error.URLError as error:
            reason = getattr(error, "reason", error)
            raise TransportError(f"{method} {url} failed: {reason}") from error
        except OSError as error:
            raise TransportError(f"{method} {url} failed: {error}") from error
        try:
            status = getattr(response, "status", None) or getattr(response, "code", 0)
            response_headers = {
                str(name).lower(): str(value)
                for name, value in response.headers.items()
            }
            payload = _read_bounded(response, max_response_bytes)
        finally:
            close = getattr(response, "close", None)
            if close is not None:
                close()
        return TransportResponse(status=status, headers=response_headers, body=payload)


# --- Snapshot files ---------------------------------------------------------


@dataclass(frozen=True)
class SnapshotFiles:
    """The four already-signed snapshot documents, keyed by fixed asset name."""

    manifest: bytes
    manifest_signature: bytes
    heartbeat: bytes
    heartbeat_signature: bytes

    @classmethod
    def from_paths(
        cls,
        *,
        manifest: Path,
        manifest_signature: Path,
        heartbeat: Path,
        heartbeat_signature: Path,
    ) -> "SnapshotFiles":
        return cls(
            manifest=Path(manifest).read_bytes(),
            manifest_signature=Path(manifest_signature).read_bytes(),
            heartbeat=Path(heartbeat).read_bytes(),
            heartbeat_signature=Path(heartbeat_signature).read_bytes(),
        )

    @classmethod
    def from_dir(cls, directory: Path) -> "SnapshotFiles":
        base = Path(directory)
        return cls.from_paths(
            manifest=base / "ytdlp-approved.json",
            manifest_signature=base / "ytdlp-approved.json.sig",
            heartbeat=base / "ytdlp-heartbeat.json",
            heartbeat_signature=base / "ytdlp-heartbeat.json.sig",
        )

    def by_name(self) -> dict[str, bytes]:
        return {
            "ytdlp-approved.json": self.manifest,
            "ytdlp-approved.json.sig": self.manifest_signature,
            "ytdlp-heartbeat.json": self.heartbeat,
            "ytdlp-heartbeat.json.sig": self.heartbeat_signature,
        }

    def sha256(self, name: str) -> str:
        return hashlib.sha256(self.by_name()[name]).hexdigest()


def validate_snapshot_files(files: SnapshotFiles) -> None:
    for name, data in files.by_name().items():
        if not data:
            raise ValidationError(f"{name} is empty")
        limit = ASSET_LIMITS[name]
        if len(data) > limit:
            raise ValidationError(f"{name} is {len(data)} bytes, limit is {limit}")


# --- Index reader -----------------------------------------------------------


@dataclass(frozen=True)
class IndexRelease:
    """The validated discovery record plus its derived snapshot location."""

    release_id: int | None
    tag_name: str
    url: str
    body: str
    snapshot_tag: str
    snapshot_base_url: str
    etag: str | None


@dataclass(frozen=True)
class SnapshotDocuments:
    """All four documents read from one snapshot, even if the index moves."""

    snapshot_tag: str
    snapshot_base_url: str
    manifest: bytes
    manifest_signature: bytes
    heartbeat: bytes | None
    heartbeat_signature: bytes | None


def repo_api_prefix(index_url: str) -> str:
    """The ``<api>/repos/<owner>/<repo>/releases/`` prefix of the index URL.

    Mirrors the pinned Kotlin client: anything else yields an empty prefix,
    which makes the release-identity check fail closed.
    """
    marker = f"/tags/{INDEX_TAG}"
    if marker not in index_url:
        return ""
    base = index_url.rsplit(marker, 1)[0]
    if "/repos/" in base and base.endswith("/releases"):
        return base + "/"
    return ""


def parse_index_body(body: str) -> str:
    """The schema-1 snapshot tag from an index body, or IndexDiscoveryError."""
    try:
        record = json.loads(body)
    except json.JSONDecodeError as invalid:
        raise IndexDiscoveryError("index body is not JSON") from invalid
    if not isinstance(record, dict):
        raise IndexDiscoveryError("index body is not a JSON object")
    schema = record.get("schema")
    snapshot_tag = record.get("snapshotTag")
    if isinstance(schema, bool) or not isinstance(schema, int) or schema != SCHEMA:
        raise IndexDiscoveryError("index body schema is not 1")
    if not isinstance(snapshot_tag, str) or not SNAPSHOT_TAG_RE.fullmatch(snapshot_tag):
        raise IndexDiscoveryError("index snapshotTag is not engine-approvals-<run>-<attempt>")
    return snapshot_tag


def parse_index_release(
    document: object,
    *,
    index_url: str,
    approval_base_url: str,
    etag: str | None = None,
) -> IndexRelease:
    """Validate the release-by-tag response as the exact discovery record."""
    if not isinstance(document, dict):
        raise IndexDiscoveryError("index release is not a JSON object")
    url = document.get("url")
    tag_name = document.get("tag_name")
    draft = document.get("draft")
    prerelease = document.get("prerelease")
    body = document.get("body")
    if not isinstance(url, str):
        raise IndexDiscoveryError("index release url is not a string")
    if not isinstance(tag_name, str):
        raise IndexDiscoveryError("index release tag_name is not a string")
    if not isinstance(draft, bool):
        raise IndexDiscoveryError("index release draft is not a boolean")
    if not isinstance(prerelease, bool):
        raise IndexDiscoveryError("index release prerelease is not a boolean")
    if not isinstance(body, str):
        raise IndexDiscoveryError("index release body is not a string")
    if tag_name != INDEX_TAG:
        raise IndexDiscoveryError(f"index tag is {tag_name!r}, expected {INDEX_TAG!r}")
    if draft:
        raise IndexDiscoveryError("index release is a draft")
    if not prerelease:
        raise IndexDiscoveryError("index release is not a prerelease")
    prefix = repo_api_prefix(index_url)
    if not prefix or not url.startswith(prefix):
        raise IndexDiscoveryError("index release url is not in the configured repository")
    if len(body) > MAX_INDEX_BODY_CHARS:
        raise IndexDiscoveryError(
            f"index body is {len(body)} characters, limit is {MAX_INDEX_BODY_CHARS}"
        )
    snapshot_tag = parse_index_body(body)
    release_id = document.get("id")
    if release_id is not None and (
        isinstance(release_id, bool) or not isinstance(release_id, int) or release_id <= 0
    ):
        raise IndexDiscoveryError("index release id is not a positive integer")
    return IndexRelease(
        release_id=release_id,
        tag_name=tag_name,
        url=url,
        body=body,
        snapshot_tag=snapshot_tag,
        snapshot_base_url=f"{approval_base_url}/download/{snapshot_tag}",
        etag=etag,
    )


def resolve_index(
    transport: GitHubTransport,
    *,
    index_url: str = INDEX_URL,
    approval_base_url: str = APPROVAL_BASE_URL,
    max_bytes: int = MAX_INDEX_JSON_BYTES,
    timeout_seconds: float | None = None,
) -> IndexRelease | None:
    """Fetch the stable index release once; None only when it is missing.

    A 404 is the explicit missing state.  Every other defect (bad JSON, wrong
    identity, draft, oversized body, unknown schema, bad tag grammar) raises
    IndexDiscoveryError and is never interpreted as a bootstrapable absence.
    """
    response = transport.request(
        "GET",
        index_url,
        headers={"Accept": "application/vnd.github+json"},
        max_response_bytes=max_bytes,
        timeout_seconds=timeout_seconds,
    )
    if response.status == 404:
        return None
    if response.status != 200:
        raise IndexDiscoveryError(f"index request failed with HTTP {response.status}")
    try:
        document = json.loads(response.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as invalid:
        raise IndexDiscoveryError("index response is not UTF-8 JSON") from invalid
    return parse_index_release(
        document,
        index_url=index_url,
        approval_base_url=approval_base_url,
        etag=response.header("etag"),
    )


def _get_document(
    transport: GitHubTransport,
    url: str,
    max_bytes: int,
    label: str,
    timeout_seconds: float | None,
    *,
    required: bool,
) -> bytes | None:
    response = transport.request(
        "GET",
        url,
        max_response_bytes=max_bytes,
        timeout_seconds=timeout_seconds,
    )
    if response.status == 404 and not required:
        return None
    if response.status != 200:
        raise SnapshotReadError(f"{label} request failed with HTTP {response.status}")
    return response.body


def read_snapshot_documents(
    transport: GitHubTransport,
    index: IndexRelease,
    *,
    timeout_seconds: float | None = None,
) -> SnapshotDocuments:
    """Read all four documents from the one snapshot the index named.

    The manifest pair is required.  The heartbeat pair is diagnostic liveness
    and a 404 is tolerated, matching the pinned client; every URL still comes
    from the same fixed snapshot base, never from the index body.
    """
    base = index.snapshot_base_url
    manifest = _get_document(
        transport,
        f"{base}/ytdlp-approved.json",
        MAX_MANIFEST_BYTES,
        "manifest",
        timeout_seconds,
        required=True,
    )
    manifest_signature = _get_document(
        transport,
        f"{base}/ytdlp-approved.json.sig",
        MAX_SIGNATURE_BYTES,
        "manifest signature",
        timeout_seconds,
        required=True,
    )
    heartbeat = _get_document(
        transport,
        f"{base}/ytdlp-heartbeat.json",
        MAX_HEARTBEAT_BYTES,
        "heartbeat",
        timeout_seconds,
        required=False,
    )
    heartbeat_signature = _get_document(
        transport,
        f"{base}/ytdlp-heartbeat.json.sig",
        MAX_SIGNATURE_BYTES,
        "heartbeat signature",
        timeout_seconds,
        required=False,
    )
    assert manifest is not None and manifest_signature is not None
    return SnapshotDocuments(
        snapshot_tag=index.snapshot_tag,
        snapshot_base_url=base,
        manifest=manifest,
        manifest_signature=manifest_signature,
        heartbeat=heartbeat,
        heartbeat_signature=heartbeat_signature,
    )


# --- Release and asset parsing ----------------------------------------------


@dataclass(frozen=True)
class Release:
    id: int
    tag_name: str
    target_commitish: str
    draft: bool
    prerelease: bool
    make_latest: str | None
    body: str | None
    immutable: bool | None = None
    immutable_releases: bool | None = None
    is_immutable: bool | None = None

    def immutability_signal(self) -> bool | None:
        """True/False when the API reports sealing, None when it says nothing."""
        signals = (self.immutable, self.immutable_releases, self.is_immutable)
        if any(value is False for value in signals):
            return False
        if any(value is True for value in signals):
            return True
        return None


@dataclass(frozen=True)
class ReleaseAsset:
    id: int
    name: str
    size: int
    state: str
    digest: str | None


def _positive_int(document: dict, key: str) -> int:
    value = document.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValidationError(f"release {key} is not a positive integer")
    return value


def parse_release(document: object) -> Release:
    if not isinstance(document, dict):
        raise ValidationError("release response is not a JSON object")
    release_id = _positive_int(document, "id")
    tag_name = document.get("tag_name")
    target_commitish = document.get("target_commitish")
    draft = document.get("draft")
    prerelease = document.get("prerelease")
    make_latest = document.get("make_latest")
    body = document.get("body")
    if not isinstance(tag_name, str):
        raise ValidationError("release tag_name is not a string")
    if not isinstance(target_commitish, str):
        raise ValidationError("release target_commitish is not a string")
    if not isinstance(draft, bool):
        raise ValidationError("release draft is not a boolean")
    if not isinstance(prerelease, bool):
        raise ValidationError("release prerelease is not a boolean")
    if make_latest is not None and not isinstance(make_latest, str):
        raise ValidationError("release make_latest is not a string or null")
    if body is not None and not isinstance(body, str):
        raise ValidationError("release body is not a string or null")
    immutable_fields = []
    for key in ("immutable", "immutable_releases", "is_immutable"):
        value = document.get(key)
        if value is not None and not isinstance(value, bool):
            raise ValidationError(f"release {key} is not a boolean or null")
        immutable_fields.append(value)
    return Release(
        id=release_id,
        tag_name=tag_name,
        target_commitish=target_commitish,
        draft=draft,
        prerelease=prerelease,
        make_latest=make_latest,
        body=body,
        immutable=immutable_fields[0],
        immutable_releases=immutable_fields[1],
        is_immutable=immutable_fields[2],
    )


def parse_asset(document: object) -> ReleaseAsset:
    if not isinstance(document, dict):
        raise ValidationError("asset response is not a JSON object")
    asset_id = _positive_int(document, "id")
    name = document.get("name")
    size = document.get("size")
    state = document.get("state")
    digest = document.get("digest")
    if not isinstance(name, str):
        raise ValidationError("asset name is not a string")
    if isinstance(size, bool) or not isinstance(size, int) or size < 0:
        raise ValidationError("asset size is not a non-negative integer")
    if not isinstance(state, str):
        raise ValidationError("asset state is not a string")
    if digest is not None and not isinstance(digest, str):
        raise ValidationError("asset digest is not a string or null")
    return ReleaseAsset(id=asset_id, name=name, size=size, state=state, digest=digest)


# --- Publisher --------------------------------------------------------------


def validate_run_ids(run_id: int, run_attempt: int) -> None:
    for label, value in (("run-id", run_id), ("run-attempt", run_attempt)):
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise ValidationError(f"{label} must be a positive integer")


def validate_commit_sha(commit_sha: str) -> None:
    if not isinstance(commit_sha, str) or not SHA_RE.fullmatch(commit_sha):
        raise ValidationError("commit-sha must be a 40- or 64-character lowercase hex SHA")


def snapshot_tag(run_id: int, run_attempt: int) -> str:
    return f"{INDEX_TAG}-{run_id}-{run_attempt}"


def index_body(snapshot_tag: str) -> str:
    """The exact schema-1 index record bytes the pinned client parses."""
    return json.dumps(
        {"schema": SCHEMA, "snapshotTag": snapshot_tag}, separators=(",", ":")
    )


def _json_bytes(payload: object) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


@dataclass(frozen=True)
class PublishPlan:
    snapshot_tag: str
    commit_sha: str
    files: SnapshotFiles
    steps: tuple[str, ...]

    def summary(self) -> str:
        lines = [f"snapshot: {self.snapshot_tag}", f"commit: {self.commit_sha}"]
        for name, data in self.files.by_name().items():
            digest = self.files.sha256(name)
            lines.append(f"asset: {name} ({len(data)} bytes, sha256:{digest})")
        lines.extend(self.steps)
        return "\n".join(lines)


@dataclass(frozen=True)
class PublishResult:
    snapshot_tag: str
    commit_sha: str
    release_id: int
    created: bool
    published: bool
    reused: bool
    index_release_id: int
    index_created: bool
    index_tag: str = INDEX_TAG


class SnapshotPublisher:
    """The immutable snapshot state machine on top of a GitHub transport."""

    def __init__(
        self,
        transport: GitHubTransport | None,
        *,
        index_url: str = INDEX_URL,
        approval_base_url: str = APPROVAL_BASE_URL,
        timeout_seconds: float | None = None,
    ):
        self._transport = transport
        self._index_url = index_url
        self._approval_base_url = approval_base_url
        self._timeout = timeout_seconds

    def _request(self, method: str, url: str, **kwargs) -> TransportResponse:
        if self._transport is None:
            raise ApprovalReleaseError("no transport configured")
        return self._transport.request(
            method, url, timeout_seconds=self._timeout, **kwargs
        )

    def plan(
        self,
        files: SnapshotFiles,
        run_id: int,
        run_attempt: int,
        commit_sha: str,
    ) -> PublishPlan:
        validate_run_ids(run_id, run_attempt)
        validate_commit_sha(commit_sha)
        validate_snapshot_files(files)
        tag = snapshot_tag(run_id, run_attempt)
        steps = [
            f"create draft prerelease {tag} targeting {commit_sha} "
            "(prerelease=true, make_latest=false)",
        ]
        steps.extend(
            f"upload {name} ({len(data)} bytes, sha256:{files.sha256(name)})"
            for name, data in files.by_name().items()
        )
        steps.extend(
            [
                f"publish {tag} (draft=false, prerelease=true, make_latest=false)",
                "verify the published snapshot's four assets and target commit",
                f"advance the {INDEX_TAG} index body to {index_body(tag)}",
            ]
        )
        return PublishPlan(
            snapshot_tag=tag,
            commit_sha=commit_sha,
            files=files,
            steps=tuple(steps),
        )

    def publish(
        self,
        files: SnapshotFiles,
        run_id: int,
        run_attempt: int,
        commit_sha: str,
        *,
        expected_index_tag: str | None = None,
    ) -> PublishResult:
        plan = self.plan(files, run_id, run_attempt, commit_sha)
        tag = plan.snapshot_tag
        release = self._get_release_by_tag(tag)
        created = False
        published = False
        reused = False
        if release is None:
            release = self._create_draft(tag, commit_sha)
            created = True
            for name in ASSET_NAMES:
                self._upload_asset(release.id, name, files.by_name()[name])
            self._publish(release.id)
            published = True
            release = self._get_release_by_id(release.id)
        elif release.draft:
            self._complete_draft(release, files, commit_sha)
            self._publish(release.id)
            published = True
            release = self._get_release_by_id(release.id)
        else:
            reused = True
        self._verify_published(release, files, commit_sha)
        index_release_id, index_created = self.advance_index(
            tag, commit_sha, expected_index_tag=expected_index_tag
        )
        return PublishResult(
            snapshot_tag=tag,
            commit_sha=commit_sha,
            release_id=release.id,
            created=created,
            published=published,
            reused=reused,
            index_release_id=index_release_id,
            index_created=index_created,
        )

    # -- snapshot release lifecycle -----------------------------------------

    def _get_release_by_tag(self, tag: str) -> Release | None:
        response = self._request(
            "GET",
            f"{REPO_API_PREFIX}/releases/tags/{tag}",
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if response.status == 404:
            return None
        if response.status != 200:
            raise ApprovalReleaseError(
                f"snapshot release lookup failed with HTTP {response.status}"
            )
        try:
            document = json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as invalid:
            raise ApprovalReleaseError("snapshot release response is not UTF-8 JSON") from invalid
        return parse_release(document)

    def _get_release_by_id(self, release_id: int) -> Release:
        response = self._request(
            "GET",
            f"{REPO_API_PREFIX}/releases/{release_id}",
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if response.status != 200:
            raise ApprovalReleaseError(
                f"snapshot release verification failed with HTTP {response.status}"
            )
        try:
            document = json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as invalid:
            raise ApprovalReleaseError("snapshot release response is not UTF-8 JSON") from invalid
        return parse_release(document)

    def _create_draft(self, tag: str, commit_sha: str) -> Release:
        payload = {
            "tag_name": tag,
            "target_commitish": commit_sha,
            "name": f"Engine approvals {tag}",
            "body": f"Immutable engine approval snapshot {tag} for {commit_sha}.",
            "draft": True,
            "prerelease": True,
            "make_latest": "false",
        }
        response = self._request(
            "POST",
            f"{REPO_API_PREFIX}/releases",
            headers={"Content-Type": "application/json"},
            body=_json_bytes(payload),
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if response.status not in (200, 201):
            raise ApprovalReleaseError(
                f"snapshot draft creation failed with HTTP {response.status}"
            )
        release = parse_release(response.json())
        if (
            release.tag_name != tag
            or release.target_commitish != commit_sha
            or not release.draft
            or not release.prerelease
            or release.make_latest == "true"
        ):
            raise SnapshotStateError("created snapshot release does not match the request")
        return release

    def _publish(self, release_id: int) -> None:
        payload = {"draft": False, "prerelease": True, "make_latest": "false"}
        response = self._request(
            "PATCH",
            f"{REPO_API_PREFIX}/releases/{release_id}",
            headers={"Content-Type": "application/json"},
            body=_json_bytes(payload),
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if response.status != 200:
            raise ApprovalReleaseError(f"snapshot publish failed with HTTP {response.status}")
        release = parse_release(response.json())
        if release.draft or not release.prerelease or release.make_latest == "true":
            raise SnapshotStateError("published snapshot release does not match the request")

    def _upload_asset(self, release_id: int, name: str, content: bytes) -> None:
        query = urllib.parse.urlencode({"name": name})
        url = f"{UPLOADS_BASE}/repos/{REPO_OWNER}/{REPO_NAME}/releases/{release_id}/assets?{query}"
        response = self._request(
            "POST",
            url,
            headers={"Content-Type": "application/octet-stream"},
            body=content,
            max_response_bytes=MAX_ASSET_JSON_BYTES,
        )
        if response.status not in (200, 201):
            raise ApprovalReleaseError(
                f"upload of {name} failed with HTTP {response.status}"
            )
        asset = parse_asset(response.json())
        if asset.name != name or asset.size != len(content):
            raise SnapshotStateError(f"uploaded asset {name} does not match the local file")

    # -- asset verification --------------------------------------------------

    def _list_assets(self, release_id: int) -> list[ReleaseAsset]:
        response = self._request(
            "GET",
            f"{REPO_API_PREFIX}/releases/{release_id}/assets?per_page=100",
            max_response_bytes=MAX_ASSET_LIST_BYTES,
        )
        if response.status != 200:
            raise ApprovalReleaseError(
                f"asset listing failed with HTTP {response.status}"
            )
        document = response.json()
        if not isinstance(document, list):
            raise SnapshotStateError("asset listing is not a JSON array")
        if 'rel="next"' in (response.header("link") or ""):
            raise SnapshotStateError("snapshot has more assets than one page")
        return [parse_asset(item) for item in document]

    def _download_asset(self, asset_id: int, max_bytes: int) -> bytes:
        response = self._request(
            "GET",
            f"{REPO_API_PREFIX}/releases/assets/{asset_id}",
            headers={"Accept": "application/octet-stream"},
            max_response_bytes=max_bytes,
        )
        if response.status != 200:
            raise SnapshotStateError(
                f"asset download failed with HTTP {response.status}"
            )
        return response.body

    def _verify_assets(
        self,
        release_id: int,
        files: SnapshotFiles,
        *,
        require_all: bool,
    ) -> list[str]:
        """Verify present assets by digest and return the missing fixed names."""
        assets = self._list_assets(release_id)
        by_name: dict[str, ReleaseAsset] = {}
        for asset in assets:
            if asset.name in by_name:
                raise SnapshotStateError(f"duplicate asset {asset.name!r}")
            by_name[asset.name] = asset
        expected = files.by_name()
        unexpected = sorted(set(by_name) - set(expected))
        if unexpected:
            raise SnapshotStateError(f"unexpected snapshot assets: {unexpected}")
        missing = [name for name in ASSET_NAMES if name not in by_name]
        if require_all and missing:
            raise SnapshotStateError(f"missing snapshot assets: {missing}")
        for name in ASSET_NAMES:
            if name not in by_name:
                continue
            asset = by_name[name]
            content = expected[name]
            if asset.state != "uploaded":
                raise SnapshotStateError(f"asset {name} is {asset.state!r}, not uploaded")
            if asset.size != len(content):
                raise SnapshotStateError(
                    f"asset {name} is {asset.size} bytes, local file is {len(content)}"
                )
            if asset.digest is not None:
                expected_digest = "sha256:" + hashlib.sha256(content).hexdigest()
                if asset.digest != expected_digest:
                    raise SnapshotStateError(f"asset {name} digest mismatch")
            try:
                downloaded = self._download_asset(asset.id, len(content))
            except ResponseTooLargeError as invalid:
                raise SnapshotStateError(f"asset {name} is larger than the local file") from invalid
            if downloaded != content:
                raise SnapshotStateError(f"asset {name} bytes differ from the local file")
        return missing

    def _complete_draft(
        self,
        release: Release,
        files: SnapshotFiles,
        commit_sha: str,
    ) -> None:
        if release.target_commitish != commit_sha:
            raise SnapshotStateError(
                "existing draft targets a different commit; request a new attempt"
            )
        if not release.prerelease:
            raise SnapshotStateError("existing draft is not a prerelease")
        if release.make_latest == "true":
            raise SnapshotStateError("existing draft would become the latest release")
        missing = self._verify_assets(release.id, files, require_all=False)
        for name in missing:
            self._upload_asset(release.id, name, files.by_name()[name])

    def _verify_published(
        self,
        release: Release,
        files: SnapshotFiles,
        commit_sha: str,
    ) -> None:
        if release.draft:
            raise SnapshotStateError("snapshot is still a draft")
        if not release.prerelease:
            raise SnapshotStateError("snapshot is not a prerelease")
        if release.target_commitish != commit_sha:
            raise SnapshotStateError("snapshot targets a different commit")
        if release.make_latest == "true":
            raise SnapshotStateError("snapshot became the latest release")
        if release.immutability_signal() is not True:
            raise SnapshotNotImmutableError(
                "the API did not report the newly published snapshot as immutable"
            )
        self._verify_assets(release.id, files, require_all=True)

    # -- stable index advance ------------------------------------------------

    def advance_index(
        self,
        snapshot_tag: str,
        commit_sha: str,
        *,
        expected_index_tag: str | None = None,
    ) -> tuple[int, bool]:
        """Advance the stable index body, or bootstrap it when missing.

        The expected-tag check is a precondition, not a linearizable CAS: the
        caller must serialize publishing.  The old pointer stays intact when
        the check fails or the PATCH fails.
        """
        response = self._request(
            "GET",
            self._index_url,
            max_response_bytes=MAX_INDEX_JSON_BYTES,
        )
        if response.status == 404:
            if expected_index_tag is not None:
                raise IndexChangedError(
                    f"index is missing, expected {expected_index_tag!r}"
                )
            return self._create_index(snapshot_tag, commit_sha)
        if response.status != 200:
            raise IndexAdvanceError(f"index lookup failed with HTTP {response.status}")
        try:
            document = json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as invalid:
            raise IndexAdvanceError("index response is not UTF-8 JSON") from invalid
        index = parse_index_release(
            document,
            index_url=self._index_url,
            approval_base_url=self._approval_base_url,
            etag=response.header("etag"),
        )
        if index.release_id is None:
            raise IndexAdvanceError("index release has no id")
        if expected_index_tag is not None and index.snapshot_tag != expected_index_tag:
            raise IndexChangedError(
                f"index points at {index.snapshot_tag!r}, expected {expected_index_tag!r}"
            )
        payload = {
            "body": index_body(snapshot_tag),
            "prerelease": True,
            "make_latest": "false",
        }
        patch = self._request(
            "PATCH",
            f"{REPO_API_PREFIX}/releases/{index.release_id}",
            headers={"Content-Type": "application/json"},
            body=_json_bytes(payload),
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if patch.status != 200:
            raise IndexAdvanceError(f"index update failed with HTTP {patch.status}")
        verify = self._request(
            "GET",
            f"{REPO_API_PREFIX}/releases/{index.release_id}",
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if verify.status != 200:
            raise IndexAdvanceError(
                f"index verification failed with HTTP {verify.status}"
            )
        verified = parse_release(verify.json())
        if (
            verified.id != index.release_id
            or verified.draft
            or not verified.prerelease
            or verified.make_latest == "true"
        ):
            raise IndexAdvanceError("index verification returned an unexpected release")
        if verified.body is None:
            raise IndexAdvanceError("index verification returned no body")
        try:
            visible_tag = parse_index_body(verified.body)
        except IndexDiscoveryError as invalid:
            raise IndexAdvanceError("index body is not a schema-1 record") from invalid
        if visible_tag != snapshot_tag:
            raise IndexAdvanceError(
                f"index still points at {visible_tag!r}, expected {snapshot_tag!r}"
            )
        return index.release_id, False

    def _create_index(self, snapshot_tag: str, commit_sha: str) -> tuple[int, bool]:
        payload = {
            "tag_name": INDEX_TAG,
            "target_commitish": commit_sha,
            "name": "Engine approvals",
            "body": index_body(snapshot_tag),
            "draft": False,
            "prerelease": True,
            "make_latest": "false",
        }
        response = self._request(
            "POST",
            f"{REPO_API_PREFIX}/releases",
            headers={"Content-Type": "application/json"},
            body=_json_bytes(payload),
            max_response_bytes=MAX_RELEASE_JSON_BYTES,
        )
        if response.status not in (200, 201):
            raise IndexAdvanceError(
                f"index bootstrap failed with HTTP {response.status}"
            )
        release = parse_release(response.json())
        if (
            release.tag_name != INDEX_TAG
            or release.target_commitish != commit_sha
            or release.draft
            or not release.prerelease
            or release.make_latest == "true"
            or release.body is None
        ):
            raise IndexAdvanceError("created index release does not match the request")
        try:
            visible_tag = parse_index_body(release.body)
        except IndexDiscoveryError as invalid:
            raise IndexAdvanceError("created index body is not a schema-1 record") from invalid
        if visible_tag != snapshot_tag:
            raise IndexAdvanceError("created index body names the wrong snapshot")
        return release.id, True


# --- CLI --------------------------------------------------------------------


def _load_files_from_args(args: argparse.Namespace) -> SnapshotFiles:
    if args.snapshot_dir is not None:
        return SnapshotFiles.from_dir(args.snapshot_dir)
    paths = (
        args.manifest,
        args.manifest_signature,
        args.heartbeat,
        args.heartbeat_signature,
    )
    if any(path is None for path in paths):
        raise ValidationError(
            "provide --snapshot-dir or all four document paths"
        )
    return SnapshotFiles.from_paths(
        manifest=args.manifest,
        manifest_signature=args.manifest_signature,
        heartbeat=args.heartbeat,
        heartbeat_signature=args.heartbeat_signature,
    )


def _default_transport_factory(*, token: str | None) -> GitHubTransport:
    return HttpsGitHubTransport(token=token)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="approval_release.py",
        description="Publish immutable engine-approval snapshots and read the index.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    publish = sub.add_parser("publish", help="publish one immutable snapshot")
    publish.add_argument("--run-id", type=int, required=True)
    publish.add_argument("--run-attempt", type=int, required=True)
    publish.add_argument("--commit-sha", required=True)
    publish.add_argument("--snapshot-dir", type=Path)
    publish.add_argument("--manifest", type=Path)
    publish.add_argument("--manifest-signature", type=Path)
    publish.add_argument("--heartbeat", type=Path)
    publish.add_argument("--heartbeat-signature", type=Path)
    publish.add_argument(
        "--dry-run",
        action="store_true",
        help="validate and print the plan without reading GITHUB_TOKEN or the network",
    )
    publish.add_argument(
        "--expected-index-tag",
        help="fail before the index PATCH when the index points elsewhere",
    )
    publish.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)

    resolve = sub.add_parser("resolve", help="read the stable index (no secret)")
    resolve.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    return parser


def main(
    argv: list[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
    transport_factory=None,
    stdout=None,
    stderr=None,
) -> int:
    args = build_parser().parse_args(argv)
    environment = os.environ if env is None else env
    out = sys.stdout if stdout is None else stdout
    err = sys.stderr if stderr is None else stderr
    factory = transport_factory or _default_transport_factory
    try:
        if args.timeout <= 0:
            raise ValidationError("--timeout must be positive")
        if args.command == "resolve":
            index = resolve_index(
                factory(token=None), timeout_seconds=args.timeout
            )
            if index is None:
                payload = {"status": "missing", "index_tag": INDEX_TAG}
            else:
                payload = {
                    "status": "resolved",
                    "index_tag": index.tag_name,
                    "index_release_id": index.release_id,
                    "snapshot_tag": index.snapshot_tag,
                    "snapshot_base_url": index.snapshot_base_url,
                    "documents": {
                        name: f"{index.snapshot_base_url}/{name}"
                        for name in ASSET_NAMES
                    },
                }
            print(json.dumps(payload, sort_keys=True), file=out)
            return 0

        files = _load_files_from_args(args)
        if args.dry_run:
            plan = SnapshotPublisher(None, timeout_seconds=args.timeout).plan(
                files, args.run_id, args.run_attempt, args.commit_sha
            )
            print(plan.summary(), file=out)
            print(
                json.dumps(
                    {"status": "dry-run", "snapshot_tag": plan.snapshot_tag},
                    sort_keys=True,
                ),
                file=out,
            )
            return 0

        token = environment.get("GITHUB_TOKEN", "").strip()
        if not token:
            raise MissingTokenError("GITHUB_TOKEN is required for a real publish")
        publisher = SnapshotPublisher(
            factory(token=token), timeout_seconds=args.timeout
        )
        result = publisher.publish(
            files,
            args.run_id,
            args.run_attempt,
            args.commit_sha,
            expected_index_tag=args.expected_index_tag,
        )
        print(
            json.dumps(
                {
                    "status": "published",
                    "snapshot_tag": result.snapshot_tag,
                    "commit_sha": result.commit_sha,
                    "release_id": result.release_id,
                    "created": result.created,
                    "published": result.published,
                    "reused": result.reused,
                    "index_release_id": result.index_release_id,
                    "index_created": result.index_created,
                    "index_tag": result.index_tag,
                },
                sort_keys=True,
            ),
            file=out,
        )
        return 0
    except (ApprovalReleaseError, TransportError, IndexDiscoveryError, OSError) as error:
        print(f"approval_release: {error}", file=err)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
