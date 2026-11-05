/* SPDX-License-Identifier: Unlicense */
/*
 * ndmedia.c — one miniaudio device behind an SPSC ring (11 ndmedia interface).
 *
 * The device callback is the only thing that advances nd_frames_played, so the
 * engine's timeline clock measures frames that really left the device. On an
 * underrun the callback emits silence and counts those frames separately; a
 * stalled ring therefore never moves position.
 *
 * Writer side: the engine thread calls nd_out_write; reader side: the audio
 * callback. The ring is lock-free SPSC: the writer owns `w`, the reader owns
 * `r`, each publishes with release and samples the other index with acquire.
 * Clear is allowed only after stop has quiesced the callback.
 *
 * Start/stop run a 5 ms linear ramp inside the callback to avoid clicks; stop
 * defers the real device stop until the ramp has drained, waiting at most a
 * bounded number of polling intervals.
 */
#include "ndmedia.h"
#include "json.h"

#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_ENCODING
#define MA_NO_DECODING
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_ENGINE
#include "miniaudio.h"

#define ND_DEFAULT_CHANNELS 2
#define ND_DEFAULT_RING_MS 200
#define ND_RAMP_MS 5
#define ND_STOP_WAIT_MS 100

/* nd_backend() sentinels sit just above ma_backend_null, the largest enum. */
#define ND_BE_DEFAULT ((ma_backend)(ma_backend_null + 1))
#define ND_BE_UNKNOWN ((ma_backend)(ma_backend_null + 2))

struct nd_out {
    ma_context *owned_ctx; /* non-NULL when a backend was pinned explicitly */
    ma_device device;
    int rate;
    int channels;
    atomic_size_t r;
    atomic_size_t w;
    size_t capacity; /* in float samples */
    float *ring;
    atomic_llong frames_played;
    atomic_llong underrun_frames;
    atomic_llong *clock_mirror; /* caller-owned, pinned until device close */
    atomic_int started;
    atomic_int stopping;
    atomic_int stopped;
    atomic_size_t ramp_pos;
    size_t ramp_frames;
    char device_name[256];
};

static void nd_publish_clock(nd_out *o) {
    if (o->clock_mirror == NULL)
        return;
    atomic_store_explicit(&o->clock_mirror[0], atomic_load_explicit(&o->frames_played, memory_order_relaxed),
                          memory_order_release);
    atomic_store_explicit(&o->clock_mirror[1], atomic_load_explicit(&o->underrun_frames, memory_order_relaxed),
                          memory_order_release);
}

ND_API int nd_out_attach_clock(nd_out *o, int64_t *counters) {
    _Static_assert(sizeof(atomic_llong) == sizeof(int64_t), "clock ABI requires 64-bit atomics");
    if (o == NULL || counters == NULL || (uintptr_t)counters % _Alignof(atomic_llong) != 0)
        return ND_E_ARG;
    if (atomic_load_explicit(&o->started, memory_order_acquire))
        return ND_E_STATE;
    o->clock_mirror = (atomic_llong *)counters;
    atomic_init(&o->clock_mirror[0], 0);
    atomic_init(&o->clock_mirror[1], 0);
    if (!atomic_is_lock_free(&o->clock_mirror[0]) || !atomic_is_lock_free(&o->clock_mirror[1])) {
        o->clock_mirror = NULL;
        return ND_E_ARG;
    }
    nd_publish_clock(o);
    return 0;
}

static ma_backend nd_backend(const char *name) {
    if (name == NULL || name[0] == '\0')
        return ND_BE_DEFAULT;
    if (strcmp(name, "null") == 0)
        return ma_backend_null;
    if (strcmp(name, "wasapi") == 0)
        return ma_backend_wasapi;
    if (strcmp(name, "coreaudio") == 0)
        return ma_backend_coreaudio;
    if (strcmp(name, "pulseaudio") == 0)
        return ma_backend_pulseaudio;
    if (strcmp(name, "alsa") == 0)
        return ma_backend_alsa;
    return ND_BE_UNKNOWN;
}

static void nd_sleep_ms(int ms) {
#if defined(_WIN32)
    Sleep(ms);
#else
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
#endif
}

/* Audio callback: the audio thread. No allocation, no blocking, no JVM calls. */
static void nd_data_callback(ma_device *dev, void *out, const void *in, ma_uint32 frames) {
    nd_out *o = (nd_out *)dev->pUserData;
    float *dst = (float *)out;
    size_t want = (size_t)frames * (size_t)o->channels;
    size_t r = atomic_load_explicit(&o->r, memory_order_relaxed);
    size_t w = atomic_load_explicit(&o->w, memory_order_acquire);
    size_t avail = (w >= r) ? (w - r) : (o->capacity - r + w);
    size_t take = avail < want ? avail : want;
    int stopping = atomic_load_explicit(&o->stopping, memory_order_acquire);
    size_t i;

    (void)in;

    if (stopping) {
        /* The stop ramp wins over the ring so the fade stays linear even with
           queued audio; then silence until nd_out_stop calls ma_device_stop. */
        size_t pos = atomic_load_explicit(&o->ramp_pos, memory_order_relaxed);
        size_t fr, k;
        size_t remaining = pos < o->ramp_frames ? o->ramp_frames - pos : 0;
        size_t ramp_samples = remaining * (size_t)o->channels;
        size_t media = 0;
        if (take > ramp_samples)
            take = ramp_samples;
        for (fr = 0; fr < frames; ++fr) {
            float gain = pos < o->ramp_frames ? 1.0f - (float)pos / (float)o->ramp_frames : 0.0f;
            for (k = 0; k < (size_t)o->channels; ++k) {
                size_t idx = fr * (size_t)o->channels + k;
                dst[idx] = (idx < take) ? o->ring[(r + idx) % o->capacity] * gain : 0.0f;
            }
            if (fr * (size_t)o->channels < take)
                ++media;
            ++pos;
        }
        atomic_store_explicit(&o->ramp_pos, pos, memory_order_relaxed);
        atomic_store_explicit(&o->r, (r + take) % o->capacity, memory_order_release);
        atomic_fetch_add_explicit(&o->frames_played, (long long)media, memory_order_relaxed);
        atomic_fetch_add_explicit(&o->underrun_frames, (long long)((frames < remaining ? frames : remaining) - media),
                                  memory_order_relaxed);
        nd_publish_clock(o);
        if (pos >= o->ramp_frames)
            atomic_store_explicit(&o->stopped, 1, memory_order_release);
        return;
    }

    for (i = 0; i < take; ++i)
        dst[i] = o->ring[(r + i) % o->capacity];
    for (; i < want; ++i)
        dst[i] = 0.0f;

    /* Start ramp on freshly played audio. */
    {
        size_t pos = atomic_load_explicit(&o->ramp_pos, memory_order_relaxed);
        if (pos < o->ramp_frames) {
            size_t fr, k;
            for (fr = 0; fr < frames && pos < o->ramp_frames; ++fr, ++pos) {
                float gain = (float)pos / (float)o->ramp_frames;
                for (k = 0; k < (size_t)o->channels; ++k)
                    dst[fr * (size_t)o->channels + k] *= gain;
            }
            atomic_store_explicit(&o->ramp_pos, pos, memory_order_relaxed);
        }
    }

    atomic_store_explicit(&o->r, (r + take) % o->capacity, memory_order_release);
    atomic_fetch_add_explicit(&o->frames_played, (long long)(take / (size_t)o->channels), memory_order_relaxed);
    atomic_fetch_add_explicit(&o->underrun_frames, (long long)(frames - take / (size_t)o->channels),
                              memory_order_relaxed);
    nd_publish_clock(o);
}

ND_API int nd_out_open(const nd_out_config *cfg, nd_out **out) {
    nd_out *o;
    ma_device_config dc;
    ma_backend be;
    ma_result mr;
    int ring_ms;
    size_t capacity;

    if (out == NULL || cfg == NULL)
        return ND_E_ARG;
    if (cfg->sample_rate < 0 || cfg->ring_ms < 0 || (cfg->channels != 0 && cfg->channels != ND_DEFAULT_CHANNELS))
        return ND_E_ARG;
    *out = NULL;
    o = (nd_out *)calloc(1, sizeof(*o));
    if (o == NULL)
        return ND_E_DEVICE;

    be = nd_backend(cfg->backend);
    if (be == ND_BE_UNKNOWN) {
        free(o);
        return ND_E_BACKEND;
    }
    o->channels = cfg->channels > 0 ? cfg->channels : ND_DEFAULT_CHANNELS;
    ring_ms = cfg->ring_ms > 0 ? cfg->ring_ms : ND_DEFAULT_RING_MS;

    dc = ma_device_config_init(ma_device_type_playback);
    dc.playback.format = ma_format_f32;
    dc.playback.channels = (ma_uint32)o->channels;
    dc.sampleRate = (ma_uint32)cfg->sample_rate;
    dc.dataCallback = nd_data_callback;
    dc.pUserData = o;

    if (be == ND_BE_DEFAULT) {
        mr = ma_device_init(NULL, &dc, &o->device);
    } else {
        /* A pinned backend gets its own context so "null" can never fall
           through to real hardware. */
        ma_backend backends[1];
        ma_context_config cc = ma_context_config_init();
        backends[0] = be;
        o->owned_ctx = (ma_context *)calloc(1, sizeof(ma_context));
        if (o->owned_ctx == NULL) {
            free(o);
            return ND_E_DEVICE;
        }
        if (ma_context_init(backends, 1, &cc, o->owned_ctx) != MA_SUCCESS) {
            free(o->owned_ctx);
            free(o);
            return ND_E_BACKEND;
        }
        mr = ma_device_init(o->owned_ctx, &dc, &o->device);
    }
    if (mr != MA_SUCCESS) {
        if (o->owned_ctx != NULL) {
            ma_context_uninit(o->owned_ctx);
            free(o->owned_ctx);
        }
        free(o);
        return ND_E_DEVICE;
    }

    o->rate = (int)o->device.sampleRate;
    o->ramp_frames = (size_t)o->rate * ND_RAMP_MS / 1000;
    if (o->ramp_frames == 0)
        o->ramp_frames = 1;
    /* Leave one physical frame unused so equal read/write cursors always
       mean empty, including immediately after a maximum-sized write. */
    capacity = ((size_t)o->rate * (size_t)ring_ms / 1000 + 1) * (size_t)o->channels;
    o->ring = (float *)calloc(capacity, sizeof(float));
    if (o->ring == NULL) {
        ma_device_uninit(&o->device);
        if (o->owned_ctx != NULL) {
            ma_context_uninit(o->owned_ctx);
            free(o->owned_ctx);
        }
        free(o);
        return ND_E_DEVICE;
    }
    o->capacity = capacity;
    snprintf(o->device_name, sizeof(o->device_name), "%s", o->device.playback.name);

    atomic_init(&o->r, 0);
    atomic_init(&o->w, 0);
    atomic_init(&o->frames_played, 0);
    atomic_init(&o->underrun_frames, 0);
    atomic_init(&o->started, 0);
    atomic_init(&o->stopping, 0);
    atomic_init(&o->stopped, 0);
    atomic_init(&o->ramp_pos, o->ramp_frames); /* no ramp before first start */
    /* Test the actual allocated objects, including their ABI alignment. MSVC's
       macro classification does not establish this per-object property. */
    if (!atomic_is_lock_free(&o->r) || !atomic_is_lock_free(&o->w) || !atomic_is_lock_free(&o->frames_played) ||
        !atomic_is_lock_free(&o->underrun_frames) || !atomic_is_lock_free(&o->started) ||
        !atomic_is_lock_free(&o->stopping) || !atomic_is_lock_free(&o->stopped) || !atomic_is_lock_free(&o->ramp_pos)) {
        nd_out_close(o);
        return ND_E_BACKEND;
    }

    *out = o;
    return 0;
}

ND_API int nd_out_write(nd_out *o, const float *interleaved, int frames) {
    size_t r, w, used, free_n, want, n, i;
    if (o == NULL || interleaved == NULL || frames < 0)
        return ND_E_ARG;
    r = atomic_load_explicit(&o->r, memory_order_acquire);
    w = atomic_load_explicit(&o->w, memory_order_relaxed);
    used = (w >= r) ? (w - r) : (o->capacity - r + w);
    free_n = o->capacity - used - (size_t)o->channels;
    want = (size_t)frames * (size_t)o->channels;
    n = want < free_n ? want : free_n;
    for (i = 0; i < n; ++i)
        o->ring[(w + i) % o->capacity] = interleaved[i];
    atomic_store_explicit(&o->w, (w + n) % o->capacity, memory_order_release);
    return (int)(n / (size_t)o->channels);
}

ND_API int nd_out_free_frames(nd_out *o) {
    size_t r, w, used;
    if (o == NULL)
        return ND_E_ARG;
    r = atomic_load_explicit(&o->r, memory_order_acquire);
    w = atomic_load_explicit(&o->w, memory_order_relaxed);
    used = (w >= r) ? (w - r) : (o->capacity - r + w);
    return (int)((o->capacity - used) / (size_t)o->channels) - 1;
}

ND_API int64_t nd_out_frames_played(nd_out *o) {
    if (o == NULL)
        return 0;
    return (int64_t)atomic_load_explicit(&o->frames_played, memory_order_acquire);
}

ND_API int64_t nd_out_underrun_frames(nd_out *o) {
    if (o == NULL)
        return 0;
    return (int64_t)atomic_load_explicit(&o->underrun_frames, memory_order_acquire);
}

ND_API int nd_out_start(nd_out *o) {
    if (o == NULL)
        return ND_E_ARG;
    if (atomic_load_explicit(&o->started, memory_order_relaxed))
        return 0;
    atomic_store_explicit(&o->ramp_pos, 0, memory_order_relaxed);
    atomic_store_explicit(&o->stopping, 0, memory_order_relaxed);
    atomic_store_explicit(&o->stopped, 0, memory_order_relaxed);
    if (ma_device_start(&o->device) != MA_SUCCESS)
        return ND_E_DEVICE;
    atomic_store_explicit(&o->started, 1, memory_order_release);
    return 0;
}

ND_API int nd_out_stop(nd_out *o) {
    int waited = 0;
    if (o == NULL)
        return ND_E_ARG;
    if (!atomic_load_explicit(&o->started, memory_order_relaxed))
        return 0;
    atomic_store_explicit(&o->ramp_pos, 0, memory_order_relaxed);
    atomic_store_explicit(&o->stopping, 1, memory_order_release);
    while (!atomic_load_explicit(&o->stopped, memory_order_acquire) && waited < ND_STOP_WAIT_MS) {
        nd_sleep_ms(1);
        ++waited;
    }
    if (ma_device_stop(&o->device) != MA_SUCCESS)
        return ND_E_DEVICE;
    atomic_store_explicit(&o->started, 0, memory_order_release);
    atomic_store_explicit(&o->stopping, 0, memory_order_relaxed);
    return 0;
}

ND_API int nd_out_clear(nd_out *o) {
    size_t r, w;
    if (o == NULL)
        return ND_E_ARG;
    /* The producer may replace the consumer cursor only after stop joins it. */
    if (atomic_load_explicit(&o->started, memory_order_acquire))
        return ND_E_STATE;
    r = atomic_load_explicit(&o->r, memory_order_acquire);
    w = atomic_load_explicit(&o->w, memory_order_relaxed);
    atomic_store_explicit(&o->r, w, memory_order_release);
    return (int)((w >= r ? w - r : o->capacity - r + w) / (size_t)o->channels);
}

ND_API void nd_out_close(nd_out *o) {
    if (o == NULL)
        return;
    if (atomic_load_explicit(&o->started, memory_order_relaxed))
        nd_out_stop(o);
    ma_device_uninit(&o->device);
    if (o->owned_ctx != NULL) {
        ma_context_uninit(o->owned_ctx);
        free(o->owned_ctx);
    }
    free(o->ring);
    free(o);
}

ND_API int nd_out_describe(nd_out *o, char *json, int cap) {
    const char *be;
    int n;
    if (o == NULL || json == NULL || cap <= 0)
        return ND_E_ARG;
    if (o->device.pContext == NULL)
        be = "null";
    else
        switch (o->device.pContext->backend) {
        case ma_backend_null:
            be = "null";
            break;
        case ma_backend_wasapi:
            be = "wasapi";
            break;
        case ma_backend_coreaudio:
            be = "coreaudio";
            break;
        case ma_backend_pulseaudio:
            be = "pulse";
            break;
        case ma_backend_alsa:
            be = "alsa";
            break;
        default:
            be = "other";
            break;
        }
    char escaped[sizeof(o->device_name) * 6];
    if (nd_json_escape(escaped, sizeof(escaped), o->device_name) < 0)
        return ND_E_ARG;
    n = snprintf(json, (size_t)cap, "{\"rate\":%d,\"channels\":%d,\"backend\":\"%s\",\"device\":\"%s\",\"period\":%u}",
                 o->rate, o->channels, be, escaped, (unsigned)o->device.playback.internalPeriodSizeInFrames);
    return n >= cap ? ND_E_ARG : n;
}
