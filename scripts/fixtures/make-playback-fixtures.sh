#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# M4 synthetic audio only. M5 adds chapters, video and longer seek fixtures.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/playback/impl/src/test/resources/media"
mkdir -p "$out"
ffmpeg_bin="${FFMPEG:-ffmpeg}"
"$ffmpeg_bin" -hide_banner -loglevel error -y -f lavfi \
  -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -c:a libmp3lame -b:a 128k -write_xing 0 -id3v2_version 0 \
  "$out/cbr_128k_2s.mp3"
"$ffmpeg_bin" -hide_banner -loglevel error -y -f lavfi \
  -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -c:a libmp3lame -q:a 5 -write_xing 1 -id3v2_version 0 \
  "$out/vbr_xing_2s.mp3"
"$ffmpeg_bin" -hide_banner -loglevel error -y -f lavfi \
  -i 'aevalsrc=if(lt(mod(t\,1)\,0.5)\,0.15*sin(2*PI*440*t)\,0):s=44100:d=3' \
  -map_metadata -1 -c:a libmp3lame -b:a 128k -write_xing 0 -id3v2_version 0 \
  "$out/silence_gaps_3s.mp3"
# ID3 CHAP plus separate Nero, QuickTime and competing MP4 chapter sources.
FFMPEG="$ffmpeg_bin" python3 "$root/scripts/fixtures/make-chapter-fixtures.py"
