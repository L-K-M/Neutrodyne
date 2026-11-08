/* SPDX-License-Identifier: Unlicense */
/* Windows-only boundary fixture, run by build.sh's windows-x64 leg (11
   Start-up step 4, Windows MSI and ZIP): the process AppUserModelID bounds and
   the .lnk stamp against real IShellLinkW + IPropertyStore + IPersistFile
   objects. The fixture's .lnk lives in its own temp directory — no user
   shortcut and no registry key is touched. */
#include "../session.h"
#include <objbase.h>
#include <propkey.h>
#include <propvarutil.h>
#include <shlobj.h>
#include <stdio.h>
#include <windows.h>

static int failures = 0;
#define CHECK(cond, msg)                                                                            \
    do {                                                                                            \
        if (!(cond)) {                                                                              \
            fprintf(stderr, "FAIL %s (line %d)\n", (msg), __LINE__);                                  \
            ++failures;                                                                             \
        }                                                                                           \
    } while (0)

static void to_utf8(const wchar_t *in, char *out, int capacity) {
    WideCharToMultiByte(CP_UTF8, 0, in, -1, out, capacity, NULL, NULL);
}

static void to_wide(const char *in, wchar_t *out, int capacity) {
    MultiByteToWideChar(CP_UTF8, 0, in, -1, out, capacity);
}

/* A throwaway .lnk pointing at this fixture's own exe, saved under dir. */
static int create_shortcut(const wchar_t *lnk) {
    IShellLinkW *link = NULL;
    IPersistFile *file = NULL;
    wchar_t exe[MAX_PATH];
    HRESULT result = CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW,
                                      (void **)&link);
    if (SUCCEEDED(result))
        result = link->lpVtbl->QueryInterface(link, &IID_IPersistFile, (void **)&file);
    if (SUCCEEDED(result)) {
        GetModuleFileNameW(NULL, exe, MAX_PATH);
        result = link->lpVtbl->SetPath(link, exe);
    }
    if (SUCCEEDED(result))
        result = link->lpVtbl->SetDescription(link, L"ndmedia identity fixture");
    if (SUCCEEDED(result))
        result = file->lpVtbl->Save(file, lnk, TRUE);
    if (file)
        file->lpVtbl->Release(file);
    if (link)
        link->lpVtbl->Release(link);
    return SUCCEEDED(result) ? 0 : -1;
}

/* Reads System.AppUserModel.ID back through IPropertyStore; "" when absent. */
static int read_aumid(const wchar_t *lnk, wchar_t *out, int capacity) {
    IShellLinkW *link = NULL;
    IPersistFile *file = NULL;
    IPropertyStore *store = NULL;
    PROPVARIANT value;
    out[0] = 0;
    HRESULT result = CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW,
                                      (void **)&link);
    if (SUCCEEDED(result))
        result = link->lpVtbl->QueryInterface(link, &IID_IPersistFile, (void **)&file);
    if (SUCCEEDED(result))
        result = file->lpVtbl->Load(file, lnk, STGM_READ);
    if (SUCCEEDED(result))
        result = link->lpVtbl->QueryInterface(link, &IID_IPropertyStore, (void **)&store);
    if (SUCCEEDED(result)) {
        PropVariantInit(&value);
        result = store->lpVtbl->GetValue(store, &PKEY_AppUserModel_ID, &value);
        if (SUCCEEDED(result) && value.vt == VT_LPWSTR && value.pwszVal)
            lstrcpynW(out, value.pwszVal, capacity);
        PropVariantClear(&value);
    }
    if (store)
        store->lpVtbl->Release(store);
    if (file)
        file->lpVtbl->Release(file);
    if (link)
        link->lpVtbl->Release(link);
    return SUCCEEDED(result) ? 0 : -1;
}

int main(void) {
    const char *aumid = "ch.lkmc.neutrodyne.identitytest";
    wchar_t dir[MAX_PATH], lnk[MAX_PATH], read_back[256], expected[256];
    char lnk_utf8[MAX_PATH * 4];

    CHECK(CoInitializeEx(NULL, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) == S_OK, "CoInit");

    /* Process identity: bounds first, then a read-back through the OS getter. */
    CHECK(nd_win_process_set_aumid(NULL) < 0, "null aumid rejected");
    CHECK(nd_win_process_set_aumid("has space") < 0, "space rejected");
    {
        char too_long[200];
        memset(too_long, 'x', sizeof(too_long) - 1);
        too_long[sizeof(too_long) - 1] = 0;
        CHECK(nd_win_process_set_aumid(too_long) < 0, "overlong aumid rejected");
    }
    CHECK(nd_win_process_set_aumid(aumid) == 0, "process aumid set");
    {
        PWSTR current = NULL;
        CHECK(GetCurrentProcessExplicitAppUserModelID(&current) == S_OK, "process aumid read back");
        if (current) {
            to_wide(aumid, expected, 256);
            CHECK(wcscmp(current, expected) == 0, "process aumid matches");
            CoTaskMemFree(current);
        }
    }

    /* Shortcut stamp: the fixture's own .lnk inside its own temp directory. */
    CHECK(GetTempPathW(MAX_PATH, dir) > 0, "temp path");
    {
        wchar_t unique[64];
        swprintf(unique, 64, L"nd-identity-%lu", GetCurrentProcessId());
        lstrcatW(dir, unique);
        CHECK(CreateDirectoryW(dir, NULL) != 0 || GetLastError() == ERROR_ALREADY_EXISTS,
              "temp dir");
        lstrcpyW(lnk, dir);
        lstrcatW(lnk, L"\\fixture.lnk");
        CHECK(create_shortcut(lnk) == 0, "fixture .lnk created");

        to_utf8(lnk, lnk_utf8, sizeof(lnk_utf8));
        CHECK(nd_win_shortcut_set_aumid(lnk_utf8, aumid) == 0, "shortcut stamped");
        CHECK(read_aumid(lnk, read_back, 256) == 0, "shortcut read back");
        to_wide(aumid, expected, 256);
        CHECK(wcscmp(read_back, expected) == 0, "shortcut aumid matches");
        CHECK(nd_win_shortcut_set_aumid(lnk_utf8, aumid) == 0, "stamp is idempotent");

        /* Bounds: relative, non-.lnk and absent paths are all refused. */
        CHECK(nd_win_shortcut_set_aumid("fixture.lnk", aumid) < 0, "relative path rejected");
        CHECK(nd_win_shortcut_set_aumid("C:\\Windows\\fixture.txt", aumid) < 0,
              "non-.lnk rejected");
        {
            wchar_t missing[MAX_PATH];
            char missing_utf8[MAX_PATH * 4];
            lstrcpyW(missing, dir);
            lstrcatW(missing, L"\\absent.lnk");
            to_utf8(missing, missing_utf8, sizeof(missing_utf8));
            CHECK(nd_win_shortcut_set_aumid(missing_utf8, aumid) < 0, "absent .lnk rejected");
        }
        CHECK(nd_win_shortcut_set_aumid(NULL, aumid) < 0, "null path rejected");
        CHECK(nd_win_shortcut_set_aumid(lnk_utf8, "bad aumid") < 0, "bad aumid rejected");

        DeleteFileW(lnk);
        RemoveDirectoryW(dir);
    }

    CoUninitialize();
    if (failures) {
        fprintf(stderr, "%d identity check(s) failed\n", failures);
        return 1;
    }
    puts("identity checks passed");
    return 0;
}
