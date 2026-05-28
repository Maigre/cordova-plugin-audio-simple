package com.maigre.cordova.plugins.exoplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One ExoPlayer wrapped behind a Howler-shaped JS API.
 *
 * Lifecycle:
 *   - Constructed on the main looper from ExoPlayerPlugin.doCreate().
 *   - Receives action calls (load, play, pause, stop, seek, ...) marshalled to
 *     the main looper by ExoPlayerPlugin.
 *   - Emits JS-facing events via plugin.emit({id, event, ...}). One callback
 *     stream is shared by all players; the JS side fans events out to per-
 *     instance listeners by matching the handle id.
 *
 * Events emitted (mirrors Howler):
 *   - 'load'      — STATE_READY reached for the current media item.
 *   - 'play'      — playback actively running (isPlaying == true).
 *   - 'pause'     — playback paused (isPlaying flipped to false without a stop).
 *   - 'stop'      — explicit stop() call observed.
 *   - 'end'       — STATE_ENDED on a non-looping item.
 *   - 'loaderror' — PlaybackException before first 'load'.
 *   - 'playerror' — PlaybackException after first 'load'.
 *
 * Error payload shape (matches what classifyAudioErrorType() in player.js expects):
 *   { code: 1..4, errorCode: <Media3 int>, message: <string> }
 *
 * Looping:
 *   ExoPlayer.REPEAT_MODE_ONE for single-item loop. Seamless on MP3/WAV at
 *   typical Flanerie afterplay loop lengths (no AVAudioPlayer numberOfLoops
 *   equivalent because Media3 handles it via the timeline itself).
 */
class ExoPlayerInstance {

    final int handle;
    private final ExoPlayerPlugin plugin;
    private final Context appContext;

    private ExoPlayer player;
    private boolean released = false;
    private boolean loadEmitted = false;     // tracks first STATE_READY since last load()
    private boolean stoppedExplicitly = false;
    private String currentSrc;
    private boolean loop;
    private float volume;

    // ---- fade state ----
    // The fade runs on the main looper as a chain of postDelayed steps. A
    // monotonically increasing token lets late ticks of a cancelled fade
    // recognise that they're stale and bail out without touching the volume.
    private int fadeToken = 0;
    private static final int FADE_STEP_MS = 50;

    ExoPlayerInstance(int handle, ExoPlayerPlugin plugin, Context appContext,
                      String initialSrc, boolean loop, float volume) {
        this.handle = handle;
        this.plugin = plugin;
        this.appContext = appContext;
        this.currentSrc = initialSrc;
        this.loop = loop;
        this.volume = clampVolume(volume);
    }

    // ---------- main-looper lifecycle ----------

    /** Must be called on the main looper. */
    void buildPlayer() {
        if (player != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        player = new ExoPlayer.Builder(appContext)
                // handleAudioFocus = false — audiofocus plugin owns the focus
                // request. This wrapper observes focus changes via a separate
                // listener registered by ExoPlayerPlugin (Step 6).
                .setAudioAttributes(attrs, false)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player.setVolume(volume);
        player.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.addListener(listener);
    }

    /** Must be called on the main looper. */
    void load(String src) {
        if (released || player == null) return;
        currentSrc = src;
        loadEmitted = false;
        stoppedExplicitly = false;
        Uri uri = UriResolver.resolve(src);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        // Don't auto-play here — JS layer decides via explicit play() / prewarm().
    }

    /**
     * Pre-warm = prepare without setting playWhenReady. Replaces the JS-side
     * A8/A8b workarounds for the Howler cold-load race; with ExoPlayer the
     * pattern is documented-safe (setPlayWhenReady(true) before STATE_READY
     * is allowed and plays as soon as the item is ready).
     *
     * Must be called on the main looper.
     */
    void prewarm() {
        if (released || player == null) return;
        if (currentSrc == null) return;
        // If we haven't loaded yet or playback was previously stopped, set the
        // media item again and prepare. Otherwise this is a no-op — ExoPlayer
        // is already prepared.
        int s = player.getPlaybackState();
        if (s == Player.STATE_IDLE) {
            loadEmitted = false;
            Uri uri = UriResolver.resolve(currentSrc);
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
        }
        player.setPlayWhenReady(false);
    }

    /** Must be called on the main looper. */
    void play() {
        if (released || player == null) return;
        stoppedExplicitly = false;
        // ExoPlayer documents setPlayWhenReady(true) as safe before STATE_READY
        // — playback starts the moment the item is ready. This eliminates the
        // Howler M4/P9 cold-load race structurally.
        int s = player.getPlaybackState();
        if (s == Player.STATE_IDLE && currentSrc != null) {
            // The player was stopped or never loaded — re-prepare before playing.
            loadEmitted = false;
            Uri uri = UriResolver.resolve(currentSrc);
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
        }
        player.setPlayWhenReady(true);
    }

    /** Must be called on the main looper. */
    void pause() {
        if (released || player == null) return;
        player.setPlayWhenReady(false);
    }

    /** Must be called on the main looper. */
    void stop() {
        if (released || player == null) return;
        stoppedExplicitly = true;
        player.setPlayWhenReady(false);
        player.stop();
        emit("stop", null);
    }

    /** Must be called on the main looper. */
    void seek(double seconds) {
        if (released || player == null) return;
        long ms = Math.max(0L, (long) Math.round(seconds * 1000.0));
        player.seekTo(ms);
    }

    /** Returns current position in seconds. Safe to call from any thread. */
    double getPosition() {
        if (released || player == null) return 0.0;
        // ExoPlayer.getCurrentPosition() must be called on its application
        // looper (the main looper, which is where everything else runs).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            long ms = player.getCurrentPosition();
            return ms < 0 ? 0.0 : ms / 1000.0;
        }
        // Fallback: dispatch and block briefly. Used by JS-driven polling that
        // arrives on the cordova thread.
        final double[] out = new double[1];
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        plugin.runOnMain(() -> {
            if (player != null) {
                long ms = player.getCurrentPosition();
                out[0] = ms < 0 ? 0.0 : ms / 1000.0;
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 50;
            while (!done[0]) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return out[0];
    }

    /** Must be called on the main looper. */
    void setVolume(float v) {
        // Cancel any in-flight fade — a bare setVolume() is an authoritative
        // override that should not race with a half-finished ramp.
        fadeToken++;
        this.volume = clampVolume(v);
        if (released || player == null) return;
        player.setVolume(this.volume);
    }

    /**
     * Linear volume ramp from {@code from} to {@code to} over
     * {@code durationMs}, stepping every {@link #FADE_STEP_MS} ms on the main
     * looper. Cancels any previous fade.
     *
     * Must be called on the main looper.
     */
    void fade(float from, float to, int durationMs) {
        if (released || player == null) return;
        from = clampVolume(from);
        to   = clampVolume(to);

        // Cancel previous fade.
        final int myToken = ++fadeToken;

        if (durationMs <= 0) {
            volume = to;
            player.setVolume(to);
            return;
        }

        final int totalSteps = Math.max(1, durationMs / FADE_STEP_MS);
        final float delta = (to - from) / (float) totalSteps;
        final float startVol = from;
        final float endVol = to;

        // Set the first frame immediately so the ramp starts visibly at `from`.
        player.setVolume(startVol);
        volume = startVol;

        final Handler h = new Handler(Looper.getMainLooper());
        final int[] stepRef = new int[]{0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (released || player == null) return;
                if (myToken != fadeToken) return;       // a newer fade or setVolume superseded us
                stepRef[0]++;
                float v;
                if (stepRef[0] >= totalSteps) v = endVol;
                else                          v = startVol + delta * stepRef[0];
                v = clampVolume(v);
                player.setVolume(v);
                volume = v;
                if (stepRef[0] < totalSteps) h.postDelayed(this, FADE_STEP_MS);
            }
        };
        h.postDelayed(tick, FADE_STEP_MS);
    }

    float getVolume() { return volume; }

    /** Must be called on the main looper. */
    void setLoop(boolean loop) {
        this.loop = loop;
        if (released || player == null) return;
        player.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    /** Must be called on the main looper. */
    void release() {
        if (released) return;
        released = true;
        fadeToken++;   // any in-flight fade tick will observe the mismatch and bail
        if (player != null) {
            try { player.removeListener(listener); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    boolean isReleased() { return released; }

    ExoPlayer getPlayer() { return player; }

    // ---------- Player.Listener ----------

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (released) return;
            if (playbackState == Player.STATE_READY && !loadEmitted) {
                loadEmitted = true;
                emit("load", null);
            } else if (playbackState == Player.STATE_ENDED) {
                if (!loop) emit("end", null);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (released) return;
            if (isPlaying) {
                emit("play", null);
            } else {
                // Distinguish stop() (already emitted by our stop() helper)
                // from a pause caused by setPlayWhenReady(false) or by reaching
                // a paused buffering state. Emit 'pause' only when we haven't
                // explicitly stopped.
                if (!stoppedExplicitly && player != null) {
                    int s = player.getPlaybackState();
                    if (s == Player.STATE_READY || s == Player.STATE_BUFFERING) {
                        emit("pause", null);
                    }
                }
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (released) return;
            JSONObject payload = new JSONObject();
            try {
                payload.put("errorCode", error.errorCode);
                payload.put("message", String.valueOf(error.getMessage()));
                payload.put("code", mapToHowlerCode(error.errorCode));
            } catch (JSONException ignored) {}
            // Howler convention: loaderror before any play has started, else playerror.
            if (!loadEmitted) emit("loaderror", payload);
            else              emit("playerror", payload);
        }
    };

    // ---------- helpers ----------

    /**
     * Map Media3 {@link PlaybackException} error codes to the 1..4 numeric
     * convention used by Howler/MediaError so the existing
     * classifyAudioErrorType() in player.js produces the same error_type enum
     * values without changes.
     *
     *   1 = aborted          (user-initiated — never used here)
     *   2 = network
     *   3 = decode_failed
     *   4 = src_unsupported / not_found / unknown
     */
    private int mapToHowlerCode(int errorCode) {
        if (errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                && errorCode <= PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
            // The IO range covers network errors AND file-not-found / permission.
            // File-not-found is reported as ERROR_CODE_IO_FILE_NOT_FOUND; everything
            // else in this band is network/connection class.
            if (errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                    || errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
                return 4; // → 'not_found' via message heuristic in JS classifier
            }
            return 2; // network
        }
        if (errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                && errorCode <= PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return 3; // decode_failed (parsing failures)
        }
        if (errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                && errorCode <= PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            return 3; // decode_failed
        }
        return 4; // src_unsupported / unknown
    }

    private void emit(String event, @Nullable JSONObject extra) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", handle);
            o.put("event", event);
            if (extra != null) {
                if (extra.has("code"))      o.put("code",      extra.opt("code"));
                if (extra.has("errorCode")) o.put("errorCode", extra.opt("errorCode"));
                if (extra.has("message"))   o.put("message",   extra.opt("message"));
                // Wrap message+code into 'error' for the JS shim's loaderror/playerror
                // dispatch (it passes evt.error as the second listener argument,
                // mirroring Howler's (id, error) callback signature).
                o.put("error", extra);
            }
            plugin.emit(o);
        } catch (JSONException ignored) {}
    }

    private static float clampVolume(float v) {
        if (Float.isNaN(v)) return 1f;
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
