/* SPDX-License-Identifier: Unlicense */
/* The Windows thumbnail boundary (11 Windows SMTC): a publish whose artwork file is
 * missing must leave the session alive. Before the wait's non-completed statuses were
 * handled, the shim called RandomAccessStreamReference.CreateFromFile with the null
 * IStorageFile a failed GetFileFromPathAsync leaves behind, which faults inside the
 * Windows runtime. Windows-only: the session shim and WinRT activation exist there. */
#include "session.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <windows.h>

int main(void) {
    int result = nd_session_open();
    if (result < 0) {
        fprintf(stderr, "thumbnail boundary test: session open failed (%d)\n", result);
        return 1;
    }
    nd_media_info info;
    memset(&info, 0, sizeof(info));
    info.title = "Neutrodyne";
    info.artist = "Neutrodyne";
    info.album = "Neutrodyne";
    info.art_path = "C:\\neutrodyne-nonexistent-artwork.png";
    info.duration_ms = 10000;
    info.position_ms = 1000;
    info.rate = 1.0f;
    info.playing = 1;
    assert(nd_session_publish(&info) == 0);
    /* The publish runs on the shim worker: give the failed file lookup its turn. */
    Sleep(100);
    int64_t value = 0;
    assert(nd_session_poll(&value) >= 0);
    /* The failed path is cached: a second publish of the same path must not reopen it. */
    assert(nd_session_publish(&info) == 0);
    Sleep(100);
    assert(nd_session_poll(&value) >= 0);
    nd_session_close();
    printf("thumbnail failure boundary ok\n");
    return 0;
}
