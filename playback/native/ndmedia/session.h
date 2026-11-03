/* SPDX-License-Identifier: Unlicense */
#ifndef ND_SESSION_H
#define ND_SESSION_H
#include "ndmedia.h"
/* One process-wide spike session. Commands stay in native memory until polled;
 * no OS callback enters the JVM. Lifecycle calls run away from the audio thread. */
#ifdef __cplusplus
extern "C" {
#endif
ND_API int nd_session_open(void);
/* Strings are borrowed UTF-8. Windows rejects invalid text or fields above
   4095 UTF-16 code units; no caller buffer is retained after this call. */
ND_API int nd_session_publish(const char *title, const char *artist, int64_t duration_ms, int64_t position_ms,
                              float rate, int playing);
ND_API int nd_session_poll(int64_t *value);
ND_API void nd_session_close(void);
#ifdef __cplusplus
}
#endif
#define ND_SESSION_PLAY 1
#define ND_SESSION_PAUSE 2
#define ND_SESSION_TOGGLE 3
#define ND_SESSION_NEXT 4
#define ND_SESSION_PREVIOUS 5
#define ND_SESSION_SEEK 6
#define ND_SESSION_SUSPEND 7
#define ND_SESSION_RESUME 8
#define ND_SESSION_REMOVED 9
#define ND_SESSION_DEFAULT_CHANGED 10
#endif
