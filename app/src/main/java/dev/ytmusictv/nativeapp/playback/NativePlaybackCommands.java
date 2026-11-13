package dev.ytmusictv.nativeapp.playback;

import android.os.Bundle;
import androidx.media3.session.SessionCommand;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import java.util.ArrayList;
import java.util.List;

/** Queue actions are explicit session commands; standard transport remains MediaController. */
public final class NativePlaybackCommands {
    private NativePlaybackCommands() { }
    public static final String PLAY_NOW = "native.play_now", PLAY_NEXT = "native.play_next", ADD_END = "native.add_end";
    public static final String PLAY_COLLECTION = "native.play_collection";
    public static final String PLAY_NEXT_COLLECTION = "native.play_next_collection", ADD_COLLECTION = "native.add_collection";
    public static final String MOVE = "native.move", REMOVE = "native.remove", UNDO = "native.undo";
    public static final String CLEAR_UPCOMING = "native.clear_upcoming";
    public static final String REFRESH_ARTWORK = "native.refresh_artwork";
    public static final String SNAPSHOT = "native.snapshot", SET_VIDEO = "native.video", SET_SHUFFLE = "native.shuffle";
    public static final String SET_REPEAT = "native.repeat", SET_BACKGROUND = "native.background";
    public static final String APP_FOREGROUND = "native.foreground", RESTORE = "native.restore", RETRY = "native.retry";
    public static final String VIDEO_VISIBLE = "native.video_visible";
    public static final String SET_VIDEO_QUALITY = "native.video_quality";
    public static final String QUEUE_CHANGED = "native.queue_changed";
    static final String[] ACTIONS = {PLAY_NOW, PLAY_NEXT, ADD_END, PLAY_COLLECTION, PLAY_NEXT_COLLECTION, ADD_COLLECTION,
            MOVE, REMOVE, UNDO, CLEAR_UPCOMING, REFRESH_ARTWORK, SNAPSHOT,
            SET_VIDEO, SET_VIDEO_QUALITY, SET_SHUFFLE, SET_REPEAT, SET_BACKGROUND, APP_FOREGROUND, VIDEO_VISIBLE, RESTORE, RETRY};

    public static SessionCommand command(String action) { return new SessionCommand(action, Bundle.EMPTY); }
    public static Bundle trackArgs(NativeTrack track) {
        Bundle args = new Bundle(); args.putBundle("track", track.toBundle()); return args;
    }
    public static Bundle collectionArgs(List<NativeTrack> tracks) {
        if (tracks == null) throw new IllegalArgumentException("Choose a collection first");
        if (tracks.isEmpty()) throw new IllegalArgumentException("This collection has no playable tracks");
        if (tracks.size() > QueueCheckpoint.MAX_ENTRIES) {
            throw new IllegalArgumentException("The whole collection will not fit. The queue limit is 200 entries.");
        }
        ArrayList<Bundle> rows = new ArrayList<>(tracks.size());
        for (NativeTrack track : tracks) {
            if (track == null) throw new IllegalArgumentException("The collection contains an unavailable track");
            rows.add(track.toBundle());
        }
        Bundle args = new Bundle(); args.putParcelableArrayList("tracks", rows); return args;
    }
    /** Same bounded track payload; this command only fills missing queue covers. */
    public static Bundle artworkArgs(List<NativeTrack> tracks) { return collectionArgs(tracks); }
    public static Bundle entryArgs(String entryId) {
        Bundle args = new Bundle(); args.putString("entryId", entryId); return args;
    }
    public static Bundle moveArgs(String entryId, String beforeEntryId) {
        Bundle args = entryArgs(entryId); args.putString("beforeEntryId", beforeEntryId); return args;
    }
    public static Bundle boolArgs(boolean value) {
        Bundle args = new Bundle(); args.putBoolean("value", value); return args;
    }
    public static Bundle repeatArgs(int mode) {
        Bundle args = new Bundle(); args.putInt("value", mode); return args;
    }
    public static Bundle videoQualityArgs(int quality) {
        Bundle args = new Bundle(); args.putInt("quality", VideoQualityPolicy.require(quality)); return args;
    }
}
