# SPDX-License-Identifier: Unlicense
"""Wire codes only leave Python. Extractor messages can contain signed URLs."""

from enum import Enum

from yt_dlp.networking.exceptions import HTTPError, TransportError
from yt_dlp.utils import GeoRestrictedError


class Code(str, Enum):
    RATE_LIMITED = 'RATE_LIMITED'
    AGE_RESTRICTED = 'AGE_RESTRICTED'
    MEMBERS_ONLY = 'MEMBERS_ONLY'
    PRIVATE = 'PRIVATE'
    REGION_BLOCKED = 'REGION_BLOCKED'
    UPCOMING = 'UPCOMING'
    LIVE = 'LIVE'
    KIDS_ONLY = 'KIDS_ONLY'
    UNAVAILABLE = 'UNAVAILABLE'
    EXTRACTION = 'EXTRACTION'
    NETWORK = 'NETWORK'
    TIMEOUT = 'TIMEOUT'
    CANCELLED = 'CANCELLED'
    CURSOR_EXPIRED = 'CURSOR_EXPIRED'
    ENGINE_UNAVAILABLE = 'ENGINE_UNAVAILABLE'


class ShimError(Exception):
    def __init__(self, code):
        self.code = Code(code).value
        super().__init__(self.code)


def _causes(error):
    """yt-dlp wraps HTTP/extractor failures in DownloadError.exc_info."""
    seen = set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        yield error
        exc_info = getattr(error, 'exc_info', None)
        error = (exc_info[1] if exc_info else None) or getattr(error, 'cause', None) or error.__cause__


def classify(info=None, warnings=(), error=None):
    info = info or {}
    causes = list(_causes(error))
    text = '\n'.join([str(e) for e in causes] + list(warnings)).lower().replace('’', "'")
    # Rate limits must precede generic unavailable/private messages in the same player reason.
    if any(isinstance(e, HTTPError) and e.status == 429 for e in causes) or any(s in text for s in (
        "confirm you're not a bot", 'try again later', 'rate-limited', 'rate limited', 'captcha challenge',
    )):
        return Code.RATE_LIMITED
    if (info.get('age_limit') or 0) >= 18 or 'confirm your age' in text or 'age-restricted' in text:
        return Code.AGE_RESTRICTED
    if info.get('availability') in ('subscriber_only', 'premium_only') or any(s in text for s in (
        'members-only', 'members only', 'join this channel', 'paid content', 'requires payment',
    )):
        return Code.MEMBERS_ONLY
    if info.get('availability') == 'private' or 'private video' in text:
        return Code.PRIVATE
    if any(isinstance(e, GeoRestrictedError) for e in causes) or any(s in text for s in (
        'not made this video available in your country', 'not available in your country', 'geo-restricted',
    )):
        return Code.REGION_BLOCKED
    if info.get('live_status') == 'is_upcoming':
        return Code.UPCOMING
    if info.get('live_status') in ('is_live', 'post_live'):
        return Code.LIVE
    if any(s in text for s in ('made for kids', 'made-for-kids')):
        return Code.KIDS_ONLY
    if any(s in text for s in ('video unavailable', 'video has been removed')) or ('account' in text and 'terminated' in text) or any(
        isinstance(e, HTTPError) and e.status == 404 for e in causes
    ):
        return Code.UNAVAILABLE
    if any(isinstance(e, (TransportError, TimeoutError)) or isinstance(e, HTTPError) and e.status >= 500 for e in causes):
        return Code.NETWORK
    return Code.EXTRACTION
