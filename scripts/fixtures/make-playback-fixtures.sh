#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Synthetic M4 audio and M5 video-as-audio fixtures; no copyrighted media.
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

# Small synthetic H.264 + AAC muxed enclosure; no visual surface is used by playback.
"$ffmpeg_bin" -hide_banner -loglevel error -y -f lavfi \
  -i 'color=c=navy:s=160x90:r=5:d=8' -f lavfi \
  -i 'sine=frequency=660:sample_rate=44100:duration=8' \
  -map_metadata -1 -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
  -c:a aac -b:a 32k -movflags +faststart -shortest \
  "$out/h264_aac_8s.mp4"
