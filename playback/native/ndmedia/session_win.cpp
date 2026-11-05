// SPDX-License-Identifier: Unlicense
// WinRT ABI declarations come from the Windows SDK. No C++/WinRT or WRL
// implementation is embedded: the COM delegates and ownership below are ours.
#include "session.h"
#include <mmdeviceapi.h>
#include <roapi.h>
#include <shobjidl.h>
#include <string.h>
#include <systemmediatransportcontrolsinterop.h>
#include <wchar.h>
#include <windows.h>
#include <windows.media.h>

using namespace ABI::Windows::Media;
using namespace ABI::Windows::Foundation;
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
wchar_t title[TEXT_CAPACITY]{}, artist[TEXT_CAPACITY]{}, endpoint[ENDPOINT_CAPACITY]{};

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

HRESULT publish(ISystemMediaTransportControls *controls, ISystemMediaTransportControls2 *controls2) {
    Guard guard;
    ISystemMediaTransportControlsDisplayUpdater *updater = nullptr;
    IMusicDisplayProperties *music = nullptr;
    HSTRING t = nullptr, a = nullptr;
    HRESULT result = controls->put_PlaybackStatus(playing ? MediaPlaybackStatus_Playing : MediaPlaybackStatus_Paused);
    if (SUCCEEDED(result))
        result = controls->get_DisplayUpdater(&updater);
    if (SUCCEEDED(result))
        result = updater->put_Type(MediaPlaybackType_Music);
    if (SUCCEEDED(result))
        result = updater->get_MusicProperties(&music);
    if (SUCCEEDED(result))
        result = text(title, &t);
    if (SUCCEEDED(result))
        result = text(artist, &a);
    if (SUCCEEDED(result))
        result = music->put_Title(t);
    if (SUCCEEDED(result))
        result = music->put_Artist(a);
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
    if (SUCCEEDED(result))
        result = SetCurrentProcessExplicitAppUserModelID(L"ch.lkmc.neutrodyne.debug");
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
extern "C" ND_API int nd_session_publish(const char *t, const char *a, int64_t duration, int64_t position, float rate,
                                         int p) {
    wchar_t nextTitle[TEXT_CAPACITY], nextArtist[TEXT_CAPACITY];
    HRESULT result = utf8(t, nextTitle, TEXT_CAPACITY);
    if (SUCCEEDED(result))
        result = utf8(a, nextArtist, TEXT_CAPACITY);
    if (FAILED(result))
        return result;
    Guard guard;
    if (!active || !window)
        return ND_E_STATE;
    memcpy(title, nextTitle, (wcslen(nextTitle) + 1) * sizeof(wchar_t));
    memcpy(artist, nextArtist, (wcslen(nextArtist) + 1) * sizeof(wchar_t));
    playing = p != 0;
    durationMs = duration;
    positionMs = position;
    playbackRate = rate;
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
