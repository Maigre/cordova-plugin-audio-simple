package com.maigre.cordova.plugins.exoplayer;

import android.net.Uri;

/**
 * Resolves the URIs handed to the plugin from JS to a form Media3 can read.
 *
 * JS may send any of:
 *   - file:///data/user/0/.../media/foo.mp3   — already a file URI; pass through.
 *   - http://localhost/...                    — embedded WebView server URL.
 *                                                Pass through; Media3's
 *                                                DefaultHttpDataSource handles it
 *                                                but goes through the embedded
 *                                                server, slower than a direct
 *                                                FileDataSource read.
 *   - /absolute/path                          — interpret as a file URI.
 *
 * The expectation is that the JS layer (FlanerieAudioMap's PlayerSimple) calls
 * the existing httpToNativePath() helper before reaching the plugin and passes
 * a file:// URI when possible. Real translation logic lives here for the
 * fallback case and for future expansion.
 */
final class UriResolver {

    private UriResolver() { /* no-instances */ }

    static Uri resolve(String src) {
        if (src == null || src.length() == 0) return Uri.EMPTY;
        if (src.startsWith("file://") || src.startsWith("content://")) return Uri.parse(src);
        if (src.startsWith("http://") || src.startsWith("https://")) return Uri.parse(src);
        if (src.charAt(0) == '/') return Uri.parse("file://" + src);
        return Uri.parse(src);
    }
}
