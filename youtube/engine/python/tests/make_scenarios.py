# SPDX-License-Identifier: Unlicense
"""Generate labelled synthetic exchanges through the real pinned YouTube extractor.

This is deliberately not a live recorder: all requests terminate in SyntheticRH.
Run manually with the pinned asset on PYTHONPATH to refresh synthetic API vectors.
"""

import io
import json
from pathlib import Path
from unittest.mock import patch

from yt_dlp.networking.common import RequestHandler, Response

from neutrodyne_ytx.bridge import Bridge
from neutrodyne_ytx.errors import ShimError
from neutrodyne_ytx.replay import request_key, scrub

FIXTURES = Path(__file__).resolve().parents[2] / 'src/test/resources/recorded'
VIDEO_ID = 'aaaaaaaaaaA'
CHANNEL_ID = 'UC' + 'a' * 21 + 'A'
CTX = {'hl': 'en', 'gl': 'US', 'ua': 'Neutrodyne synthetic test', 'ipFamily': 'V4'}


def player(scenario):
    response = {
        'playabilityStatus': {'status': 'OK'},
        'videoDetails': {'videoId': VIDEO_ID, 'title': 'Synthetic episode', 'lengthSeconds': '120',
                         'channelId': CHANNEL_ID, 'author': 'Synthetic channel', 'shortDescription': '0:00 Synthetic chapter',
                         'isLiveContent': False, 'viewCount': '1', 'thumbnail': {'thumbnails': []}},
        'streamingData': {'adaptiveFormats': [
            {'itag': 140, 'url': 'https://media.example.invalid/audio?itag=140&clen=1000&lmt=123&ip=0.0.0.0&expire=4102444800&sig=X',
             'mimeType': 'audio/mp4; codecs="mp4a.40.2"', 'bitrate': 128000, 'contentLength': '1000',
             'audioQuality': 'AUDIO_QUALITY_MEDIUM', 'audioSampleRate': '44100', 'audioChannels': 2, 'approxDurationMs': '120000'},
        ]},
        'microformat': {'playerMicroformatRenderer': {'isFamilySafe': True, 'category': 'Education', 'uploadDate': '2026-10-01'}},
    }
    reasons = {'age': 'Sign in to confirm your age', 'member': 'Join this channel to get access to members-only content',
               'region': 'The uploader has not made this video available in your country', 'private': 'Private video',
               'removed': 'This video has been removed', 'rate': "This content isn't available, try again later",
               'bot': "Sign in to confirm you're not a bot", 'unknown': 'Synthetic unknown reason'}
    if scenario in reasons:
        response.pop('streamingData')
        response['playabilityStatus'] = {'status': 'LOGIN_REQUIRED' if scenario in ('bot', 'private') else 'ERROR',
                                         'reason': reasons[scenario]}
    if scenario == 'kids':
        response.pop('streamingData')
        response['playabilityStatus'] = {'status': 'ERROR', 'reason': 'This video is made for kids'}
    if scenario == 'sabr':
        response['streamingData']['adaptiveFormats'][0].pop('url')
    return response


def channel_data(search=False):
    channel = {'channelId': CHANNEL_ID, 'title': {'simpleText': 'Synthetic channel'},
               'subscriberCountText': {'simpleText': '@synthetic'},
               'thumbnail': {'thumbnails': [{'url': 'https://example.invalid/avatar', 'width': 100, 'height': 100}]}}
    section = {'itemSectionRenderer': {'contents': [{'channelRenderer': channel}] if search else [
        {'gridRenderer': {'items': [{'gridVideoRenderer': {'videoId': VIDEO_ID,
            'title': {'simpleText': 'Synthetic episode'}, 'lengthText': {'simpleText': '2:00'},
            'navigationEndpoint': {'commandMetadata': {'webCommandMetadata': {'url': '/watch?v=' + VIDEO_ID}}}}}]}}]}}
    if search:
        return {'contents': {'twoColumnSearchResultsRenderer': {'primaryContents': {
            'sectionListRenderer': {'contents': [section]}}}}}
    return {'metadata': {'channelMetadataRenderer': {'title': 'Synthetic channel', 'externalId': CHANNEL_ID,
        'description': 'Synthetic description', 'channelUrl': 'https://www.youtube.com/channel/' + CHANNEL_ID,
        'vanityChannelUrl': 'https://www.youtube.com/@synthetic',
        'avatar': {'thumbnails': [{'url': 'https://example.invalid/avatar', 'width': 100, 'height': 100}]}}},
        'contents': {'twoColumnBrowseResultsRenderer': {'tabs': [{'tabRenderer': {'selected': True,
            'title': 'Videos', 'content': {'sectionListRenderer': {'contents': [section]}}}}]}}}


class SyntheticRH(RequestHandler):
    _SUPPORTED_URL_SCHEMES = ('http', 'https')
    records = []
    scenario = 'resolve'

    def _send(self, request):
        data = player(self.scenario)
        if '/youtubei/v1/player' in request.url:
            body = json.dumps(data)
        elif '/watch?' in request.url or '/embed/' in request.url:
            body = '<script>var ytInitialPlayerResponse = ' + json.dumps(data) + ';</script>'
            body += '<script>ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20260819.00.00"}},"INNERTUBE_API_KEY":"synthetic"});</script>'
        else:
            body = '{}'
        if self.scenario in ('lookup', 'tab', 'search'):
            body = '<script>var ytInitialData = ' + json.dumps(channel_data(self.scenario == 'search')) + ';</script>'
            body += '<script>ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20260819.00.00"}}});</script>'
        if self.scenario == 'search' and '/youtubei/' in request.url:
            body = json.dumps(channel_data(True))
        record = {'request': request_key(request), 'response': {'status': 200, 'body': body, 'headers': {}}}
        self.records.append(scrub(record))
        return Response(io.BytesIO(body.encode()), request.url, {}, 200)


def generate(output=FIXTURES, scenarios=None):
    # Tests of actual upstream parsing, never evidence of current YouTube server shapes.
    from yt_dlp.networking import common
    common._REQUEST_HANDLERS.clear()
    common._REQUEST_HANDLERS[SyntheticRH.RH_KEY] = SyntheticRH
    for scenario in scenarios or ('resolve', 'facts', 'lookup', 'tab', 'search', 'ping', 'version', 'selftest'):
        SyntheticRH.records = []
        SyntheticRH.scenario = scenario
        method = 'facts' if scenario == 'facts' else 'resolve'
        payload = {'ctx': CTX, 'videoId': VIDEO_ID}
        if scenario == 'lookup':
            method, payload = 'lookup', {'ctx': CTX, 'url': 'https://www.youtube.com/@synthetic'}
        if scenario == 'tab':
            method, payload = 'tab_open', {'ctx': CTX, 'channelId': CHANNEL_ID, 'tab': 'videos'}
        if scenario == 'search':
            method, payload = 'search_open', {'ctx': CTX, 'query': 'synthetic'}
        if scenario in ('ping', 'version', 'selftest'):
            method, payload = scenario, {'ctx': CTX}
        bridge = Bridge()
        if scenario == 'selftest':
            from neutrodyne_ytx.okhttp_rh import install
            from neutrodyne_ytx.replay import ReplayRH
            ReplayRH.configure(output / 'ping')
            install(ReplayRH)
        try:
            result = json.loads(bridge.call(method, json.dumps(payload), 1e20, 1))
        except ShimError as error:
            result = {'error': error.code}
        if scenario in ('resolve', 'facts', 'lookup', 'tab', 'search', 'ping', 'version', 'selftest') and 'error' in result:
            raise RuntimeError('Synthetic method scenario failed: ' + scenario)
        directory = output / scenario
        directory.mkdir(parents=True, exist_ok=True)
        bodies = list(dict.fromkeys(record['response']['body'] for record in SyntheticRH.records))
        for record in SyntheticRH.records:
            record['response']['bodyRef'] = bodies.index(record['response'].pop('body'))
        (directory / 'http.json').write_text(json.dumps({'provenance': 'synthetic, generated 2026-10-19; no live traffic',
                                                       'bodies': bodies, 'exchanges': SyntheticRH.records}, separators=(',', ':')) + '\n')
        (directory / 'request.json').write_text(json.dumps({'method': method, 'payload': payload}, indent=2) + '\n')
        (directory / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(scenario, len(SyntheticRH.records), str(result)[:200])


if __name__ == '__main__':
    with patch('time.time', return_value=1792368000):
        generate()
