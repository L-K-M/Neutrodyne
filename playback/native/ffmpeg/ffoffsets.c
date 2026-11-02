// SPDX-License-Identifier: Unlicense
//
// ffoffsets.c — the layout probe of the minimal FFmpeg build (11 FFM bindings,
// docs/design/11-desktop.md).
//
// Compiled and run by `build.sh` against the very headers of the build it just
// configured, and printed as `ffmpeg-layout.json` next to the libraries. The
// hand-written FFM bindings read the struct offsets, sizes and constants from
// that file instead of hard-coding them, so a same-major FFmpeg rebuild whose
// layout shifted still binds, and a different major is refused by
// `FfmpegLibrary` before any struct is touched.
//
// It prints one JSON object on stdout:
//   {
//     "ffmpegVersion": "9.0.2",
//     "libraries": { "avcodec": 63, "avformat": 63, "avutil": 61, "swresample": 7 },
//     "structs":   { "<Name>": { "size": N, "fields": { "<field>": offset } } },
//     "constants": { "<NAME>": value }
//   }
//
// Only the fields and constants the bindings actually use are reported; adding
// one means adding it here, in the JSON and in FfmpegLayout.

#include <errno.h>
#include <stdio.h>
#include <stddef.h>

#include "libavcodec/avcodec.h"
#include "libavcodec/codec_id.h"
#include "libavcodec/packet.h"
#include "libavformat/avformat.h"
#include "libavformat/avio.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/log.h"
#include "libavutil/samplefmt.h"
#include "libavutil/version.h"
#include "libswresample/swresample.h"

/** One struct field: printed as `"name": offset`. The last field of a struct
 *  uses FIELD_LAST so the JSON object stays valid (no trailing comma). */
#define FIELD(s, f) printf("      \"%s\": %zu,\n", #f, offsetof(s, f))
#define FIELD_LAST(s, f) printf("      \"%s\": %zu\n", #f, offsetof(s, f))

static void struct_begin(const char *name, size_t size)
{
    printf("    \"%s\": {\n      \"size\": %zu,\n      \"fields\": {\n", name, size);
}

static void struct_end(void) { printf("      }\n    },\n"); }

int main(int argc, char **argv)
{
    /* The point release comes from the lock, handed over as the first argument
     * by build.sh; without one the built library reports its own. A -D would
     * need quoting that MSVC's and gcc's command lines parse differently. */
    const char *version = argc > 1 ? argv[1] : av_version_info();

    printf("{\n");
    printf("  \"ffmpegVersion\": \"%s\",\n", version);
    printf("  \"libraries\": {\n");
    printf("    \"avcodec\": %d,\n", LIBAVCODEC_VERSION_MAJOR);
    printf("    \"avformat\": %d,\n", LIBAVFORMAT_VERSION_MAJOR);
    printf("    \"avutil\": %d,\n", LIBAVUTIL_VERSION_MAJOR);
    printf("    \"swresample\": %d\n", LIBSWRESAMPLE_VERSION_MAJOR);
    printf("  },\n");

    printf("  \"structs\": {\n");

    struct_begin("AVFormatContext", sizeof(AVFormatContext));
    FIELD(AVFormatContext, pb);
    FIELD(AVFormatContext, ctx_flags);
    FIELD(AVFormatContext, nb_streams);
    FIELD(AVFormatContext, streams);
    FIELD(AVFormatContext, nb_chapters);
    FIELD(AVFormatContext, chapters);
    FIELD(AVFormatContext, duration);
    FIELD_LAST(AVFormatContext, flags);
    struct_end();

    struct_begin("AVStream", sizeof(AVStream));
    FIELD(AVStream, codecpar);
    FIELD(AVStream, time_base);
    FIELD_LAST(AVStream, discard);
    struct_end();

    struct_begin("AVCodecParameters", sizeof(AVCodecParameters));
    FIELD(AVCodecParameters, codec_type);
    FIELD(AVCodecParameters, codec_id);
    FIELD(AVCodecParameters, sample_rate);
    FIELD(AVCodecParameters, ch_layout);
    FIELD(AVCodecParameters, initial_padding);
    FIELD(AVCodecParameters, trailing_padding);
    FIELD_LAST(AVCodecParameters, seek_preroll);
    struct_end();

    struct_begin("AVPacket", sizeof(AVPacket));
    FIELD(AVPacket, stream_index);
    FIELD(AVPacket, pts);
    FIELD(AVPacket, side_data);
    FIELD_LAST(AVPacket, side_data_elems);
    struct_end();

    struct_begin("AVFrame", sizeof(AVFrame));
    FIELD(AVFrame, nb_samples);
    FIELD(AVFrame, format);
    FIELD(AVFrame, sample_rate);
    FIELD(AVFrame, ch_layout);
    FIELD(AVFrame, pts);
    FIELD_LAST(AVFrame, duration);
    struct_end();

    struct_begin("AVChapter", sizeof(AVChapter));
    FIELD(AVChapter, time_base);
    FIELD(AVChapter, start);
    FIELD(AVChapter, end);
    FIELD_LAST(AVChapter, metadata);
    struct_end();

    struct_begin("AVRational", sizeof(AVRational));
    FIELD(AVRational, num);
    FIELD_LAST(AVRational, den);
    struct_end();

    struct_begin("AVChannelLayout", sizeof(AVChannelLayout));
    FIELD(AVChannelLayout, order);
    FIELD(AVChannelLayout, nb_channels);
    FIELD_LAST(AVChannelLayout, u);
    struct_end();

    struct_begin("AVPacketSideData", sizeof(AVPacketSideData));
    FIELD(AVPacketSideData, data);
    FIELD(AVPacketSideData, size);
    FIELD_LAST(AVPacketSideData, type);
    struct_end();

    printf("    \"AVIOContext\": { \"size\": %zu }\n", sizeof(AVIOContext));
    printf("  },\n");

    printf("  \"codecIds\": {\n");
    printf("    \"AAC\": %d,\n", AV_CODEC_ID_AAC);
    printf("    \"MP3\": %d,\n", AV_CODEC_ID_MP3);
    printf("    \"OPUS\": %d,\n", AV_CODEC_ID_OPUS);
    printf("    \"VORBIS\": %d,\n", AV_CODEC_ID_VORBIS);
    printf("    \"FLAC\": %d,\n", AV_CODEC_ID_FLAC);
    printf("    \"ALAC\": %d,\n", AV_CODEC_ID_ALAC);
    printf("    \"PCM_S16LE\": %d,\n", AV_CODEC_ID_PCM_S16LE);
    printf("    \"PCM_S24LE\": %d,\n", AV_CODEC_ID_PCM_S24LE);
    printf("    \"PCM_F32LE\": %d,\n", AV_CODEC_ID_PCM_F32LE);
    printf("    \"PCM_U8\": %d\n", AV_CODEC_ID_PCM_U8);
    printf("  },\n");

    printf("  \"constants\": {\n");
    printf("    \"AVMEDIA_TYPE_AUDIO\": %d,\n", AVMEDIA_TYPE_AUDIO);
    printf("    \"AV_SAMPLE_FMT_S16\": %d,\n", AV_SAMPLE_FMT_S16);
    printf("    \"AV_SAMPLE_FMT_FLTP\": %d,\n", AV_SAMPLE_FMT_FLTP);
    printf("    \"AV_LOG_QUIET\": %d,\n", AV_LOG_QUIET);
    printf("    \"AVDISCARD_ALL\": %d,\n", AVDISCARD_ALL);
    printf("    \"AVFMT_FLAG_CUSTOM_IO\": %d,\n", AVFMT_FLAG_CUSTOM_IO);
    printf("    \"AVSEEK_SIZE\": %d,\n", AVSEEK_SIZE);
    printf("    \"AVSEEK_FORCE\": %d,\n", AVSEEK_FORCE);
    printf("    \"SEEK_SET\": %d,\n", SEEK_SET);
    printf("    \"SEEK_CUR\": %d,\n", SEEK_CUR);
    printf("    \"SEEK_END\": %d,\n", SEEK_END);
    printf("    \"AV_ERROR_MAX_STRING_SIZE\": %d,\n", AV_ERROR_MAX_STRING_SIZE);
    printf("    \"AV_CHANNEL_LAYOUT_STEREO_MASK\": %llu,\n",
           (unsigned long long)AV_CH_LAYOUT_STEREO);
    printf("    \"AV_PKT_DATA_SKIP_SAMPLES\": %d,\n", AV_PKT_DATA_SKIP_SAMPLES);
    /* Error codes are negative Mkmpeg error numbers; the bindings compare them
     * against the same macro values instead of magic integers. */
    printf("    \"AVERROR_EAGAIN\": %d,\n", AVERROR(EAGAIN));
    printf("    \"AVERROR_EOF\": %d,\n", AVERROR_EOF);
    printf("    \"AVERROR_EXIT\": %d,\n", AVERROR_EXIT);
    printf("    \"AVERROR_EIO\": %d,\n", AVERROR(EIO));
    printf("    \"AVERROR_INVALIDDATA\": %d,\n", AVERROR_INVALIDDATA);
    printf("    \"AVERROR_BUFFER_TOO_SMALL\": %d,\n", AVERROR_BUFFER_TOO_SMALL);
    printf("    \"AV_TIME_BASE\": %d\n", AV_TIME_BASE);
    printf("  }\n");

    printf("}\n");
    return 0;
}
