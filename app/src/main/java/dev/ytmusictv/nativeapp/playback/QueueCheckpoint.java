package dev.ytmusictv.nativeapp.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Small atomic snapshots of catalogue IDs and intent, never signed stream URLs. */
final class QueueCheckpoint {
    static final int MAX_ENTRIES = 200;
    private final SharedPreferences preferences;
    QueueCheckpoint(Context context) { preferences = context.getSharedPreferences("native_playback", Context.MODE_PRIVATE); }

    void save(Bundle snapshot, long position) {
        try {
            JSONArray entries = new JSONArray();
            ArrayList<Bundle> list = snapshot.getParcelableArrayList("entries");
            if (list != null) for (Bundle row : list) {
                if (entries.length() >= MAX_ENTRIES) break;
                NativeTrack t = NativeTrack.fromBundle(row.getBundle("track"));
                if (t == null) continue;
                JSONObject record = new JSONObject().put("entryId", row.getString("entryId"))
                        .put("videoId", t.videoId).put("title", t.title).put("artist", t.artist)
                        .put("album", t.album).put("duration", t.durationSeconds).put("thumbnail", t.thumbnailUrl)
                        .put("videoSource", t.isVideoSource);
                entries.put(record);
            }
            JSONObject state = new JSONObject().put("entries", entries).put("current", snapshot.getString("currentId", ""))
                    .put("baseOrder", new JSONArray(snapshot.getStringArrayList("baseOrder")))
                    .put("position", Math.max(0, position)).put("shuffle", snapshot.getBoolean("shuffle"))
                    .put("repeat", snapshot.getInt("repeat")).put("video", snapshot.getBoolean("video"))
                    .put("background", snapshot.getBoolean("background"));
            preferences.edit().putString("checkpoint", state.toString()).apply();
        } catch (Exception ignored) { /* A malformed item must not crash active playback. */ }
    }

    Bundle load() {
        Bundle snapshot = new Bundle();
        ArrayList<Bundle> entries = new ArrayList<>();
        try {
            String raw = preferences.getString("checkpoint", "");
            if (raw == null || raw.length() > 1024 * 1024) return snapshot;
            JSONObject state = new JSONObject(raw);
            JSONArray list = state.optJSONArray("entries");
            for (int i = 0; list != null && i < Math.min(list.length(), MAX_ENTRIES); i++) {
                JSONObject row = list.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("entryId", "");
                NativeTrack track = new NativeTrack(row.optString("videoId"), row.optString("title"),
                        row.optString("artist"), row.optString("album"), row.optInt("duration"),
                        row.optString("thumbnail"), row.optBoolean("videoSource"));
                if (id.isEmpty() || id.length() > 128 || track.videoId.isEmpty()) continue;
                Bundle entry = new Bundle(); entry.putString("entryId", id); entry.putBundle("track", track.toBundle());
                entries.add(entry);
            }
            snapshot.putString("currentId", state.optString("current", ""));
            JSONArray base = state.optJSONArray("baseOrder");
            ArrayList<String> baseIds = new ArrayList<>();
            for (int i = 0; base != null && i < Math.min(base.length(), MAX_ENTRIES); i++) baseIds.add(base.optString(i));
            snapshot.putStringArrayList("baseOrder", baseIds);
            snapshot.putLong("position", Math.max(0, state.optLong("position")));
            snapshot.putBoolean("shuffle", state.optBoolean("shuffle"));
            snapshot.putInt("repeat", Math.max(0, Math.min(2, state.optInt("repeat"))));
            snapshot.putBoolean("video", state.optBoolean("video"));
            snapshot.putBoolean("background", state.optBoolean("background"));
        } catch (Exception ignored) { }
        snapshot.putParcelableArrayList("entries", entries);
        return snapshot;
    }
}
