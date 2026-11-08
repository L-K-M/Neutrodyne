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
/* One publish: the media-session metadata of 11's Windows SMTC and macOS Now
   Playing rows. Strings are borrowed UTF-8 and NULL when absent. */
typedef struct {
    const char *title;
    const char *artist;
    const char *album;
    const char *art_path;
    int64_t duration_ms;
    int64_t position_ms;
    float rate;
    int playing;
} nd_media_info;
/* Strings are borrowed UTF-8. Windows rejects invalid text or fields above
   4095 UTF-16 code units; no caller buffer is retained after this call. */
ND_API int nd_session_publish(const nd_media_info *m);
ND_API int nd_session_poll(int64_t *value);
ND_API void nd_session_close(void);
/* Keep the computer from idle-sleeping while an episode plays (11 Power):
   Windows SetThreadExecutionState, macOS IOPMAssertion NoIdleSleep. Linux has
   no native shim and keeps the portal path. Idempotent. */
ND_API int nd_power_keep_awake(int on);
/* SMAppService.mainApp through the Objective-C shim: 0 status, 1 register,
   2 unregister. Status is the SMAppServiceStatus enum (0 not registered,
   1 enabled, 2 requires approval, 3 not found); register and unregister
   return 0 or a negative ND_E_* code. */
ND_API int nd_mac_login_item(int op);
/* Windows only (11 Start-up step 4): SetCurrentProcessExplicitAppUserModelID
   for the whole process, remembered so a later media-session start re-asserts
   it instead of resetting a packaged build's release ID. `aumid_utf8` is a
   borrowed UTF-8 string — at most 128 UTF-16 units, no spaces. The symbol is
   absent on other platforms; a missing export means unsupported. */
ND_API int nd_win_process_set_aumid(const char *aumid_utf8);
/* Windows only (11 Windows MSI and ZIP): stamp System.AppUserModel.ID on a
   shortcut through IShellLinkW + IPropertyStore + IPersistFile::Save.
   `lnk_utf8` must name an absolute, existing .lnk path; a shortcut already
   carrying `aumid_utf8` is left untouched. Same missing-export contract. */
ND_API int nd_win_shortcut_set_aumid(const char *lnk_utf8, const char *aumid_utf8);
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
