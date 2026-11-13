package dev.ytmusictv.nativeapp.playback;

import android.os.Bundle;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import java.util.ArrayList;
import java.util.List;

/** Complete admission and copying before the coordinator allocates or mutates queue entries. */
final class CollectionBatch {
    private CollectionBatch() { }

    static List<NativeTrack> read(Bundle args, int existingCount, boolean replacing) {
        ArrayList<?> rows = args == null ? null : args.getParcelableArrayList("tracks");
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("This collection has no playable tracks");
        int retained = replacing ? 0 : existingCount;
        if (retained < 0 || retained > QueueCheckpoint.MAX_ENTRIES
                || rows.size() > QueueCheckpoint.MAX_ENTRIES - retained) {
            throw new IllegalArgumentException("The whole collection will not fit. The queue limit is 200 entries.");
        }
        ArrayList<NativeTrack> tracks = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (!(row instanceof Bundle)) throw new IllegalArgumentException("The collection contains an unavailable track");
            NativeTrack track = NativeTrack.fromBundle((Bundle) row);
            if (track == null) throw new IllegalArgumentException("The collection contains an unavailable track");
            tracks.add(track);
        }
        return tracks;
    }
}
