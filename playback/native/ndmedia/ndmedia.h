/* SPDX-License-Identifier: Unlicense */
/*
 * ndmedia.h — the flat C ABI of :playback:native's ndmedia library (11 ndmedia
 * interface). Kotlin binds these symbols with hand-written FFM downcalls in
 * NdmediaLibrary/NdOutput. All functions return immediately; nothing calls into
 * the JVM from the audio callback.
 */
#ifndef NDMEDIA_H
#define NDMEDIA_H

#include <stdint.h>

#ifdef _WIN32
#define ND_API __declspec(dllexport)
#else
#define ND_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct nd_out nd_out;

typedef struct {
    /* NULL = default back-end order, "null" = the test back-end */
    const char *backend;
    /* 0 = the device's native rate */
    int sample_rate;
    /* 2 */
    int channels;
    /* ring capacity in milliseconds, 0 = 200 */
    int ring_ms;
} nd_out_config;

/* miniaudio device (shared mode, f32) + SPSC ring; 0 = ok, < 0 = ND_E_* */
ND_API int nd_out_open(const nd_out_config *cfg, nd_out **out);
/* frames accepted, never blocks; < 0 on error */
ND_API int nd_out_write(nd_out *o, const float *interleaved, int frames);
ND_API int nd_out_free_frames(nd_out *o);
/* media frames consumed by the device callback; silence on underrun does not
   count, so a stalled ring never advances the clock */
ND_API int64_t nd_out_frames_played(nd_out *o);
/* frames the device asked for but the ring could not supply */
ND_API int64_t nd_out_underrun_frames(nd_out *o);
/* 5-ms ramps on start and stop */
ND_API int nd_out_start(nd_out *o);
ND_API int nd_out_stop(nd_out *o);
ND_API void nd_out_close(nd_out *o);
/* drop queued frames (seek); returns frames dropped */
ND_API int nd_out_clear(nd_out *o);
/* {"rate":…,"channels":…,"backend":"…","device":"…","period":…} */
ND_API int nd_out_describe(nd_out *o, char *json, int cap);

#define ND_E_ARG (-1)
#define ND_E_STATE (-2)
#define ND_E_BACKEND (-3)
#define ND_E_DEVICE (-4)

#ifdef __cplusplus
}
#endif
#endif /* NDMEDIA_H */
