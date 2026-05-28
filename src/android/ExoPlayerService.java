package com.maigre.cordova.plugins.exoplayer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.RawResourceDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Foreground service hosting the persistent MediaSession.
 *
 * Architecture (locked 2026-05-27):
 *   - ONE persistent silent ExoPlayer holds the MediaSession for the whole walk.
 *   - Its only job is to keep PlaybackState perpetually PLAYING so OEMs trust
 *     the process and don't kill it under memory pressure.
 *   - Real audio (voice, afterplay, zones, globals) runs on OTHER ExoPlayer
 *     instances created via the plugin's per-Player JS API. Those instances
 *     are NOT bound to the MediaSession — they live in the same process and
 *     ride on this service's FG status.
 *
 * Coexistence with cordova-plugin-audiofocus:
 *   - audiofocus owns AudioFocusService (notification ID 7374) + the
 *     AudioManager focus request.
 *   - this service owns notification ID 7375. Two independent FG services in
 *     the same process — two notifications in the shade, max reliability.
 */
public class ExoPlayerService extends MediaSessionService {

    static final int NOTIFICATION_ID = 7375;
    static final String CHANNEL_ID = "flanerie_exoplayer";

    private ExoPlayer silentPlayer;
    private MediaSession session;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        // Promote to foreground immediately with our own static notification —
        // we don't need the MediaSession default notification builder because
        // the user never sees a lock-screen UI for this app. The notification
        // is a "process is alive" beacon, IMPORTANCE_MIN so OEM launchers
        // typically suppress it from the visible shade.
        ensureChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        buildSilentPlayer();
        buildSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // super.onStartCommand may also call startForeground via Media3's own
        // notification provider; we've already done it manually above. Calling
        // super is still required for MediaSessionService to bind controllers.
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (silentPlayer != null) {
            silentPlayer.release();
            silentPlayer = null;
        }
        super.onDestroy();
    }

    // ---------- internals ----------

    @OptIn(markerClass = UnstableApi.class)
    private void buildSilentPlayer() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        silentPlayer = new ExoPlayer.Builder(this)
                // handleAudioFocus = false — audiofocus plugin owns focus.
                .setAudioAttributes(attrs, false)
                // WAKE_MODE_LOCAL keeps CPU awake during local playback, the
                // documented Doze-safe path for Media3.
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        silentPlayer.setVolume(0f);
        silentPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);

        // Load the bundled silent track from res/raw. The resource id is
        // looked up at runtime to avoid coupling to the host app's generated
        // R class (Cordova places R in the app package, not the plugin's).
        int rawId = getResources().getIdentifier("exoplayer_silent", "raw", getPackageName());
        if (rawId != 0) {
            Uri silentUri = RawResourceDataSource.buildRawResourceUri(rawId);
            silentPlayer.setMediaItem(MediaItem.fromUri(silentUri));
            silentPlayer.prepare();
            silentPlayer.setPlayWhenReady(true);
        }

        // BT / MediaSession transport callbacks (play/pause buttons) and any
        // external pause source should NOT stop the silent loop — the locked
        // decision is "BT buttons ignored". Re-arm playWhenReady whenever it
        // gets flipped off for any reason other than end-of-media (which can
        // never happen here because of REPEAT_MODE_ALL).
        silentPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (playWhenReady) return;
                if (silentPlayer == null) return;
                mainHandler.post(() -> {
                    if (silentPlayer != null) silentPlayer.setPlayWhenReady(true);
                });
            }
        });
    }

    private void buildSession() {
        if (silentPlayer == null) return;
        session = new MediaSession.Builder(this, silentPlayer)
                .setId("flanerie_exoplayer_session")
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(getResources().getIdentifier(
                        "exoplayer_channel_name", "string", getPackageName())),
                NotificationManager.IMPORTANCE_MIN);
        int descId = getResources().getIdentifier(
                "exoplayer_channel_description", "string", getPackageName());
        if (descId != 0) ch.setDescription(getString(descId));
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private android.app.Notification buildNotification() {
        int iconRes;
        try { iconRes = getApplicationInfo().icon; }
        catch (Exception e) { iconRes = android.R.drawable.ic_media_play; }

        PendingIntent tap = buildTapIntent();

        int titleId = getResources().getIdentifier(
                "exoplayer_notification_title", "string", getPackageName());
        int textId = getResources().getIdentifier(
                "exoplayer_notification_text", "string", getPackageName());
        String title = titleId != 0 ? getString(titleId) : "Flanerie";
        String text  = textId  != 0 ? getString(textId)  : "Lecture audio";

        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new android.app.Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new android.app.Notification.Builder(this);
        }
        b.setContentTitle(title).setContentText(text).setSmallIcon(iconRes).setOngoing(true);
        if (tap != null) b.setContentIntent(tap);
        return b.build();
    }

    private PendingIntent buildTapIntent() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) return null;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(this, 0, launch, flags);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- helpers invoked from ExoPlayerPlugin ----------

    /**
     * Returns the persistent silent player so the plugin's per-instance code
     * can share its rendering pipeline if needed in future. Public so
     * ExoPlayerPlugin (same package) can reach it; do not expose elsewhere.
     */
    @Nullable
    ExoPlayer getSilentPlayer() { return silentPlayer; }

    static void start(Context ctx) {
        Intent intent = new Intent(ctx, ExoPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ExoPlayerService.class));
    }
}
