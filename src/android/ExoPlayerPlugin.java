package com.maigre.cordova.plugins.exoplayer;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * cordova-plugin-exoplayer-simple — bridge entry point.
 *
 * Owns the per-player SparseArray and the single long-lived "events" callback
 * context. ExoPlayerInstance objects post events back via {@link #emit}.
 *
 * Coexists with cordova-plugin-audiofocus: audio focus is requested by that
 * plugin; this one only observes focus changes (Phase 3 of the rollout).
 */
public class ExoPlayerPlugin extends CordovaPlugin {

    // Static reference so the MediaSessionService can find the active plugin
    // instance (mirrors the AudioFocus.instance pattern used for AF-3 service
    // restart). Null when no Cordova activity is bound.
    static ExoPlayerPlugin instance = null;

    private final SparseArray<ExoPlayerInstance> players = new SparseArray<>();
    private int nextHandle = 1;

    private CallbackContext eventsCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Tracks whether we've asked the OS to start ExoPlayerService. Idempotent
    // — Android's startForegroundService is safe to call repeatedly, but
    // skipping no-op calls keeps the log noise down.
    private boolean serviceStarted = false;

    @Override
    public void pluginInitialize() {
        super.pluginInitialize();
        instance = this;
        attachAudioFocusBridge();
    }

    /**
     * Reflectively register a focus listener with cordova-plugin-audiofocus so
     * AUDIOFOCUS_LOSS / LOSS_TRANSIENT pause our ExoPlayer instances without a
     * JS roundtrip. Fails soft when:
     *   - audiofocus is not installed (other plugins or apps using this one).
     *   - audiofocus < 1.7.0 (the ExtraFocusListener interface didn't exist).
     *
     * In either failure mode the JS-side cordova.plugins.audiofocus.onFocusChange
     * path remains the only handler, identical to pre-Step 6 behaviour.
     */
    private void attachAudioFocusBridge() {
        try {
            Class<?> afc = Class.forName("com.maigre.cordova.plugins.AudioFocus");
            Class<?> iface = Class.forName("com.maigre.cordova.plugins.AudioFocus$ExtraFocusListener");
            Method setter = afc.getMethod("setExtraFocusListener", iface);
            InvocationHandler handler = (proxy, method, args) -> {
                if ("onAudioFocusChange".equals(method.getName()) && args != null && args.length == 1) {
                    onAudioFocusChange((Integer) args[0]);
                }
                return null;
            };
            Object proxy = Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class[]{iface}, handler);
            setter.invoke(null, proxy);
        } catch (Throwable ignored) {
            // audiofocus absent or older version — native fast-pause not
            // available. JS-side focus handler still owns the slow path.
        }
    }

    /**
     * Invoked on the audiofocus-plugin AudioManager listener thread. Pause every
     * active ExoPlayer instance on LOSS/LOSS_TRANSIENT. Skip ducking (JS still
     * owns DUCKED_PLAYERS bookkeeping at player.js — double-ducking would
     * misrecord the "original" volume). Skip auto-resume on GAIN (JS owns the
     * PAUSED_PLAYERS queue — only the right subset should resume).
     */
    private void onAudioFocusChange(int focusChange) {
        if (focusChange != AudioManager.AUDIOFOCUS_LOSS
                && focusChange != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            return;
        }
        runOnMain(() -> {
            for (int i = 0; i < players.size(); i++) {
                ExoPlayerInstance inst = players.valueAt(i);
                if (inst != null && !inst.isReleased()) inst.pause();
            }
        });
    }

    @Override
    public void onDestroy() {
        // Release every active player to avoid leaking ExoPlayer instances
        // across activity recreation (rotation / config change). The service
        // outlives the activity, but per-player handles are tied to JS state
        // that does not survive a reload.
        releaseAllInternal();
        detachAudioFocusBridge();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void detachAudioFocusBridge() {
        try {
            Class<?> afc = Class.forName("com.maigre.cordova.plugins.AudioFocus");
            Class<?> iface = Class.forName("com.maigre.cordova.plugins.AudioFocus$ExtraFocusListener");
            Method setter = afc.getMethod("setExtraFocusListener", iface);
            setter.invoke(null, (Object) null);
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) throws JSONException {
        switch (action) {
            case "ping":             return doPing(cb);
            case "subscribeEvents":  return doSubscribeEvents(cb);
            case "releaseAll":       return doReleaseAll(cb);
            case "startService":     return doStartService(cb);
            case "create":           return doCreate(args, cb);
            case "load":             return doPerPlayer(args, cb, "load");
            case "prewarm":          return doPerPlayer(args, cb, "prewarm");
            case "play":             return doPerPlayer(args, cb, "play");
            case "pause":            return doPerPlayer(args, cb, "pause");
            case "stop":             return doPerPlayer(args, cb, "stop");
            case "seek":             return doPerPlayer(args, cb, "seek");
            case "getPosition":      return doPerPlayer(args, cb, "getPosition");
            case "setVolume":        return doPerPlayer(args, cb, "setVolume");
            case "fade":             return doPerPlayer(args, cb, "fade");
            case "setLoop":          return doPerPlayer(args, cb, "setLoop");
            case "unload":           return doPerPlayer(args, cb, "unload");
            default:                 return false;
        }
    }

    // ---------- per-player dispatch ----------

    /**
     * Creates a new ExoPlayer wrapped in an {@link ExoPlayerInstance}. The
     * first create() starts the foreground service so the process is held
     * alive before any audio is requested.
     *
     * Expected args[0] = { src: string, loop: bool, volume: number }
     */
    private boolean doCreate(JSONArray args, CallbackContext cb) {
        ensureServiceStarted();
        final JSONObject opts;
        try { opts = args.getJSONObject(0); }
        catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }

        final String src    = opts.optString("src", null);
        final boolean loop  = opts.optBoolean("loop", false);
        final double volume = opts.optDouble("volume", 1.0);
        final int handle    = nextHandle++;

        runOnMain(() -> {
            ExoPlayerInstance inst = new ExoPlayerInstance(
                    handle, this,
                    this.cordova.getActivity().getApplicationContext(),
                    src, loop, (float) volume);
            inst.buildPlayer();
            players.put(handle, inst);
            cb.success(handle);
        });
        return true;
    }

    private boolean doPerPlayer(JSONArray args, CallbackContext cb, String op) {
        final int handle;
        try { handle = args.getInt(0); }
        catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }

        final ExoPlayerInstance inst = players.get(handle);
        if (inst == null) { cb.error("no_such_handle:" + handle); return true; }

        switch (op) {
            case "load": {
                final String src;
                try { src = args.getString(1); }
                catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }
                runOnMain(() -> { inst.load(src); cb.success(); });
                return true;
            }
            case "prewarm":
                runOnMain(() -> { inst.prewarm(); cb.success(); });
                return true;
            case "play":
                runOnMain(() -> { inst.play(); cb.success(); });
                return true;
            case "pause":
                runOnMain(() -> { inst.pause(); cb.success(); });
                return true;
            case "stop":
                runOnMain(() -> { inst.stop(); cb.success(); });
                return true;
            case "seek": {
                final double sec;
                try { sec = args.getDouble(1); }
                catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }
                runOnMain(() -> { inst.seek(sec); cb.success(); });
                return true;
            }
            case "getPosition":
                runOnMain(() -> cb.success((float) inst.getPosition()));
                return true;
            case "setVolume": {
                final double v;
                try { v = args.getDouble(1); }
                catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }
                runOnMain(() -> { inst.setVolume((float) v); cb.success(); });
                return true;
            }
            case "fade": {
                final double from, to;
                final int durationMs;
                try {
                    from = args.getDouble(1);
                    to = args.getDouble(2);
                    durationMs = args.getInt(3);
                } catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }
                runOnMain(() -> { inst.fade((float) from, (float) to, durationMs); cb.success(); });
                return true;
            }
            case "setLoop": {
                final boolean b;
                try { b = args.getBoolean(1); }
                catch (JSONException e) { cb.error("bad_args:" + e.getMessage()); return true; }
                runOnMain(() -> { inst.setLoop(b); cb.success(); });
                return true;
            }
            case "unload":
                runOnMain(() -> {
                    inst.release();
                    players.remove(handle);
                    cb.success();
                });
                return true;
            default:
                cb.error("unknown_op:" + op);
                return true;
        }
    }

    // ---------- Plugin-level actions ----------

    private boolean doPing(CallbackContext cb) {
        try {
            JSONObject info = new JSONObject();
            info.put("plugin", "cordova-plugin-exoplayer-simple");
            info.put("version", "0.1.0");
            info.put("media3", "1.4.1");
            cb.success(info);
        } catch (JSONException e) {
            cb.error(e.getMessage());
        }
        return true;
    }

    /**
     * Long-lived event channel. JS calls this once on first Player()
     * construction. Subsequent emit() calls fan out through this callback.
     */
    private boolean doSubscribeEvents(CallbackContext cb) {
        this.eventsCallback = cb;
        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        cb.sendPluginResult(result);
        return true;
    }

    private boolean doReleaseAll(CallbackContext cb) {
        releaseAllInternal();
        stopServiceIfStarted();
        cb.success();
        return true;
    }

    private boolean doStartService(CallbackContext cb) {
        ensureServiceStarted();
        cb.success();
        return true;
    }

    private void releaseAllInternal() {
        // Tear down every player on the main looper — ExoPlayer must be
        // released from its construction thread.
        runOnMain(() -> {
            for (int i = 0; i < players.size(); i++) {
                ExoPlayerInstance inst = players.valueAt(i);
                if (inst != null) inst.release();
            }
            players.clear();
        });
    }

    private void ensureServiceStarted() {
        if (serviceStarted) return;
        serviceStarted = true;
        ExoPlayerService.start(this.cordova.getActivity().getApplicationContext());
    }

    private void stopServiceIfStarted() {
        if (!serviceStarted) return;
        serviceStarted = false;
        ExoPlayerService.stop(this.cordova.getActivity().getApplicationContext());
    }

    // ---------- Helpers used by ExoPlayerInstance / ExoPlayerService ----------

    void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    /**
     * Push an event to JS. Safe to call from any thread.
     */
    void emit(JSONObject event) {
        if (eventsCallback == null || event == null) return;
        PluginResult result = new PluginResult(PluginResult.Status.OK, event);
        result.setKeepCallback(true);
        eventsCallback.sendPluginResult(result);
    }
}
