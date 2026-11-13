package dev.ytmusictv.nativeapp.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Short-lived transport information. Never persist or log signed source URLs. */
public final class PlaybackSource {
    public final String audioUrl, videoUrl, audioMimeType, videoMimeType;
    public final Map<String, String> headers;
    public final long expiresAtMillis;

    public PlaybackSource(String audioUrl, String videoUrl, String audioMimeType,
            String videoMimeType, Map<String, String> headers, long expiresAtMillis) {
        this.audioUrl = audioUrl == null ? "" : audioUrl;
        this.videoUrl = videoUrl == null ? "" : videoUrl;
        this.audioMimeType = audioMimeType == null ? "audio/mp4" : audioMimeType;
        this.videoMimeType = videoMimeType == null ? "video/mp4" : videoMimeType;
        this.headers = Collections.unmodifiableMap(new HashMap<>(
                headers == null ? Collections.emptyMap() : headers));
        this.expiresAtMillis = expiresAtMillis;
    }

    public boolean hasVideo() { return !videoUrl.isEmpty(); }
    @Override public String toString() {
        return "PlaybackSource(audio=" + !audioUrl.isEmpty() + ", video=" + hasVideo() + ")";
    }
}
