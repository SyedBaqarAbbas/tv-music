package dev.ytmusictv.nativeapp.model;

import android.os.Bundle;

/** Catalogue identity; queue entries receive their own independent IDs. */
public final class NativeTrack {
    public final String videoId, title, artist, album, thumbnailUrl;
    public final int durationSeconds;
    public final boolean isVideoSource;

    public NativeTrack(String videoId, String title, String artist, String album,
            int durationSeconds, String thumbnailUrl, boolean isVideoSource) {
        this.videoId = clean(videoId, 128);
        this.title = clean(title, 512);
        this.artist = clean(artist, 512);
        this.album = clean(album, 512);
        this.durationSeconds = Math.max(0, durationSeconds);
        this.thumbnailUrl = clean(thumbnailUrl, 2048);
        this.isVideoSource = isVideoSource;
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString("videoId", videoId); b.putString("title", title);
        b.putString("artist", artist); b.putString("album", album);
        b.putInt("durationSeconds", durationSeconds); b.putString("thumbnailUrl", thumbnailUrl);
        b.putBoolean("isVideoSource", isVideoSource);
        return b;
    }

    public static NativeTrack fromBundle(Bundle b) {
        if (b == null) return null;
        NativeTrack t = new NativeTrack(b.getString("videoId", ""), b.getString("title", ""),
                b.getString("artist", ""), b.getString("album", ""),
                b.getInt("durationSeconds", 0), b.getString("thumbnailUrl", ""),
                b.getBoolean("isVideoSource", false));
        return t.videoId.isEmpty() ? null : t;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.trim(); return s.length() <= max ? s : s.substring(0, max);
    }
}
