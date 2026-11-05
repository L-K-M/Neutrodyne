/* SPDX-License-Identifier: Unlicense */
#define _POSIX_C_SOURCE 200809L
#include "ndmedia.h"
#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define RATE 48000
#define CHANNELS 2
#define RING_FRAMES (RATE / 5)

static int64_t now_ms(void) {
    struct timespec value;
    assert(clock_gettime(CLOCK_MONOTONIC, &value) == 0);
    return value.tv_sec * 1000 + value.tv_nsec / 1000000;
}
static void yield(void) {
    struct timespec delay = {0, 1000000};
    nanosleep(&delay, NULL);
}
int main(void) {
    float *pcm = malloc(RATE * CHANNELS * sizeof(float));
    assert(pcm != NULL);
    for (int i = 0; i < RATE * CHANNELS; ++i)
        pcm[i] = (float)(i % 97) / 1000;
    for (int iteration = 0; iteration < 3; ++iteration) {
        nd_out *output = NULL;
        nd_out_config config = {"null", RATE, CHANNELS, 200};
        _Alignas(8) int64_t clock[2];
        assert(nd_out_open(&config, &output) == 0 && output != NULL);
        assert(nd_out_attach_clock(output, clock) == 0);
        int written = nd_out_write(output, pcm, RATE);
        assert(written == RING_FRAMES && nd_out_free_frames(output) == 0);
        assert(nd_out_write(output, pcm, 1) == 0);
        assert(nd_out_start(output) == 0);
        assert(nd_out_clear(output) == ND_E_STATE);
        int64_t deadline = now_ms() + 5000;
        while (written < RATE && now_ms() < deadline) {
            int accepted = nd_out_write(output, pcm + written * CHANNELS, RATE - written);
            assert(accepted >= 0 && accepted <= RATE - written);
            written += accepted;
            if (!accepted)
                yield();
        }
        assert(written == RATE);
        while (nd_out_frames_played(output) < RATE && now_ms() < deadline)
            yield();
        assert(nd_out_stop(output) == 0);
        assert(nd_out_frames_played(output) == RATE);
        assert(clock[0] == RATE && clock[1] == nd_out_underrun_frames(output));
        printf("null stress %d: played=%" PRId64 " underrun=%" PRId64 "\n", iteration, clock[0], clock[1]);
        assert(nd_out_clear(output) == 0);
        /* Start and stop inside the 5-ms ramp: the callback owns ramp_pos, so
           the engine thread's stop must never reset it under an in-flight
           callback. The toggle must stay clean and the ring consistent. */
        int64_t playedBeforeToggles = nd_out_frames_played(output);
        for (int toggle = 0; toggle < 50; ++toggle) {
            assert(nd_out_clear(output) >= 0);
            assert(nd_out_write(output, pcm, RATE / 100) > 0);
            assert(nd_out_start(output) == 0);
            assert(nd_out_stop(output) == 0);
        }
        assert(nd_out_clear(output) >= 0);
        assert(nd_out_free_frames(output) == RING_FRAMES);
        int64_t playedAfterToggles = nd_out_frames_played(output);
        assert(playedAfterToggles >= playedBeforeToggles);
        nd_out_close(output);
        yield();
        assert(clock[0] == playedAfterToggles);
    }
    free(pcm);
    return 0;
}
