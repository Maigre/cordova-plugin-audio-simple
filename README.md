# cordova-plugin-exoplayer-simple

Cordova plugin wrapping AndroidX Media3 (ExoPlayer 1.4.x) with a Howler-compatible JS API surface. Android only — iOS keeps using `cordova-plugin-media` (the existing `NativeMediaPlayer` wrapper in FlanerieAudioMap).

## Why

Replaces Howler.js on Android in the Flanerie field-test fleet. Howler runs inside the WebView and is fragile under Doze, App Standby, and OEM aggressive memory pressure. ExoPlayer runs natively, hosted in a `MediaSessionService` foreground service, and survives screen lock + GPS-callback-triggered playback reliably.

## Coexistence with `cordova-plugin-audiofocus`

The two plugins are deliberately kept independent for rollback safety:

- **audiofocus plugin** — owns `AudioManager.requestAudioFocus`, its own `AudioFocusService` foreground notification (ID 7374), and `ACTION_POWER_SAVE_MODE_CHANGED`.
- **exoplayer plugin (this)** — owns ExoPlayer instances, `MediaSessionService` foreground notification (ID 7375), and registers its own `OnAudioFocusChangeListener` on the same `AudioManager` so it can pause/duck natively without a JS roundtrip.

`AudioAttributes.handleAudioFocus = false` on every ExoPlayer instance — audiofocus stays single-source-of-truth for the focus *request*.

## JS API (Howler-compatible subset)

```js
var p = new cordova.plugins.exoplayer.Player(src, { loop: false });
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
p.prewarm();                // setMediaItem + prepare, no playWhenReady
p.unload();

p.state();                  // 'unloaded' | 'loading' | 'loaded'
p.playing();                // bool
p.paused();                 // bool
p._src;                     // resolved file:// or http://localhost URI
```

Plugin-level:

```js
cordova.plugins.exoplayer.releaseAll(success, error);  // walk-end teardown
cordova.plugins.exoplayer.ping(success, error);        // sanity check
```

## Native dependencies

- `androidx.media3:media3-exoplayer:1.4.1`
- `androidx.media3:media3-session:1.4.1`
- `androidx.media3:media3-common:1.4.1`
- `androidx.media3:media3-datasource:1.4.1`

`DefaultExtractorsFactory` covers MP3 and WAV — sufficient for the Flanerie media set.

## Min SDK

21 (Media3 baseline). FlanerieCordova currently targets minSdk 24 / targetSdk 36.
