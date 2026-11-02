#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# make-engine-corpus.sh — the MD0 decode corpus of :playback:engine
# (scripts/fixtures, 11 Desktop playback engine / MD0 spike).
#
# Every file is synthetic (sine waves and silence gaps, no third-party audio),
# short (2-3 s) and deterministic: `-flags +bitexact -fflags +bitexact` keep
# encoder version strings and timestamps out of the bytes, so the committed
# SHA-256s in corpus.json stay stable across ffmpeg builds. Regenerate with
#
#   FFMPEG=/path/to/ffmpeg scripts/fixtures/make-engine-corpus.sh
#
# and then refresh the manifest with
#
#   python3 scripts/fixtures/corpus_reference.py --write \
#     playback/engine/src/test/resources/media/corpus.json
#
# The reference decoder used while preparing the corpus was ffmpeg 9.0 (LGPL
# build); the manifest's expected sample counts are recomputed from the
# container structure by corpus_reference.py, never by the code under test.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/playback/engine/src/test/resources/media"
mkdir -p "$out"
ffmpeg_bin="${FFMPEG:-ffmpeg}"
common=(-hide_banner -loglevel error -y -flags +bitexact -fflags +bitexact)

# 1. CBR MP3 without a Xing header and without ID3: no trimming information at
#    all, so the decoded count is the frame count times 1152.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a libmp3lame -b:a 128k -write_xing 0 -id3v2_version 0 \
  "$out/cbr_128k_2s.mp3"

# 2. VBR MP3 with a Xing/LAME header: FFmpeg's decoder applies the LAME tag's
#    encoder delay and padding, so the manifest records the trimmed count.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a libmp3lame -q:a 5 -write_xing 1 -id3v2_version 0 \
  "$out/vbr_xing_2s.mp3"

# 3. MP3 with ID3v2.3 CHAP frames: three chapters with titles and start times,
#    the ID3 chapter form Android's MediaItem metadata also reads.
cat > "$out/id3chap_3s.chapters" <<'META'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=1000
title=Intro
[CHAPTER]
TIMEBASE=1/1000
START=1000
END=2000
title=Middle
[CHAPTER]
TIMEBASE=1/1000
START=2000
END=3000
title=Outro
META
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=3' \
  -i "$out/id3chap_3s.chapters" -map 0:a -map_metadata 1 -map_chapters 1 -ac 2 \
  -c:a libmp3lame -b:a 128k -write_xing 0 -id3v2_version 3 \
  "$out/id3chap_3s.mp3"
rm -f "$out/id3chap_3s.chapters"

# 4. M4A with an edit list: ffmpeg writes the AAC priming as an edit list, which
#    its own decoder honours (gapless trimming).
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a aac -b:a 128k "$out/m4a_lc_2s.m4a"

# 5. M4A with QuickTime text chapters in a chapter track.
cat > "$out/m4a_chapters_3s.chapters" <<'META'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=1000
title=First
[CHAPTER]
TIMEBASE=1/1000
START=1000
END=2000
title=Second
[CHAPTER]
TIMEBASE=1/1000
START=2000
END=3000
title=Third
META
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=660:sample_rate=44100:duration=3' \
  -i "$out/m4a_chapters_3s.chapters" -map 0:a -map_metadata 1 -map_chapters 1 -ac 2 \
  -c:a aac -b:a 128k "$out/m4a_chapters_3s.m4a"
rm -f "$out/m4a_chapters_3s.chapters"

# 6. Fragmented MP4 like YouTube itag 140: AAC-LC, no edit list, so the priming
#    has to be dropped by the decoder (the 1024-sample rule of 11 Demux and
#    decode), not by container metadata.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=3' \
  -map_metadata -1 -ac 2 -c:a aac -b:a 128k \
  -movflags +frag_keyframe+empty_moov+default_base_moof \
  "$out/fmp4_itag140_3s.mp4"

# 7. WebM Opus like itag 251: 48 kHz, the rate YouTube's audio-only streams use.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2' \
  -map_metadata -1 -ac 2 -c:a libopus -b:a 96k "$out/webm_opus_2s.webm"

# 8. Ogg Opus: OpusHead pre-skip is applied by the decoder.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2' \
  -map_metadata -1 -ac 2 -c:a libopus -b:a 96k "$out/ogg_opus_2s.opus"

# 9. Ogg Vorbis, stereo.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a libvorbis -q:a 4 "$out/ogg_vorbis_2s.ogg"

# 10. Ogg Vorbis, mono at 22050 Hz: the rate-change and channel-mix case.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=22050:duration=2' \
  -map_metadata -1 -ac 1 -c:a libvorbis -q:a 4 "$out/ogg_vorbis_mono_22050_2s.ogg"

# 11. FLAC: lossless, so the decoded count is the container's total.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a flac "$out/flac_2s.flac"

# 12. WAV: the reference format with an exact count.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a pcm_s16le "$out/wav_2s.wav"

# 13. MP3 with silence gaps: the SilenceSkipper input of 11 DSP chain.
"$ffmpeg_bin" "${common[@]}" -f lavfi \
  -i 'aevalsrc=if(lt(mod(t\,1)\,0.5)\,0.15*sin(2*PI*440*t)\,0):s=44100:d=3' \
  -map_metadata -1 -ac 2 -c:a libmp3lame -b:a 128k -write_xing 0 -id3v2_version 0 \
  "$out/mp3_silence_gaps_3s.mp3"

# 14./15. HE-AAC v1 (SBR over a stereo core) and v2 (Parametric Stereo over a
#    mono core) in ADTS — MD0 AC1 lists both, and ADTS also exercises the `aac`
#    demuxer no other corpus file reaches. FFmpeg's rewritten AAC encoder no
#    longer writes SBR ("Profile not supported!" for aac_he/aac_he_v2), so these
#    come from Fraunhofer's libfdk-aac `aac-enc` frontend instead, built from
#    source and named through AAC_ENC; the WAV input is the same deterministic
#    sine as every other fixture.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -map_metadata -1 -ac 2 -c:a pcm_s16le "$out/heaac.wav"
"${AAC_ENC:-aac-enc}" -r 64000 -t 5 "$out/heaac.wav" "$out/heaac_v1_2s.aac"
"${AAC_ENC:-aac-enc}" -r 32000 -t 29 "$out/heaac.wav" "$out/heaac_v2_2s.aac"
rm -f "$out/heaac.wav"

echo "make-engine-corpus: wrote $(ls "$out" | wc -l) files to $out"
