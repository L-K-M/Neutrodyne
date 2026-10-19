# SPDX-License-Identifier: Unlicense
"""No-network compatibility probe of the actual bundled package and selected transport."""

import inspect
import json
import platform
import ssl


def probe(ydl=None):
    missing = []
    try:
        from yt_dlp import YoutubeDL
        from yt_dlp.networking import common
        from yt_dlp.networking.common import RequestHandler, Request, Response
        from yt_dlp.extractor.youtube import YoutubeIE, YoutubeTabIE, YoutubeSearchIE
        from yt_dlp_ejs import version
        from . import okhttp_rh
        for owner, name in ((YoutubeDL, 'extract_info'), (RequestHandler, '_get_cookiejar'),
                            (RequestHandler, '_check_extensions'), (common, '_REQUEST_HANDLERS'),
                            (common, 'register_rh'), (common, 'register_preference')):
            if not hasattr(owner, name) or name != '_REQUEST_HANDLERS' and not callable(getattr(owner, name)):
                missing.append(name)
        if 'process' not in inspect.signature(YoutubeDL.extract_info).parameters:
            missing.append('extract_info.process')
        handlers = getattr(common, '_REQUEST_HANDLERS', {})
        if len(handlers) != 1 or next(iter(handlers), None) not in ('NeutrodyneOkHttp', 'Replay', 'Recording'):
            missing.append('exclusive_handler')
        if 'NeutrodyneOkHttp' in handlers and not callable(okhttp_rh._execute):
            missing.append('PyHttp.execute')
        if ydl is not None:
            keys = set(ydl._request_director.handlers)
            if keys != set(handlers):
                missing.append('worker.exclusive_handler')
        if not version or not all((YoutubeIE, YoutubeTabIE, YoutubeSearchIE, Request, Response)):
            missing.append('extractor_api')
    except Exception:
        missing.append('yt_dlp_api')
    return {'ok': not missing, 'missing': missing}


def selftest():
    # The S7 host has no configured engine/HTTP handler; bridge.selftest runs the full probe.
    return json.dumps({
        "ok": True,
        "python": platform.python_version(),
        "openssl": ssl.OPENSSL_VERSION,
    })
