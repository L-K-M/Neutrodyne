// SPDX-License-Identifier: Unlicense
// WinRT ABI declarations come from the Windows SDK. No C++/WinRT or WRL
// implementation is embedded: the COM delegates and ownership below are ours.
#include "session.h"
#include <mmdeviceapi.h>
#include <propkey.h>
#include <propsys.h>
#include <propvarutil.h>
#include <roapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <string.h>
#include <systemmediatransportcontrolsinterop.h>
#include <wchar.h>
#include <wctype.h>
#include <windows.foundation.h>
#include <windows.h>
#include <windows.media.h>
#include <windows.storage.h>
#include <windows.storage.streams.h>

using namespace ABI::Windows::Media;
using namespace ABI::Windows::Foundation;
using namespace ABI::Windows::Storage;
using namespace ABI::Windows::Storage::Streams;
using Positions = ITypedEventHandler<SystemMediaTransportControls *, PlaybackPositionChangeRequestedEventArgs *>;
using Buttons =
    ITypedEventHandler<SystemMediaTransportControls *, SystemMediaTransportControlsButtonPressedEventArgs *>;

namespace {
struct Event {
    int kind;
    int64_t value;
};
// Win32 primitives keep the flat shim independent of the C++ runtime DLLs.
class AtomicWord {
    mutable volatile LONG value;

  public:
    AtomicWord(LONG initial = 0) noexcept : value(initial) {}
    LONG load() const noexcept { return InterlockedCompareExchange(&value, 0, 0); }
    operator LONG() const noexcept { return load(); }
    void operator=(LONG next) noexcept { InterlockedExchange(&value, next); }
    LONG exchange(LONG next) noexcept { return InterlockedExchange(&value, next); }
    LONG operator++() noexcept { return InterlockedIncrement(&value); }
    LONG operator--() noexcept { return InterlockedDecrement(&value); }
};
SRWLOCK mutex = SRWLOCK_INIT;
SRWLOCK lifecycle = SRWLOCK_INIT;
SRWLOCK powerLifecycle = SRWLOCK_INIT;
class Guard {
    SRWLOCK *held;

  public:
    Guard(SRWLOCK *lock = &mutex) noexcept : held(lock) { AcquireSRWLockExclusive(held); }
    ~Guard() { ReleaseSRWLockExclusive(held); }
    Guard(const Guard &) = delete;
    Guard &operator=(const Guard &) = delete;
};
constexpr size_t EVENT_CAPACITY = 64;
constexpr size_t TEXT_CAPACITY = 4096;
constexpr size_t ENDPOINT_CAPACITY = 1024;
Event events[EVENT_CAPACITY];
size_t head = 0, count = 0;
AtomicWord active{false};
AtomicWord failure{S_OK};
AtomicWord readyResult{S_OK};
HANDLE worker = nullptr, readyEvent = nullptr;
HWND window = nullptr;
wchar_t title[TEXT_CAPACITY]{}, artist[TEXT_CAPACITY]{}, album[TEXT_CAPACITY]{}, artPath[TEXT_CAPACITY]{};
wchar_t endpoint[ENDPOINT_CAPACITY]{};
wchar_t thumbnailArt[TEXT_CAPACITY]{};

// The process AppUserModelID configured through nd_win_process_set_aumid —
// empty until the shell sets it at start-up. The media-session worker
// re-asserts this value, so a late session open cannot reset a packaged
// build's release ID to the development one (11 Start-up step 4).
SRWLOCK identityLock = SRWLOCK_INIT;
constexpr size_t AUMID_CAPACITY = 129; // MS limit 128 + terminator.
wchar_t processAumid[AUMID_CAPACITY]{};

// SetThreadExecutionState is per-thread: a release from a different thread would
// leave the acquire set. The power state therefore lives on one dedicated thread
// that outlives every acquire/release pair.
HANDLE powerThread = nullptr;
HANDLE powerRequest = nullptr;
HANDLE powerDone = nullptr;
LONG powerCommand = 0;
LONG powerResult = 0;

HRESULT copyEndpoint(LPCWSTR id) {
    size_t length = id ? wcslen(id) : 0;
    if (length >= ENDPOINT_CAPACITY)
        return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
    if (length)
        memcpy(endpoint, id, length * sizeof(wchar_t));
    endpoint[length] = 0;
    return S_OK;
}
bool playing = false;
int64_t durationMs = 0, positionMs = 0;
float playbackRate = 1;
constexpr UINT publishMessage = WM_APP + 1;

void enqueue(int kind, int64_t value = 0) noexcept {
    Guard guard;
    if (!active)
        return;
    if (count == EVENT_CAPACITY) {
        failure = E_OUTOFMEMORY;
        return;
    }
    events[(head + count++) % EVENT_CAPACITY] = {kind, value};
}

template <class T> void release(T *&value) {
    if (value) {
        value->Release();
        value = nullptr;
    }
}

class ButtonHandler final : public Buttons {
    AtomicWord references{1};

  public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **result) override {
        if (!result)
            return E_POINTER;
        *result = nullptr;
        if (id != IID_IUnknown && id != __uuidof(Buttons))
            return E_NOINTERFACE;
        *result = static_cast<Buttons *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references; }
    ULONG STDMETHODCALLTYPE Release() override { return --references; }
    HRESULT STDMETHODCALLTYPE Invoke(ISystemMediaTransportControls *,
                                     ISystemMediaTransportControlsButtonPressedEventArgs *args) override {
        SystemMediaTransportControlsButton button;
        if (FAILED(args->get_Button(&button)))
            return E_FAIL;
        switch (button) {
        case SystemMediaTransportControlsButton_Play:
            enqueue(ND_SESSION_PLAY);
            break;
        case SystemMediaTransportControlsButton_Pause:
        case SystemMediaTransportControlsButton_Stop:
            enqueue(ND_SESSION_PAUSE);
            break;
        case SystemMediaTransportControlsButton_Next:
            enqueue(ND_SESSION_NEXT);
            break;
        case SystemMediaTransportControlsButton_Previous:
            enqueue(ND_SESSION_PREVIOUS);
            break;
        default:
            break;
        }
        return S_OK;
    }
};

class PositionHandler final : public Positions {
    AtomicWord references{1};

  public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **result) override {
        if (!result)
            return E_POINTER;
        *result = nullptr;
        if (id != IID_IUnknown && id != __uuidof(Positions))
            return E_NOINTERFACE;
        *result = static_cast<Positions *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references; }
    ULONG STDMETHODCALLTYPE Release() override { return --references; }
    HRESULT STDMETHODCALLTYPE Invoke(ISystemMediaTransportControls *,
                                     IPlaybackPositionChangeRequestedEventArgs *args) override {
        TimeSpan value;
        HRESULT result = args->get_RequestedPlaybackPosition(&value);
        if (SUCCEEDED(result))
            enqueue(ND_SESSION_SEEK, value.Duration / 10'000);
        return result;
    }
};

class Routes final : public IMMNotificationClient {
    AtomicWord references{1};
    bool current(LPCWSTR id) {
        Guard guard;
        return id && wcscmp(endpoint, id) == 0;
    }

  public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **result) override {
        if (!result)
            return E_POINTER;
        *result = nullptr;
        if (id != IID_IUnknown && id != __uuidof(IMMNotificationClient))
            return E_NOINTERFACE;
        *result = static_cast<IMMNotificationClient *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references; }
    ULONG STDMETHODCALLTYPE Release() override { return --references; }
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR id, DWORD state) override {
        if (state != DEVICE_STATE_ACTIVE && current(id))
            enqueue(ND_SESSION_REMOVED);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR id) override {
        if (current(id))
            enqueue(ND_SESSION_REMOVED);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR id) override {
        if (flow != eRender || role != eMultimedia)
            return S_OK;
        enqueue(ND_SESSION_DEFAULT_CHANGED);
        Guard guard;
        HRESULT result = copyEndpoint(id);
        if (FAILED(result))
            failure = result;
        return S_OK;
    }
};

// Each delegate retains a permanent module-owned reference. Unregistering
// releases OS ownership; a late native callback still cannot reach freed code
// or enter the JVM. The worker takes and releases an additional local reference.
ButtonHandler buttonHandler;
PositionHandler positionHandler;
Routes routeHandler;

LRESULT CALLBACK procedure(HWND hwnd, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_CLOSE) {
        DestroyWindow(hwnd);
        return 0;
    }
    if (message == WM_DESTROY) {
        PostQuitMessage(0);
        return 0;
    }
    if (message == WM_POWERBROADCAST) {
        if (w == PBT_APMSUSPEND)
            enqueue(ND_SESSION_SUSPEND);
        if (w == PBT_APMRESUMEAUTOMATIC)
            enqueue(ND_SESSION_RESUME);
        return TRUE;
    }
    return DefWindowProcW(hwnd, message, w, l);
}

HRESULT text(const wchar_t *value, HSTRING *out) {
    return WindowsCreateString(value, static_cast<UINT32>(wcslen(value)), out);
}

// Best effort: the thumbnail is the pinned artwork file through
// RandomAccessStreamReference::CreateFromFile. Unverified for unpackaged apps;
// a failure leaves the thumbnail unset (11 Windows SMTC).
HRESULT put_thumbnail(ISystemMediaTransportControlsDisplayUpdater *updater, const wchar_t *path) {
    if (!path || !path[0])
        return S_OK;
    HSTRING fileClassName = nullptr, referenceClassName = nullptr, pathString = nullptr;
    IStorageFileStatics *fileStatics = nullptr;
    IRandomAccessStreamReferenceStatics *referenceStatics = nullptr;
    IAsyncOperation<StorageFile *> *operation = nullptr;
    IAsyncInfo *info = nullptr;
    IStorageFile *file = nullptr;
    IRandomAccessStreamReference *reference = nullptr;
    AsyncStatus status = AsyncStatus::Started;
    HRESULT result = text(L"Windows.Storage.StorageFile", &fileClassName);
    if (SUCCEEDED(result))
        result = RoGetActivationFactory(fileClassName, __uuidof(IStorageFileStatics),
                                        reinterpret_cast<void **>(&fileStatics));
    if (SUCCEEDED(result))
        result = text(L"Windows.Storage.Streams.RandomAccessStreamReference", &referenceClassName);
    if (SUCCEEDED(result))
        result = RoGetActivationFactory(referenceClassName, __uuidof(IRandomAccessStreamReferenceStatics),
                                        reinterpret_cast<void **>(&referenceStatics));
    if (SUCCEEDED(result))
        result = text(path, &pathString);
    if (SUCCEEDED(result))
        result = fileStatics->GetFileFromPathAsync(pathString, &operation);
    if (SUCCEEDED(result))
        result = operation->QueryInterface(__uuidof(IAsyncInfo), reinterpret_cast<void **>(&info));
    for (int waited = 0; SUCCEEDED(result) && waited < 500; ++waited) {
        result = info->get_Status(&status);
        if (FAILED(result) || status != AsyncStatus::Started)
            break;
        Sleep(10);
    }
    if (SUCCEEDED(result) && status == AsyncStatus::Completed) {
        result = operation->GetResults(&file);
    } else if (SUCCEEDED(result)) {
        // The wait ended without a file: a still-running operation is cancelled,
        // and CreateFromFile never receives the null IStorageFile a timeout,
        // Canceled or Error would leave behind.
        if (status == AsyncStatus::Started)
            info->Cancel();
        if (status == AsyncStatus::Canceled) {
            result = HRESULT_FROM_WIN32(ERROR_CANCELLED);
        } else if (status == AsyncStatus::Error) {
            HRESULT error = E_FAIL;
            if (FAILED(info->get_ErrorCode(&error)))
                error = E_FAIL;
            result = error;
        } else {
            result = HRESULT_FROM_WIN32(ERROR_TIMEOUT);
        }
    }
    if (SUCCEEDED(result))
        result = referenceStatics->CreateFromFile(file, &reference);
    if (SUCCEEDED(result))
        result = updater->put_Thumbnail(reference);
    release(reference);
    release(file);
    release(info);
    release(operation);
    release(referenceStatics);
    release(fileStatics);
    WindowsDeleteString(pathString);
    WindowsDeleteString(referenceClassName);
    WindowsDeleteString(fileClassName);
    return result;
}

HRESULT publish(ISystemMediaTransportControls *controls, ISystemMediaTransportControls2 *controls2) {
    Guard guard;
    ISystemMediaTransportControlsDisplayUpdater *updater = nullptr;
    IMusicDisplayProperties *music = nullptr;
    IMusicDisplayProperties2 *music2 = nullptr;
    HSTRING t = nullptr, a = nullptr, al = nullptr;
    HRESULT result = controls->put_PlaybackStatus(playing ? MediaPlaybackStatus_Playing : MediaPlaybackStatus_Paused);
    if (SUCCEEDED(result))
        result = controls->get_DisplayUpdater(&updater);
    if (SUCCEEDED(result))
        result = updater->put_Type(MediaPlaybackType_Music);
    if (SUCCEEDED(result))
        // Best effort: an unpackaged thumbnail failure leaves the artwork unset. The
        // path cache keeps a five-second playing publish from reopening the file,
        // success or failure: a failing path is remembered until the path changes.
        if (wcscmp(artPath, thumbnailArt) != 0) {
            put_thumbnail(updater, artPath);
            if (artPath[0])
                memcpy(thumbnailArt, artPath, (wcslen(artPath) + 1) * sizeof(wchar_t));
            else
                thumbnailArt[0] = 0;
        }
    if (SUCCEEDED(result))
        result = updater->get_MusicProperties(&music);
    if (SUCCEEDED(result))
        result = text(title, &t);
    if (SUCCEEDED(result))
        result = text(artist, &a);
    if (SUCCEEDED(result))
        result = text(album, &al);
    if (SUCCEEDED(result))
        result = music->put_Title(t);
    if (SUCCEEDED(result))
        result = music->put_Artist(a);
    if (SUCCEEDED(result))
        result = music->QueryInterface(__uuidof(IMusicDisplayProperties2), reinterpret_cast<void **>(&music2));
    if (SUCCEEDED(result))
        result = music2->put_AlbumTitle(al);
    if (SUCCEEDED(result))
        result = updater->Update();
    IInspectable *timelineObject = nullptr;
    ISystemMediaTransportControlsTimelineProperties *timeline = nullptr;
    HSTRING timelineName = nullptr;
    if (SUCCEEDED(result))
        result = text(L"Windows.Media.SystemMediaTransportControlsTimelineProperties", &timelineName);
    if (SUCCEEDED(result))
        result = RoActivateInstance(timelineName, &timelineObject);
    if (SUCCEEDED(result))
        result = timelineObject->QueryInterface(__uuidof(ISystemMediaTransportControlsTimelineProperties),
                                                reinterpret_cast<void **>(&timeline));
    TimeSpan start{0}, end{(durationMs > positionMs ? durationMs : positionMs) * 10'000}, position{positionMs * 10'000};
    if (SUCCEEDED(result))
        result = timeline->put_StartTime(start);
    if (SUCCEEDED(result))
        result = timeline->put_EndTime(end);
    if (SUCCEEDED(result))
        result = timeline->put_MinSeekTime(start);
    if (SUCCEEDED(result))
        result = timeline->put_MaxSeekTime(end);
    if (SUCCEEDED(result))
        result = timeline->put_Position(position);
    if (SUCCEEDED(result))
        result = controls2->put_PlaybackRate(playbackRate);
    if (SUCCEEDED(result))
        result = controls2->UpdateTimelineProperties(timeline);
    WindowsDeleteString(timelineName);
    release(timeline);
    release(timelineObject);
    WindowsDeleteString(t);
    WindowsDeleteString(a);
    WindowsDeleteString(al);
    release(music2);
    release(music);
    release(updater);
    return result;
}

DWORD WINAPI run(void *) noexcept {
    HWND ownedWindow = nullptr;
    ISystemMediaTransportControlsInterop *factory = nullptr;
    ISystemMediaTransportControls *controls = nullptr;
    ISystemMediaTransportControls2 *controls2 = nullptr;
    IMMDeviceEnumerator *devices = nullptr;
    IMMDevice *device = nullptr;
    ButtonHandler *buttons = nullptr;
    PositionHandler *positions = nullptr;
    EventRegistrationToken positionToken{};
    bool positionSubscribed = false;
    Routes *routes = nullptr;
    EventRegistrationToken token{};
    bool subscribed = false;
    HSTRING className = nullptr;
    HRESULT result = RoInitialize(RO_INIT_MULTITHREADED);
    const bool initialized = SUCCEEDED(result);
    if (SUCCEEDED(result)) {
        // The shell owns the process AppUserModelID and sets it before any
        // window exists (11 Start-up step 4). A session opened first still
        // needs it on this thread, so the worker re-asserts the configured
        // value — never a hardcoded ID that could overwrite the release one.
        wchar_t configured[AUMID_CAPACITY]{};
        AcquireSRWLockShared(&identityLock);
        memcpy(configured, processAumid, sizeof(configured));
        ReleaseSRWLockShared(&identityLock);
        if (configured[0])
            result = SetCurrentProcessExplicitAppUserModelID(configured);
    }
    WNDCLASSW cls{};
    cls.lpfnWndProc = procedure;
    cls.hInstance = GetModuleHandleW(nullptr);
    cls.lpszClassName = L"NeutrodyneMd0Session";
    if (SUCCEEDED(result) && !RegisterClassW(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
        result = HRESULT_FROM_WIN32(GetLastError());
    if (SUCCEEDED(result)) {
        // A hidden top-level window, not HWND_MESSAGE, receives power broadcasts.
        ownedWindow = CreateWindowW(cls.lpszClassName, L"Neutrodyne MD0", WS_OVERLAPPED, 0, 0, 0, 0, nullptr, nullptr,
                                    cls.hInstance, nullptr);
        if (!ownedWindow)
            result = HRESULT_FROM_WIN32(GetLastError());
        Guard guard;
        window = ownedWindow;
    }
    if (SUCCEEDED(result))
        result = text(L"Windows.Media.SystemMediaTransportControls", &className);
    if (SUCCEEDED(result))
        result = RoGetActivationFactory(className, __uuidof(ISystemMediaTransportControlsInterop),
                                        reinterpret_cast<void **>(&factory));
    if (SUCCEEDED(result))
        result = factory->GetForWindow(ownedWindow, __uuidof(ISystemMediaTransportControls),
                                       reinterpret_cast<void **>(&controls));
    if (SUCCEEDED(result))
        result =
            controls->QueryInterface(__uuidof(ISystemMediaTransportControls2), reinterpret_cast<void **>(&controls2));
    if (SUCCEEDED(result)) {
        positions = &positionHandler;
        positions->AddRef();
        result = controls2->add_PlaybackPositionChangeRequested(positions, &positionToken);
        positionSubscribed = SUCCEEDED(result);
    }
    if (SUCCEEDED(result))
        result = controls->put_IsEnabled(true);
    if (SUCCEEDED(result))
        result = controls->put_IsPlayEnabled(true);
    if (SUCCEEDED(result))
        result = controls->put_IsPauseEnabled(true);
    if (SUCCEEDED(result))
        result = controls->put_IsNextEnabled(true);
    if (SUCCEEDED(result))
        result = controls->put_IsPreviousEnabled(true);
    if (SUCCEEDED(result)) {
        buttons = &buttonHandler;
        buttons->AddRef();
        result = controls->add_ButtonPressed(buttons, &token);
        subscribed = SUCCEEDED(result);
    }
    if (SUCCEEDED(result))
        result = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
                                  reinterpret_cast<void **>(&devices));
    if (SUCCEEDED(result))
        result = devices->GetDefaultAudioEndpoint(eRender, eMultimedia, &device);
    // A headless runner may have no default endpoint. The real SMTC session
    // can still be created; headphone-removal evidence then remains open.
    if (result == HRESULT_FROM_WIN32(ERROR_NOT_FOUND))
        result = S_OK;
    if (SUCCEEDED(result) && device) {
        LPWSTR id = nullptr;
        result = device->GetId(&id);
        if (SUCCEEDED(result)) {
            Guard guard;
            result = copyEndpoint(id);
            CoTaskMemFree(id);
        }
    }
    if (SUCCEEDED(result)) {
        routes = &routeHandler;
        routes->AddRef();
        result = devices->RegisterEndpointNotificationCallback(routes);
    }
    readyResult = result;
    SetEvent(readyEvent);
    if (SUCCEEDED(result)) {
        MSG message;
        int fetched;
        while ((fetched = GetMessageW(&message, nullptr, 0, 0)) > 0) {
            if (message.message == publishMessage)
                failure = publish(controls, controls2);
            else {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        if (fetched < 0)
            failure = HRESULT_FROM_WIN32(GetLastError());
        else if (active)
            failure = E_ABORT;
    }
    active = false;
    if (routes && devices)
        devices->UnregisterEndpointNotificationCallback(routes);
    if (subscribed)
        controls->remove_ButtonPressed(token);
    if (positionSubscribed)
        controls2->remove_PlaybackPositionChangeRequested(positionToken);
    if (controls)
        controls->put_IsEnabled(false);
    {
        Guard guard;
        window = nullptr;
    }
    if (ownedWindow && IsWindow(ownedWindow))
        DestroyWindow(ownedWindow);
    release(routes);
    release(device);
    release(devices);
    release(buttons);
    release(positions);
    release(controls2);
    release(controls);
    release(factory);
    WindowsDeleteString(className);
    if (initialized)
        RoUninitialize();
    return 0;
}

HRESULT utf8(const char *value, wchar_t *buffer, int capacity) {
    if (!value) {
        buffer[0] = 0;
        return S_OK;
    }
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, buffer, capacity))
        return HRESULT_FROM_WIN32(GetLastError());
    return S_OK;
}

// MS AppUserModelIDs: at most 128 UTF-16 units, no spaces (appids doc).
bool validAumid(const wchar_t *id) {
    const size_t length = wcslen(id);
    if (!length || length > 128)
        return false;
    for (size_t i = 0; i < length; ++i)
        if (id[i] == L' ')
            return false;
    return true;
}

DWORD WINAPI powerRun(void *) noexcept {
    for (;;) {
        WaitForSingleObject(powerRequest, INFINITE);
        LONG command = InterlockedExchange(&powerCommand, 0);
        if (command < 0)
            break;
        EXECUTION_STATE state =
            SetThreadExecutionState(command == 1 ? (ES_CONTINUOUS | ES_SYSTEM_REQUIRED) : ES_CONTINUOUS);
        InterlockedExchange(&powerResult, state != NULL ? 0 : ND_E_DEVICE);
        SetEvent(powerDone);
    }
    return 0;
}
} // namespace

extern "C" ND_API int nd_session_open(void) {
    Guard lifecycleGuard(&lifecycle);
    if (worker || active.exchange(true))
        return ND_E_STATE;
    failure = S_OK;
    head = count = 0;
    readyEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!readyEvent) {
        active = false;
        return HRESULT_FROM_WIN32(GetLastError());
    }
    worker = CreateThread(nullptr, 0, run, nullptr, 0, nullptr);
    if (!worker) {
        HRESULT error = HRESULT_FROM_WIN32(GetLastError());
        CloseHandle(readyEvent);
        readyEvent = nullptr;
        active = false;
        return error;
    }
    WaitForSingleObject(readyEvent, INFINITE);
    CloseHandle(readyEvent);
    readyEvent = nullptr;
    int result = static_cast<int>(readyResult.load());
    if (FAILED(result)) {
        WaitForSingleObject(worker, INFINITE);
        CloseHandle(worker);
        worker = nullptr;
        active = false;
    }
    return result;
}
extern "C" ND_API int nd_session_publish(const nd_media_info *m) {
    if (!m)
        return ND_E_ARG;
    wchar_t nextTitle[TEXT_CAPACITY], nextArtist[TEXT_CAPACITY], nextAlbum[TEXT_CAPACITY], nextArt[TEXT_CAPACITY];
    HRESULT result = utf8(m->title, nextTitle, TEXT_CAPACITY);
    if (SUCCEEDED(result))
        result = utf8(m->artist, nextArtist, TEXT_CAPACITY);
    if (SUCCEEDED(result))
        result = utf8(m->album, nextAlbum, TEXT_CAPACITY);
    if (SUCCEEDED(result))
        result = utf8(m->art_path, nextArt, TEXT_CAPACITY);
    if (FAILED(result))
        return result;
    Guard guard;
    if (!active || !window)
        return ND_E_STATE;
    memcpy(title, nextTitle, (wcslen(nextTitle) + 1) * sizeof(wchar_t));
    memcpy(artist, nextArtist, (wcslen(nextArtist) + 1) * sizeof(wchar_t));
    memcpy(album, nextAlbum, (wcslen(nextAlbum) + 1) * sizeof(wchar_t));
    memcpy(artPath, nextArt, (wcslen(nextArt) + 1) * sizeof(wchar_t));
    playing = m->playing != 0;
    durationMs = m->duration_ms;
    positionMs = m->position_ms;
    playbackRate = m->rate;
    return PostMessageW(window, publishMessage, 0, 0) ? 0 : ND_E_STATE;
}
extern "C" ND_API int nd_session_poll(int64_t *value) {
    if (!value)
        return ND_E_ARG;
    if (FAILED(failure))
        return static_cast<int>(failure.load());
    Guard guard;
    if (!count)
        return 0;
    const auto event = events[head];
    head = (head + 1) % EVENT_CAPACITY;
    --count;
    *value = event.value;
    return event.kind;
}
extern "C" ND_API void nd_session_close(void) {
    Guard lifecycleGuard(&lifecycle);
    {
        Guard guard;
        active = false;
        if (window)
            PostMessageW(window, WM_CLOSE, 0, 0);
    }
    if (worker) {
        WaitForSingleObject(worker, INFINITE);
        CloseHandle(worker);
        worker = nullptr;
    }
}
extern "C" ND_API int nd_win_process_set_aumid(const char *aumid_utf8) {
    wchar_t id[AUMID_CAPACITY];
    HRESULT result = utf8(aumid_utf8, id, AUMID_CAPACITY);
    if (FAILED(result))
        return static_cast<int>(result);
    if (!validAumid(id))
        return ND_E_ARG;
    result = SetCurrentProcessExplicitAppUserModelID(id);
    if (FAILED(result))
        return static_cast<int>(result);
    AcquireSRWLockExclusive(&identityLock);
    memcpy(processAumid, id, (wcslen(id) + 1) * sizeof(wchar_t));
    ReleaseSRWLockExclusive(&identityLock);
    return 0;
}
extern "C" ND_API int nd_win_shortcut_set_aumid(const char *lnk_utf8, const char *aumid_utf8) {
    wchar_t lnk[TEXT_CAPACITY], id[AUMID_CAPACITY];
    HRESULT result = utf8(lnk_utf8, lnk, TEXT_CAPACITY);
    if (SUCCEEDED(result))
        result = utf8(aumid_utf8, id, AUMID_CAPACITY);
    if (FAILED(result))
        return static_cast<int>(result);
    if (!validAumid(id))
        return ND_E_ARG;
    // Only an absolute path to an existing .lnk is ever written; the caller
    // vets the folder. Data only — the path never reaches a command line.
    const size_t length = wcslen(lnk);
    const bool absolute = (length > 2 && iswalpha(lnk[0]) && lnk[1] == L':' && lnk[2] == L'\\') ||
                          (length > 2 && lnk[0] == L'\\' && lnk[1] == L'\\');
    if (!absolute || length < 5 || _wcsicmp(lnk + length - 4, L".lnk") != 0)
        return ND_E_ARG;
    if (GetFileAttributesW(lnk) == INVALID_FILE_ATTRIBUTES)
        return static_cast<int>(HRESULT_FROM_WIN32(GetLastError()));
    HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    if (FAILED(com) && com != RPC_E_CHANGED_MODE)
        return static_cast<int>(com);
    IShellLinkW *link = nullptr;
    IPersistFile *file = nullptr;
    IPropertyStore *store = nullptr;
    result = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link));
    if (SUCCEEDED(result))
        result = link->QueryInterface(IID_PPV_ARGS(&file));
    if (SUCCEEDED(result))
        result = file->Load(lnk, STGM_READ);
    if (SUCCEEDED(result))
        result = link->QueryInterface(IID_PPV_ARGS(&store));
    bool dirty = true;
    if (SUCCEEDED(result)) {
        PROPVARIANT current;
        PropVariantInit(&current);
        // A shortcut already carrying the ID is left untouched: the MSI's file
        // keeps its timestamp and no write races an installer repair check.
        if (SUCCEEDED(store->GetValue(PKEY_AppUserModel_ID, &current)) && current.vt == VT_LPWSTR &&
            current.pwszVal && wcscmp(current.pwszVal, id) == 0) {
            dirty = false;
        }
        PropVariantClear(&current);
    }
    if (SUCCEEDED(result) && dirty) {
        PROPVARIANT value;
        result = InitPropVariantFromString(id, &value);
        if (SUCCEEDED(result)) {
            result = store->SetValue(PKEY_AppUserModel_ID, value);
            PropVariantClear(&value);
        }
        if (SUCCEEDED(result))
            result = store->Commit();
        // pszFileName NULL saves back to the file IPersistFile::Load opened.
        if (SUCCEEDED(result))
            result = file->Save(nullptr, TRUE);
    }
    release(store);
    release(file);
    release(link);
    if (SUCCEEDED(com))
        CoUninitialize();
    return static_cast<int>(result);
}
extern "C" ND_API int nd_power_keep_awake(int on) {
    if (on != 0 && on != 1)
        return ND_E_ARG;
    Guard powerGuard(&powerLifecycle);
    if (powerThread == nullptr) {
        powerRequest = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        powerDone = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (powerRequest == nullptr || powerDone == nullptr) {
            DWORD error = GetLastError();
            if (powerRequest != nullptr)
                CloseHandle(powerRequest);
            if (powerDone != nullptr)
                CloseHandle(powerDone);
            powerRequest = powerDone = nullptr;
            return HRESULT_FROM_WIN32(error);
        }
        powerThread = CreateThread(nullptr, 0, powerRun, nullptr, 0, nullptr);
        if (powerThread == nullptr) {
            DWORD error = GetLastError();
            CloseHandle(powerRequest);
            CloseHandle(powerDone);
            powerRequest = powerDone = nullptr;
            return HRESULT_FROM_WIN32(error);
        }
    }
    InterlockedExchange(&powerCommand, on != 0 ? 1 : 2);
    SetEvent(powerRequest);
    WaitForSingleObject(powerDone, INFINITE);
    return static_cast<int>(InterlockedExchange(&powerResult, 0));
}
