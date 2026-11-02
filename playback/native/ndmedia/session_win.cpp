// SPDX-License-Identifier: Unlicense
// WinRT ABI declarations come from the Windows SDK. No C++/WinRT or WRL
// implementation is embedded: the COM delegates and ownership below are ours.
#include "session.h"
#include <array>
#include <atomic>
#include <future>
#include <mmdeviceapi.h>
#include <mutex>
#include <roapi.h>
#include <shobjidl.h>
#include <stdexcept>
#include <string>
#include <systemmediatransportcontrolsinterop.h>
#include <thread>
#include <windows.h>
#include <windows.media.h>

using namespace ABI::Windows::Media;
using namespace ABI::Windows::Foundation;
using Positions = ITypedEventHandler<SystemMediaTransportControls *,
                                     PlaybackPositionChangeRequestedEventArgs *>;
using Buttons =
    ITypedEventHandler<SystemMediaTransportControls *, SystemMediaTransportControlsButtonPressedEventArgs *>;

namespace {
struct Event {
    int kind;
    int64_t value;
};
std::mutex mutex;
std::array<Event, 64> events;
size_t head = 0, count = 0;
std::atomic<bool> active{false};
std::atomic<HRESULT> failure{S_OK};
std::thread worker;
HWND window = nullptr;
std::wstring title, artist, endpoint;
bool playing = false;
int64_t durationMs = 0, positionMs = 0;
float playbackRate = 1;
constexpr UINT publishMessage = WM_APP + 1;

void enqueue(int kind, int64_t value = 0) noexcept {
    try {
        std::lock_guard<std::mutex> lock(mutex);
        if (!active)
            return;
        if (count == events.size()) {
            failure = E_OUTOFMEMORY;
            return;
        }
        events[(head + count++) % events.size()] = {kind, value};
    } catch (...) {
        failure = E_FAIL;
    }
}

template <class T> void release(T *&value) {
    if (value) {
        value->Release();
        value = nullptr;
    }
}

class ButtonHandler final : public Buttons {
    std::atomic<ULONG> references{1};

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
    ULONG STDMETHODCALLTYPE Release() override {
        auto n = --references;
        if (!n)
            delete this;
        return n;
    }
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
    std::atomic<ULONG> references{1};

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
    ULONG STDMETHODCALLTYPE Release() override {
        auto n = --references;
        if (!n)
            delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE
    Invoke(ISystemMediaTransportControls *,
           IPlaybackPositionChangeRequestedEventArgs *args) override {
        TimeSpan value;
        HRESULT result = args->get_RequestedPlaybackPosition(&value);
        if (SUCCEEDED(result))
            enqueue(ND_SESSION_SEEK, value.Duration / 10'000);
        return result;
    }
};

class Routes final : public IMMNotificationClient {
    std::atomic<ULONG> references{1};
    bool current(LPCWSTR id) {
        std::lock_guard<std::mutex> lock(mutex);
        return id && endpoint == id;
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
    ULONG STDMETHODCALLTYPE Release() override {
        auto n = --references;
        if (!n)
            delete this;
        return n;
    }
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
        try {
            std::lock_guard<std::mutex> lock(mutex);
            endpoint = id ? id : L"";
        } catch (...) {
            failure = E_FAIL;
        }
        return S_OK;
    }
};

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

HRESULT text(const std::wstring &value, HSTRING *out) {
    return WindowsCreateString(value.data(), static_cast<UINT32>(value.size()), out);
}

HRESULT publish(ISystemMediaTransportControls *controls, ISystemMediaTransportControls2 *controls2) {
    std::lock_guard<std::mutex> lock(mutex);
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

void run(std::promise<int> ready) noexcept {
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
        result = SetCurrentProcessExplicitAppUserModelID(L"ch.lkmc.neutrodyne.md0");
    WNDCLASSW cls{};
    cls.lpfnWndProc = procedure;
    cls.hInstance = GetModuleHandleW(nullptr);
    cls.lpszClassName = L"NeutrodyneMd0Session";
    if (SUCCEEDED(result) && !RegisterClassW(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
        result = HRESULT_FROM_WIN32(GetLastError());
    if (SUCCEEDED(result)) {
        // A hidden top-level window, not HWND_MESSAGE, receives power broadcasts.
        window = CreateWindowW(cls.lpszClassName, L"Neutrodyne MD0", WS_OVERLAPPED, 0, 0, 0, 0, nullptr, nullptr,
                               cls.hInstance, nullptr);
        if (!window)
            result = HRESULT_FROM_WIN32(GetLastError());
    }
    if (SUCCEEDED(result))
        result = text(L"Windows.Media.SystemMediaTransportControls", &className);
    if (SUCCEEDED(result))
        result = RoGetActivationFactory(className, __uuidof(ISystemMediaTransportControlsInterop),
                                        reinterpret_cast<void **>(&factory));
    if (SUCCEEDED(result))
        result = factory->GetForWindow(window, __uuidof(ISystemMediaTransportControls),
                                       reinterpret_cast<void **>(&controls));
    if (SUCCEEDED(result))
        result =
            controls->QueryInterface(__uuidof(ISystemMediaTransportControls2), reinterpret_cast<void **>(&controls2));
    if (SUCCEEDED(result)) {
        positions = new PositionHandler();
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
        buttons = new ButtonHandler();
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
            endpoint = id;
            CoTaskMemFree(id);
        }
    }
    if (SUCCEEDED(result)) {
        routes = new Routes();
        result = devices->RegisterEndpointNotificationCallback(routes);
    }
    ready.set_value(static_cast<int>(result));
    if (SUCCEEDED(result)) {
        MSG message;
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            if (message.message == publishMessage)
                failure = publish(controls, controls2);
            else {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
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
    if (window && IsWindow(window))
        DestroyWindow(window);
    window = nullptr;
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
}

std::wstring utf8(const char *value) {
    if (!value)
        return L"";
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, nullptr, 0);
    if (!length)
        throw std::invalid_argument("invalid UTF-8");
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, result.data(), length);
    result.pop_back();
    return result;
}
} // namespace

extern "C" ND_API int nd_session_open(void) {
    if (active.exchange(true))
        return ND_E_STATE;
    try {
        failure = S_OK;
        head = count = 0;
        std::promise<int> ready;
        auto future = ready.get_future();
        worker = std::thread(run, std::move(ready));
        int result = future.get();
        if (FAILED(result)) {
            worker.join();
            active = false;
        }
        return result;
    } catch (...) {
        active = false;
        return ND_E_DEVICE;
    }
}
extern "C" ND_API int nd_session_publish(const char *t, const char *a, int64_t duration, int64_t position, float rate,
                                         int p) {
    if (!active)
        return ND_E_STATE;
    try {
        {
            std::lock_guard<std::mutex> lock(mutex);
            title = utf8(t);
            artist = utf8(a);
            playing = p != 0;
            durationMs = duration;
            positionMs = position;
            playbackRate = rate;
        }
        return PostMessageW(window, publishMessage, 0, 0) ? 0 : ND_E_STATE;
    } catch (...) {
        return ND_E_ARG;
    }
}
extern "C" ND_API int nd_session_poll(int64_t *value) {
    if (!value)
        return ND_E_ARG;
    if (FAILED(failure))
        return static_cast<int>(failure.load());
    std::lock_guard<std::mutex> lock(mutex);
    if (!count)
        return 0;
    const auto event = events[head];
    head = (head + 1) % events.size();
    --count;
    *value = event.value;
    return event.kind;
}
extern "C" ND_API void nd_session_close(void) {
    active = false;
    if (window)
        PostMessageW(window, WM_CLOSE, 0, 0);
    if (worker.joinable())
        worker.join();
}
