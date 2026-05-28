// cordova-plugin-audio-simple — JS shim.
//
// Renamed from cordova-plugin-exoplayer-simple in Round 24 (Workstream I.A in
// mobile-audit.md). Android implementation is unchanged (AndroidX Media3 /
// ExoPlayer inside a MediaSessionService FG). iOS implementation landed in
// Round 25 (Workstream I.B).
//
// Exposes:
//   cordova.plugins.audio.Player(src, opts)  — per-instance Howler-compat wrapper
//   cordova.plugins.audio.releaseAll(ok, ko) — walk-end teardown (A1, A3)
//   cordova.plugins.audio.ping(ok, ko)       — sanity check
//
// API mirrors the Howler subset used by FlanerieAudioMap's PlayerSimple
// (state / playing / paused / play / pause / stop / seek / volume / fade /
// loop / unload / once / on / _src, plus prewarm()).

var exec = require('cordova/exec');

var SERVICE = 'AudioSimple';

// Native bridge actions
var ACTION_CREATE      = 'create';
var ACTION_LOAD        = 'load';
var ACTION_PREWARM     = 'prewarm';
var ACTION_PLAY        = 'play';
var ACTION_PAUSE       = 'pause';
var ACTION_STOP        = 'stop';
var ACTION_SEEK_GET    = 'getPosition';
var ACTION_SEEK_SET    = 'seek';
var ACTION_VOLUME      = 'setVolume';
var ACTION_FADE        = 'fade';
var ACTION_LOOP        = 'setLoop';
var ACTION_UNLOAD      = 'unload';
var ACTION_RELEASE_ALL = 'releaseAll';
var ACTION_PING        = 'ping';
var ACTION_EVENTS      = 'subscribeEvents';

// Per-instance state lives in JS; native side keeps an int handle.
var _nextLocalId = 1;
var _byHandle = {};   // playerId (int from native) → Player instance
var _eventsSubscribed = false;

function _ensureEventsSubscribed() {
    if (_eventsSubscribed) return;
    _eventsSubscribed = true;
    exec(_dispatchEvent, _onEventStreamError, SERVICE, ACTION_EVENTS, []);
}

function _onEventStreamError(err) {
    // Android: native MediaSessionService streams events back through this
    // subscription. iOS (added in Round 25) does the same via its own
    // AudioSimplePlayer event channel. Browser leaves the stream un-subscribed.
    _eventsSubscribed = false;
    if (typeof console !== 'undefined') console.warn('[AudioSimple] event stream unavailable:', err);
}

function _dispatchEvent(evt) {
    if (!evt || typeof evt !== 'object') return;
    var p = _byHandle[evt.id];
    if (!p) return;
    var name = evt.event;
    if (!name) return;

    // Translate native event → JS state mirrors + listener fan-out.
    if (name === 'load') {
        p._loaded = true;
        p._loading = false;
        p._loadError = false;
    }
    else if (name === 'play') {
        p._playing = true;
        p._loaded = true;
        p._startPositionPoll();
    }
    else if (name === 'pause') {
        p._playing = false;
        p._stopPositionPoll();
    }
    else if (name === 'stop') {
        p._playing = false;
        p._stopPositionPoll();
        p._cachedPos = 0;
    }
    else if (name === 'end') {
        // Non-looping playback finished naturally.
        p._playing = false;
        p._stopPositionPoll();
    }
    else if (name === 'loaderror') {
        p._loaded = false;
        p._loading = false;
        p._loadError = true;
        p._playing = false;
        p._stopPositionPoll();
    }
    else if (name === 'playerror') {
        p._playing = false;
        p._loadError = true;
        p._stopPositionPoll();
    }

    var listeners = p._listeners[name];
    if (!listeners || listeners.length === 0) return;
    // Copy first — listeners may unsubscribe themselves (once()).
    var snapshot = listeners.slice();
    for (var i = 0; i < snapshot.length; i++) {
        try {
            // Howler shape: (soundId, error) for *error events, (soundId) otherwise.
            // We don't track per-sound IDs; pass 0 for parity, then error payload.
            if (name === 'loaderror' || name === 'playerror') snapshot[i](0, evt.error || null);
            else snapshot[i](p._src);
        } catch (e) {
            if (typeof console !== 'undefined') console.error('[AudioSimple] listener error:', name, e);
        }
    }
}

function Player(src, opts) {
    if (!(this instanceof Player)) return new Player(src, opts);
    opts = opts || {};

    this._src         = src || null;
    this._loop        = !!opts.loop;
    this._volume      = (typeof opts.volume === 'number') ? opts.volume : 1.0;
    this._localId     = _nextLocalId++;
    this._handle      = null;   // assigned by native create()
    this._creating    = null;   // Promise
    this._loaded      = false;
    this._loading     = false;
    this._loadError   = false;
    this._playing     = false;
    this._unloaded    = false;
    this._listeners   = {};
    this._cachedPos   = 0;      // last known position in seconds (for seek() getter)
    this._positionPoll = null;  // setInterval handle for the 250 ms position cache update

    // Howler compat — PlayerSimple reads these directly.
    this.__isPrimingForBackground = false;
    this.__backgroundPrimed = false;

    _ensureEventsSubscribed();
    this._create();
}

Player.prototype._create = function() {
    var self = this;
    if (self._unloaded || self._creating || self._handle !== null) return;
    // Snapshot opts we send to native — used to detect whether JS mutated
    // _volume / _loop in the sync window between constructor return and the
    // async create() callback resolving.
    var sentVolume = self._volume;
    var sentLoop = self._loop;
    self._creating = new Promise(function(resolve, reject) {
        exec(function(handle) {
            self._handle = handle;
            _byHandle[handle] = self;
            self._creating = null;
            // Issue the deferred load now that the handle exists.
            if (self._src) self._load();
            // If JS mutated volume / loop between constructor and now, push the
            // current values so native catches up.
            if (self._volume !== sentVolume) {
                exec(null, null, SERVICE, ACTION_VOLUME, [handle, self._volume]);
            }
            if (self._loop !== sentLoop) {
                exec(null, null, SERVICE, ACTION_LOOP, [handle, self._loop]);
            }
            resolve(handle);
        }, function(err) {
            self._creating = null;
            reject(err);
        }, SERVICE, ACTION_CREATE, [{
            src: self._src,
            loop: sentLoop,
            volume: sentVolume,
        }]);
    });
    return self._creating;
};

Player.prototype._load = function() {
    var self = this;
    if (self._unloaded || self._handle === null || !self._src) return;
    self._loading = true;
    self._loaded = false;
    self._loadError = false;
    exec(null, function(err) {
        self._loading = false;
        self._loadError = true;
        _dispatchEvent({ id: self._handle, event: 'loaderror', error: err });
    }, SERVICE, ACTION_LOAD, [self._handle, self._src]);
};

// ---------- Howler-shaped API ----------

Player.prototype.state = function() {
    if (this._unloaded) return 'unloaded';
    if (this._loaded) return 'loaded';
    if (this._loading) return 'loading';
    return 'unloaded';
};

Player.prototype.playing = function() { return !!this._playing; };
Player.prototype.paused  = function() { return this._loaded && !this._playing; };

Player.prototype.play = function() {
    var self = this;
    if (self._unloaded) return self;
    self._playing = true;   // optimistic — native 'play' event will confirm
    var send = function() {
        if (self._handle === null) return;
        exec(null, null, SERVICE, ACTION_PLAY, [self._handle]);
    };
    if (self._handle === null && self._creating) self._creating.then(send);
    else send();
    return self;
};

Player.prototype.pause = function() {
    if (this._unloaded || this._handle === null) return this;
    exec(null, null, SERVICE, ACTION_PAUSE, [this._handle]);
    return this;
};

Player.prototype.stop = function() {
    if (this._unloaded || this._handle === null) return this;
    exec(null, null, SERVICE, ACTION_STOP, [this._handle]);
    return this;
};

Player.prototype.seek = function(seconds) {
    if (seconds === undefined) {
        // Synchronous-style getter — return last-known cached position.
        // PlayerSimple polls every 250ms (matches NativeMediaPlayer), so the
        // cached value never drifts more than a poll interval.
        return this._cachedPos || 0;
    }
    if (this._unloaded || this._handle === null) return this;
    this._cachedPos = seconds;
    exec(null, null, SERVICE, ACTION_SEEK_SET, [this._handle, seconds]);
    return this;
};

Player.prototype.volume = function(v) {
    if (v === undefined) return this._volume;
    this._volume = Math.max(0, Math.min(1, v));
    if (!this._unloaded && this._handle !== null) {
        exec(null, null, SERVICE, ACTION_VOLUME, [this._handle, this._volume]);
    }
    return this._volume;
};

Player.prototype.fade = function(from, to, durationMs) {
    if (this._unloaded || this._handle === null) return this;
    from = Math.max(0, Math.min(1, from));
    to   = Math.max(0, Math.min(1, to));
    this._volume = to;
    exec(null, null, SERVICE, ACTION_FADE, [this._handle, from, to, durationMs|0]);
    return this;
};

Player.prototype.loop = function(value) {
    if (value === undefined) return this._loop;
    this._loop = !!value;
    if (!this._unloaded && this._handle !== null) {
        exec(null, null, SERVICE, ACTION_LOOP, [this._handle, this._loop]);
    }
    return this._loop;
};

Player.prototype.prewarm = function() {
    var self = this;
    var send = function() {
        if (self._handle === null) return;
        exec(null, null, SERVICE, ACTION_PREWARM, [self._handle]);
    };
    if (self._handle === null && self._creating) self._creating.then(send);
    else send();
    return self;
};

// ---------- position polling (mirrors NativeMediaPlayer at player.js:396-405)
//
// PlayerSimple's seek() getter and snapshotVoicePosition() expect a synchronous
// position read. We cache the value in _cachedPos and refresh it every 250 ms
// while the underlying ExoPlayer is actually playing. Stops on pause/stop/end/
// error so an idle player generates no native traffic.

Player.prototype._startPositionPoll = function() {
    if (this._positionPoll) return;
    var self = this;
    self._positionPoll = setInterval(function() {
        if (self._unloaded || self._handle === null) {
            self._stopPositionPoll();
            return;
        }
        exec(function(pos) {
            if (typeof pos === 'number' && !isNaN(pos) && pos >= 0) self._cachedPos = pos;
        }, function() { /* ignore errors — keep last cached value */ },
        SERVICE, ACTION_SEEK_GET, [self._handle]);
    }, 250);
};

Player.prototype._stopPositionPoll = function() {
    if (!this._positionPoll) return;
    clearInterval(this._positionPoll);
    this._positionPoll = null;
};

Player.prototype.unload = function() {
    if (this._unloaded) return;
    this._unloaded = true;
    this._loaded = false;
    this._playing = false;
    this._stopPositionPoll();
    var h = this._handle;
    if (h !== null) {
        delete _byHandle[h];
        this._handle = null;
        exec(null, null, SERVICE, ACTION_UNLOAD, [h]);
    }
    this._listeners = {};
};

// ---------- EventEmitter (Howler subset) ----------

Player.prototype.on = function(name, fn) {
    if (typeof fn !== 'function') return this;
    if (!this._listeners[name]) this._listeners[name] = [];
    this._listeners[name].push(fn);
    return this;
};

Player.prototype.once = function(name, fn) {
    var self = this;
    var wrap = function() {
        self.off(name, wrap);
        fn.apply(null, arguments);
    };
    return self.on(name, wrap);
};

Player.prototype.off = function(name, fn) {
    if (!this._listeners[name]) return this;
    if (fn === undefined) { this._listeners[name] = []; return this; }
    this._listeners[name] = this._listeners[name].filter(function(x) { return x !== fn && x !== fn.__wrap; });
    return this;
};

// ---------- Plugin-level ----------

function releaseAll(success, error) {
    // Drop all JS-side handles too — native side will tear down its SparseArray.
    var keys = Object.keys(_byHandle);
    for (var i = 0; i < keys.length; i++) {
        var p = _byHandle[keys[i]];
        if (p) {
            p._stopPositionPoll();
            p._unloaded = true;
            p._loaded = false;
            p._playing = false;
            p._handle = null;
            p._listeners = {};
        }
    }
    _byHandle = {};
    exec(success || function(){}, error || function(){}, SERVICE, ACTION_RELEASE_ALL, []);
}

function ping(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, ACTION_PING, []);
}

function startService(success, error) {
    // Idempotent. Useful at parcours entry as a belt-and-suspenders companion
    // to cordova.plugins.audiofocus.startKeepalive() so the ExoPlayer FG
    // service is up before the first GPS-triggered step audio fires, even
    // before any per-player ExoPlayer instance has been created.
    // iOS no-ops — there is no FG service to start.
    exec(success || function(){}, error || function(){}, SERVICE, 'startService', []);
}

// ---------- iOS-only surface (added in Round 25, Workstream I.B) ------------
//
// All methods below succeed on iOS and errback on Android (action not
// registered). The webapp gates calls on PLATFORM === 'ios'. They were
// migrated here from cordova-plugin-audiofocus@1.8.0 (Round 21/22 surface)
// because the audio engine should own AVAudioSession lifecycle, the
// lock-screen tile, and the resume snapshot — keeping audiofocus iOS
// strictly an interruption-observer-as-telemetry plugin.

// AVAudioSession lifecycle. activateSession is idempotent.
function activateSession(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'activateSession', []);
}

function deactivateSession(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'deactivateSession', []);
}

// Equivalent to deactivateSession + notifyOthers=YES. Kept as a separate
// action because the audiofocus 1.8.0 JS API named it releaseSession; the
// webapp migrating from audiofocus to audio keeps the verb the same.
function releaseSession(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'releaseSession', []);
}

// A2 audio-engine-reset path: deactivate + brief settle + reactivate.
// Clears the iOS "fail once stay poisoned" AVAudioSession state observed in
// the GIVORS field test (M3 silent-audio-on-rearm) without losing session
// ownership.
function resetSession(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'resetSession', []);
}

// F-A2 telemetry parity with audiofocus's getAudioSessionState. Returns a
// Promise resolving to {outputVolume, currentPort, currentPortName,
// currentCategory, secondaryAudioShouldBeSilenced, sessionActive, nowPlayingActive}.
function getSessionState() {
    return new Promise(function(resolve, reject) {
        exec(resolve, reject, SERVICE, 'getSessionState', []);
    });
}

// MPNowPlayingInfoCenter + MPRemoteCommandCenter (R22 migration). Lock-screen
// tile with all remote-command center commands disabled. Hardware volume
// buttons remain functional (system-level, not overridable).
// options shape: { title: String, artist: String, albumTitle: String } — all optional.
function setupNowPlaying(options, success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'setupNowPlaying', [options || {}]);
}

function clearNowPlaying(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'clearNowPlaying', []);
}

// NSUserDefaults step-state cache (R21 migration). parcours.store() dual-writes
// resumeStepVoicePos here; parcours.restore() reads it on cold relaunch to
// recover when the WKWebView's localStorage was evicted.
// snapshot shape: { stepId: Number, seekPosSec: Number, pID: String }
function setResumeSnapshot(snapshot, success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'setResumeSnapshot', [snapshot || {}]);
}

// Returns a Promise resolving to:
//   { found: Bool, stepId: Number|null, seekPosSec: Number|null,
//     pID: String|null, savedAtMs: Number|null, ageMs: Number }
function getResumeSnapshot() {
    return new Promise(function(resolve, reject) {
        exec(resolve, reject, SERVICE, 'getResumeSnapshot', []);
    });
}

function clearResumeSnapshot(success, error) {
    exec(success || function(){}, error || function(){}, SERVICE, 'clearResumeSnapshot', []);
}

module.exports = {
    Player: Player,
    releaseAll: releaseAll,
    startService: startService,
    ping: ping,
    // iOS-only surface
    activateSession: activateSession,
    deactivateSession: deactivateSession,
    releaseSession: releaseSession,
    resetSession: resetSession,
    getSessionState: getSessionState,
    setupNowPlaying: setupNowPlaying,
    clearNowPlaying: clearNowPlaying,
    setResumeSnapshot: setResumeSnapshot,
    getResumeSnapshot: getResumeSnapshot,
    clearResumeSnapshot: clearResumeSnapshot,
};
