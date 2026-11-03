/* SPDX-License-Identifier: Unlicense */
#include "session.h"
#import <AppKit/AppKit.h>
#import <CoreAudio/CoreAudio.h>
#import <MediaPlayer/MediaPlayer.h>
#import <os/lock.h>

/* AppKit is bootstrapped by the test-only JVM harness before these calls.
 * Every registration and removal runs on the actual Cocoa main queue. */
static BOOL active = NO;
static os_unfair_lock lock = OS_UNFAIR_LOCK_INIT;
static struct {
    int kind;
    int64_t value;
} events[64];
static size_t head, count;
static int failure;
static NSArray *commandTargets;
static id sleepObserver, wakeObserver;
static AudioDeviceID device;
static UInt32 previousSource;
static AudioObjectPropertyListenerBlock aliveListener, sourceListener, defaultListener;
static const AudioObjectPropertyAddress defaultAddress = {
    kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
static const AudioObjectPropertyAddress aliveAddress = {
    kAudioDevicePropertyDeviceIsAlive, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
static const AudioObjectPropertyAddress sourceAddress = {
    kAudioDevicePropertyDataSource, kAudioDevicePropertyScopeOutput, kAudioObjectPropertyElementMain};

static void main_queue(dispatch_block_t action) {
    if ([NSThread isMainThread])
        action();
    else
        dispatch_sync(dispatch_get_main_queue(), action);
}
static void enqueue(int kind, int64_t value) {
    os_unfair_lock_lock(&lock);
    if (active) {
        if (count == 64)
            failure = ND_E_STATE;
        else {
            size_t index = (head + count++) % 64;
            events[index].kind = kind;
            events[index].value = value;
        }
    }
    os_unfair_lock_unlock(&lock);
}
static BOOL read_property(AudioObjectID object, const AudioObjectPropertyAddress *address, UInt32 *result) {
    UInt32 size = sizeof(*result);
    return AudioObjectGetPropertyData(object, address, 0, NULL, &size, result) == noErr;
}
static void detach_device(void) {
    if (device && aliveListener)
        AudioObjectRemovePropertyListenerBlock(device, &aliveAddress, dispatch_get_main_queue(), aliveListener);
    if (device && sourceListener)
        AudioObjectRemovePropertyListenerBlock(device, &sourceAddress, dispatch_get_main_queue(), sourceListener);
    aliveListener = nil;
    sourceListener = nil;
    device = 0;
}
static void attach_device(void) {
    detach_device();
    if (!read_property(kAudioObjectSystemObject, &defaultAddress, &device) || !device)
        return;
    aliveListener = ^(UInt32 n, const AudioObjectPropertyAddress *addresses) {
      (void)n;
      (void)addresses;
      UInt32 alive = 1;
      if (read_property(device, &aliveAddress, &alive) && !alive)
          enqueue(ND_SESSION_REMOVED, 0);
    };
    AudioObjectAddPropertyListenerBlock(device, &aliveAddress, dispatch_get_main_queue(), aliveListener);
    if (read_property(device, &sourceAddress, &previousSource)) {
        sourceListener = ^(UInt32 n, const AudioObjectPropertyAddress *addresses) {
          (void)n;
          (void)addresses;
          UInt32 source;
          if (!read_property(device, &sourceAddress, &source))
              return;
          /* Apple's built-in headphone data-source ID. Hardware without this
           * property remains an explicit open jack-removal measurement. */
          if (previousSource == 'hdpn' && source != previousSource)
              enqueue(ND_SESSION_REMOVED, 0);
          previousSource = source;
        };
        AudioObjectAddPropertyListenerBlock(device, &sourceAddress, dispatch_get_main_queue(), sourceListener);
    }
}
static NSDictionary *command(MPRemoteCommand *command, int kind) {
    command.enabled = YES;
    id target = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
      int64_t value = 0;
      if (kind == ND_SESSION_SEEK)
          value = (int64_t)(((MPChangePlaybackPositionCommandEvent *)event).positionTime * 1000.0);
      enqueue(kind, value);
      return MPRemoteCommandHandlerStatusSuccess;
    }];
    return @{@"command" : command, @"target" : target};
}
int nd_session_open(void) {
    __block int result = 0;
    main_queue(^{
      @try {
          if (active) {
              result = ND_E_STATE;
              return;
          }
          os_unfair_lock_lock(&lock);
          active = YES;
          count = head = 0;
          failure = 0;
          os_unfair_lock_unlock(&lock);
          [NSApplication sharedApplication];
          MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
          commandTargets = @[
              command(center.playCommand, ND_SESSION_PLAY), command(center.pauseCommand, ND_SESSION_PAUSE),
              command(center.togglePlayPauseCommand, ND_SESSION_TOGGLE),
              command(center.nextTrackCommand, ND_SESSION_NEXT),
              command(center.previousTrackCommand, ND_SESSION_PREVIOUS),
              command(center.changePlaybackPositionCommand, ND_SESSION_SEEK)
          ];
          NSNotificationCenter *notifications = [[NSWorkspace sharedWorkspace] notificationCenter];
          sleepObserver = [notifications addObserverForName:NSWorkspaceWillSleepNotification
                                                     object:nil
                                                      queue:[NSOperationQueue mainQueue]
                                                 usingBlock:^(NSNotification *note) {
                                                   (void)note;
                                                   enqueue(ND_SESSION_SUSPEND, 0);
                                                 }];
          wakeObserver = [notifications addObserverForName:NSWorkspaceDidWakeNotification
                                                    object:nil
                                                     queue:[NSOperationQueue mainQueue]
                                                usingBlock:^(NSNotification *note) {
                                                  (void)note;
                                                  enqueue(ND_SESSION_RESUME, 0);
                                                }];
          attach_device();
          defaultListener = ^(UInt32 n, const AudioObjectPropertyAddress *addresses) {
            (void)n;
            (void)addresses;
            enqueue(ND_SESSION_DEFAULT_CHANGED, 0);
            attach_device();
          };
          AudioObjectAddPropertyListenerBlock(kAudioObjectSystemObject, &defaultAddress, dispatch_get_main_queue(),
                                              defaultListener);
      } @catch (NSException *exception) {
          (void)exception;
          result = ND_E_DEVICE;
      }
    });
    return result;
}
int nd_session_publish(const char *title, const char *artist, int64_t duration, int64_t position, float rate,
                       int playing) {
    __block int result = 0;
    main_queue(^{
      @try {
          if (!active) {
              result = ND_E_STATE;
              return;
          }
          MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
          if (!title) {
              center.nowPlayingInfo = nil;
              center.playbackState = MPNowPlayingPlaybackStateStopped;
              return;
          }
          NSString *t = [NSString stringWithUTF8String:title];
          NSString *a = artist ? [NSString stringWithUTF8String:artist] : @"";
          if (!t || !a) {
              result = ND_E_ARG;
              return;
          }
          center.nowPlayingInfo = @{
              MPMediaItemPropertyTitle : t,
              MPMediaItemPropertyArtist : a,
              MPMediaItemPropertyPlaybackDuration : @(duration / 1000.0),
              MPNowPlayingInfoPropertyElapsedPlaybackTime : @(position / 1000.0),
              MPNowPlayingInfoPropertyPlaybackRate : @(playing ? rate : 0)
          };
          center.playbackState = playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
      } @catch (NSException *exception) {
          (void)exception;
          result = ND_E_DEVICE;
      }
    });
    return result;
}
int nd_session_poll(int64_t *value) {
    if (!value)
        return ND_E_ARG;
    os_unfair_lock_lock(&lock);
    int kind = failure;
    if (!kind && count) {
        kind = events[head].kind;
        *value = events[head].value;
        head = (head + 1) % 64;
        --count;
    }
    os_unfair_lock_unlock(&lock);
    return kind;
}
void nd_session_close(void) {
    main_queue(^{
      os_unfair_lock_lock(&lock);
      active = NO;
      os_unfair_lock_unlock(&lock);
      for (NSDictionary *entry in commandTargets)
          [(MPRemoteCommand *)entry[@"command"] removeTarget:entry[@"target"]];
      commandTargets = nil;
      NSNotificationCenter *notifications = [[NSWorkspace sharedWorkspace] notificationCenter];
      if (sleepObserver)
          [notifications removeObserver:sleepObserver];
      if (wakeObserver)
          [notifications removeObserver:wakeObserver];
      sleepObserver = wakeObserver = nil;
      detach_device();
      if (defaultListener)
          AudioObjectRemovePropertyListenerBlock(kAudioObjectSystemObject, &defaultAddress, dispatch_get_main_queue(),
                                                 defaultListener);
      defaultListener = nil;
      [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
      [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
    });
}
