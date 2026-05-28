# cordova-plugin-audio-simple

Unified background-reliable audio surface for Cordova. Howler-compatible JS API across Android and iOS.

- **Android:** AndroidX Media3 (ExoPlayer 1.4.x) hosted in a `MediaSessionService` foreground service.
- **iOS:** AVAudioPlayer pool with AVAudioSession lifecycle, MPNowPlayingInfo (lock-screen tile with all controls disabled), and an NSUserDefaults step-state cache. *Added in Round 25 — see ios-native-plan §2 Workstream I.*

Renamed from `cordova-plugin-exoplayer-simple` in Round 24 of the Flanerie iOS native plan; Android implementation is bytes-identical aside from the renamed top-level plugin class (`AudioSimplePlugin`).

## Why

Replaces Howler.js on Android (fragile under Doze / App Standby / OEM aggressive memory pressure) and replaces `cordova-plugin-media` on iOS (per-file `AVAudioPlayer` reallocation churn + AVAudioSession reactivation under each play).

## Coexistence with `cordova-plugin-audiofocus`

The two plugins are deliberately kept independent for rollback safety:

- **audiofocus plugin** — owns `AudioManager.requestAudioFocus` (Android), its own `AudioFocusService` foreground notification (ID 7374), and `ACTION_POWER_SAVE_MODE_CHANGED`. iOS surface shrunk in Round 25 to telemetry-only interruption observer (AVAudioSession lifecycle moved here).
- **audio-simple plugin (this)** — owns the actual playback engines (ExoPlayer + AVAudioPlayer), its own `MediaSessionService` foreground notification (ID 7375 — Android), and AVAudioSession lifecycle + MPNowPlayingInfo + step-state cache (iOS).

`AudioAttributes.handleAudioFocus = false` on every Android ExoPlayer instance — audiofocus stays single-source-of-truth for the focus *request* on Android.

## JS API (Howler-compatible subset)

```js
var p = new cordova.plugins.audio.Player(src, { loop: false });
p.on('load',       function() { ... });
p.on('play',       function() { ... });
p.on('pause',      function() { ... });
p.on('end',        function() { ... });
p.on('stop',       function() { ... });
p.on('loaderror',  function(id, err) { ... });
p.on('playerror',  function(id, err) { ... });

p.play();
p.pause();
p.stop();
p.seek(seconds);            // setter
var pos = p.seek();         // getter
p.volume(0.5);
p.fade(0, 1, 1500);
p.loop(true);
p.prewarm();                // prepare without playWhenReady — closes M4/P9 cold-load race
p.unload();

p.state();                  // 'unloaded' | 'loading' | 'loaded'
p.playing();                // bool
p.paused();                 // bool
p._src;                     // resolved file:// or http://localhost URI
```

Plugin-level:

```js
cordova.plugins.audio.releaseAll(success, error);   // walk-end teardown
cordova.plugins.audio.startService(success, error); // Android FG service warm-up
cordova.plugins.audio.ping(success, error);         // sanity check
```

iOS-only methods (added in Round 25):

```js
cordova.plugins.audio.setupNowPlaying({ title, artist, albumTitle });
cordova.plugins.audio.clearNowPlaying();
cordova.plugins.audio.setResumeSnapshot({ stepId, seekPosSec, pID });
cordova.plugins.audio.getResumeSnapshot();   // → Promise<{found, stepId, seekPosSec, pID, savedAtMs, ageMs}>
cordova.plugins.audio.clearResumeSnapshot();
```

## Native dependencies

**Android:**
- `androidx.media3:media3-exoplayer:1.4.1`
- `androidx.media3:media3-session:1.4.1`
- `androidx.media3:media3-common:1.4.1`
- `androidx.media3:media3-datasource:1.4.1`

`DefaultExtractorsFactory` covers MP3 and WAV — sufficient for the Flanerie media set.

**iOS:**
- `AVFoundation.framework` (AVAudioPlayer + AVAudioSession)
- `MediaPlayer.framework` (MPNowPlayingInfoCenter + MPRemoteCommandCenter)

## Min SDK

- Android 21 (Media3 baseline). FlanerieCordova currently targets minSdk 24 / targetSdk 36.
- iOS 13 (AVAudioSession route-change keys baseline).
