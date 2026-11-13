package dev.ytmusictv.nativeapp.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import dev.ytmusictv.nativeapp.model.NativeStreamResolver;
import dev.ytmusictv.nativeapp.model.PlaybackSource;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Random;

/** Actual queue decisions and checkpoint JSON with tiny in-memory platform fixtures. */
public final class NativeQueueTest {
    private static int checks;
    public static void main(String[] args) {
        List<String> original = List.of("A", "B", "C");
        equal(List.of("A", "D", "B", "C"), QueueOrder.insertNext(original, "A", "D"));
        equal(List.of("A", "E", "D", "B", "C"), QueueOrder.insertNext(
                QueueOrder.insertNext(original, "A", "D"), "A", "E"));
        equal(List.of("A", "B", "C", "E"), QueueOrder.append(original, "E"));
        equal(List.of("A", "C", "B"), QueueOrder.moveUpcoming(original, "A", "C", "B"));
        equal(List.of("A", "C", "B"), QueueOrder.moveUpcoming(original, "A", "B", ""));
        equal(original, QueueOrder.moveUpcoming(original, "A", "B", "B"));
        equal(List.of("D"), QueueOrder.insertNext(List.of(), "", "D"));
        equal(List.of("D"), QueueOrder.append(List.of(), "D"));
        rejects(() -> QueueOrder.moveUpcoming(original, "A", "A", "C"));
        rejects(() -> QueueOrder.moveUpcoming(original, "B", "B", "C"));
        rejects(() -> QueueOrder.moveUpcoming(original, "B", "C", "A"));
        rejects(() -> QueueOrder.moveUpcoming(original, "C", "B", ""));
        rejects(() -> QueueOrder.moveUpcoming(original, "A", "gone", "B"));
        rejects(() -> QueueOrder.moveUpcoming(original, "A", "B", "gone"));
        rejects(() -> QueueOrder.insertNext(original, "A", "B"));
        rejects(() -> QueueOrder.append(original, "A"));
        rejects(() -> QueueOrder.append(List.of("A", "A"), "B"));
        equal(List.of("A", "A2", "B"), QueueOrder.insertNext(List.of("A", "B"), "A", "A2"));
        equal(List.of("C", "A", "B", "D"), QueueOrder.retainExisting(
                List.of("C", "gone", "A", "A"), List.of("A", "B", "C", "D")));
        for (int seed = 0; seed < 20; seed++) {
            List<String> shuffled = QueueOrder.shuffleUpcoming(List.of("history", "A", "B", "C", "D"), "A", new Random(seed));
            check(shuffled.subList(0, 2).equals(List.of("history", "A")) && new HashSet<>(shuffled).size() == 5);
        }
        equal(List.of("A", "B", "C"), original);
        equal(List.of("A"), QueueOrder.clearUpcoming(original, "A"));
        equal(List.of("A", "B"), QueueOrder.clearUpcoming(original, "B"));
        equal(original, QueueOrder.clearUpcoming(original, "C"));
        equal(List.of(), QueueOrder.clearUpcoming(List.of(), ""));
        rejects(() -> QueueOrder.clearUpcoming(original, "missing"));
        equal(List.of("history", "current"), QueueOrder.clearUpcoming(
                List.of("history", "current", "shuffled-C", "shuffled-B"), "current"));
        collections();
        artwork();
        quality();
        persistence();
        System.out.println("Native queue/checkpoint checks passed: " + checks);
    }
    private static void artwork() {
        Map<String,NativeTrack> queued = new LinkedHashMap<>();
        NativeTrack current = new NativeTrack("song", "Original title", "Artist", "Album", 183, "", true);
        NativeTrack duplicate = new NativeTrack("song", "Another occurrence", "Artist", "Album", 99, "", false);
        NativeTrack existing = new NativeTrack("song", "Existing art", "Artist", "Album", 20, "https://old.example/cover", false);
        NativeTrack unrelated = new NativeTrack("other", "Other song", "Artist", "Album", 40, "", false);
        queued.put("current-id", current); queued.put("duplicate-id", duplicate);
        queued.put("covered-id", existing); queued.put("upcoming-id", unrelated);
        List<String> before = new ArrayList<>(queued.keySet());
        NativeTrack fresh = new NativeTrack("song", "Different provider title", "artist", "album", 900,
                "https://images.example/cover", false);
        check(ArtworkRepair.fillMissing(queued, List.of(fresh)) == 2);
        equal(before, new ArrayList<>(queued.keySet())); // Stable entry IDs and order, including duplicate songs.
        NativeTrack repaired = queued.get("current-id");
        check(repaired.videoId.equals(current.videoId) && repaired.title.equals(current.title)
                && repaired.artist.equals(current.artist) && repaired.album.equals(current.album)
                && repaired.durationSeconds == current.durationSeconds && repaired.isVideoSource == current.isVideoSource);
        check(repaired.thumbnailUrl.equals(fresh.thumbnailUrl) && current.thumbnailUrl.isEmpty());
        check(queued.get("duplicate-id").title.equals(duplicate.title)
                && queued.get("duplicate-id").durationSeconds == 99 && !queued.get("duplicate-id").isVideoSource);
        check(queued.get("covered-id") == existing && queued.get("upcoming-id") == unrelated);
        check(ArtworkRepair.fillMissing(queued, List.of(fresh)) == 0 && queued.get("current-id") == repaired);

        Map<String,NativeTrack> conflicts = new LinkedHashMap<>(); conflicts.put("kept-id", current);
        check(ArtworkRepair.fillMissing(conflicts, List.of(new NativeTrack("song", "", "Other artist", "Album", 0,
                fresh.thumbnailUrl, false))) == 0 && conflicts.get("kept-id") == current);
        check(ArtworkRepair.fillMissing(conflicts, List.of(new NativeTrack("song", "", "Artist", "Other album", 0,
                fresh.thumbnailUrl, false))) == 0 && conflicts.get("kept-id") == current);
        for(String invalid : List.of("http://images.example/cover", "https://user@images.example/cover", "https://images.example:81/cover")) {
            check(ArtworkRepair.fillMissing(conflicts, List.of(new NativeTrack("song", "", "Artist", "Album", 0,
                    invalid, false))) == 0 && conflicts.get("kept-id") == current);
        }
        check(ArtworkRepair.fillMissing(conflicts, List.of(
                new NativeTrack("song", "", "Different artist", "Album", 0, fresh.thumbnailUrl, false), fresh)) == 1);
        NativeTrack noAlbum = new NativeTrack("song", "Original", "Artist", "", 15, "", false);
        conflicts.clear(); conflicts.put("kept-id", noAlbum);
        check(ArtworkRepair.fillMissing(conflicts, List.of(fresh)) == 1 && conflicts.get("kept-id").album.isEmpty());
        List<NativeTrack> malformed = new ArrayList<>(); malformed.add(fresh); malformed.add(null);
        conflicts.clear(); conflicts.put("kept-id", current);
        rejects(() -> ArtworkRepair.fillMissing(conflicts, malformed));
        check(conflicts.get("kept-id") == current); // A malformed final row cannot partially repair metadata.
    }
    private static void collections() {
        List<String> shuffled = List.of("history", "current", "existing-C", "existing-B");
        List<String> album = List.of("album-1", "album-2", "album-3");
        equal(List.of("history", "current", "album-1", "album-2", "album-3", "existing-C", "existing-B"),
                QueueOrder.insertNextCollection(shuffled, "current", album));
        equal(List.of("history", "current", "existing-C", "existing-B", "album-1", "album-2", "album-3"),
                QueueOrder.appendCollection(shuffled, album));
        equal(album, QueueOrder.insertNextCollection(List.of(), "", album));
        equal(album, QueueOrder.appendCollection(List.of(), album));
        equal(List.of("history", "current", "new-1", "new-2", "album-1", "album-2", "album-3", "existing-C", "existing-B"),
                QueueOrder.insertNextCollection(QueueOrder.insertNextCollection(shuffled, "current", album),
                        "current", List.of("new-1", "new-2")));
        rejects(() -> QueueOrder.insertNextCollection(shuffled, "stale", album));
        rejects(() -> QueueOrder.insertNextCollection(shuffled, "current", List.of("album-1", "album-1")));
        rejects(() -> QueueOrder.appendCollection(shuffled, List.of("album-1", "existing-B")));
        rejects(() -> QueueOrder.appendCollection(shuffled, List.of()));
        equal(List.of("history", "current", "existing-C", "existing-B"), shuffled);

        Bundle valid = collection(3);
        List<NativeTrack> tracks = CollectionBatch.read(valid, 197, false);
        check(tracks.size() == 3 && tracks.get(0).title.equals("Track 0") && tracks.get(2).title.equals("Track 2"));
        check(tracks.get(0).videoId.equals(tracks.get(1).videoId)); // collection duplicates are not deduplicated
        check(CollectionBatch.read(collection(200), 200, true).size() == 200); // replacement ignores old capacity
        rejects(() -> CollectionBatch.read(valid, 198, false));
        rejects(() -> CollectionBatch.read(collection(201), 0, true));
        rejects(() -> CollectionBatch.read(collection(0), 0, false));
        rejects(() -> CollectionBatch.read(new Bundle(), 0, false));
        ArrayList<Bundle> rows = valid.getParcelableArrayList("tracks");
        rows.set(2, new Bundle()); // a bad final row rejects the entire otherwise valid collection
        rejects(() -> CollectionBatch.read(valid, 0, false));
        check(valid.<Bundle>getParcelableArrayList("tracks").size() == 3);
        equal(List.of("history", "current", "existing-C", "existing-B"), shuffled); // validation never mutated live input
    }
    private static Bundle collection(int size) {
        Bundle args = new Bundle(); ArrayList<Bundle> tracks = new ArrayList<>();
        for (int i = 0; i < size; i++) tracks.add(new NativeTrack(i < 2 ? "same-song" : "song-" + i,
                "Track " + i, "Artist", "Album", 180, "", false).toBundle());
        args.putParcelableArrayList("tracks", tracks); return args;
    }
    private static void persistence() {
        MemoryContext context = new MemoryContext(); QueueCheckpoint storage = new QueueCheckpoint(context);
        Bundle state = state(3);
        state.putStringArrayList("baseOrder", new ArrayList<>(List.of("entry-0", "entry-2", "entry-1")));
        state.putString("currentId", "entry-1"); state.putBoolean("shuffle", true);
        state.putBoolean("video", true); state.putBoolean("background", true); state.putInt("repeat", 2);
        state.putString("signedSourceUrl", "not-serialized"); storage.save(state, 9321);
        check(!context.value.contains("not-serialized"));
        Bundle restored = new QueueCheckpoint(context).load();
        equal(List.of("entry-0", "entry-2", "entry-1"), restored.getStringArrayList("baseOrder"));
        check("entry-1".equals(restored.getString("currentId")) && restored.getLong("position") == 9321);
        check(restored.getBoolean("shuffle") && restored.getBoolean("video") && restored.getBoolean("background") && restored.getInt("repeat") == 2);
        ArrayList<Bundle> entries = restored.getParcelableArrayList("entries");
        check(entries.size() == 3 && !entries.get(0).getString("entryId").equals(entries.get(1).getString("entryId")));
        check(NativeTrack.fromBundle(entries.get(0).getBundle("track")).videoId.equals(
                NativeTrack.fromBundle(entries.get(1).getBundle("track")).videoId)); // duplicate songs, independent entries
        storage.save(state(205), -1); restored = storage.load();
        check(restored.<Bundle>getParcelableArrayList("entries").size() == 200 && restored.getLong("position") == 0);
        context.value = "malformed"; check(storage.load().<Bundle>getParcelableArrayList("entries").isEmpty());
        context.value = "x".repeat(1024 * 1024 + 1); check(storage.load().getParcelableArrayList("entries") == null);
    }
    private static Bundle state(int size) {
        Bundle result = new Bundle(); ArrayList<Bundle> entries = new ArrayList<>(); ArrayList<String> base = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            NativeTrack track = new NativeTrack("same-song", "Synthetic", "Artist", "Album", 180, "", false);
            Bundle row = new Bundle(); row.putString("entryId", "entry-" + i); row.putBundle("track", track.toBundle());
            entries.add(row); base.add("entry-" + i);
        }
        result.putParcelableArrayList("entries", entries); result.putStringArrayList("baseOrder", base); return result;
    }
    private static void quality() {
        for(int allowed : new int[]{0,360,480,720,1080,2160}) {
            check(VideoQualityPolicy.require(allowed) == allowed);
            MemoryContext context = new MemoryContext(); QueueCheckpoint storage = new QueueCheckpoint(context);
            Bundle state = state(3); state.putInt("videoQuality", allowed);
            state.putString("currentId", "entry-1"); state.putBoolean("shuffle", true);state.putBoolean("video", true);
            state.putStringArrayList("baseOrder", new ArrayList<>(List.of("entry-0","entry-2","entry-1")));
            storage.save(state, 83_456); Bundle restored = storage.load();
            check(restored.getInt("videoQuality", -1) == allowed);
            check(restored.getLong("position") == 83_456 && "entry-1".equals(restored.getString("currentId"))
                    && restored.getBoolean("shuffle") && restored.getBoolean("video"));
            equal(List.of("entry-0","entry-2","entry-1"),restored.getStringArrayList("baseOrder"));
        }
        for(int invalid : new int[]{-1,144,240,540,1440,2880,4320,Integer.MAX_VALUE}) {
            rejects(() -> VideoQualityPolicy.require(invalid));
            check(VideoQualityPolicy.restored(invalid) == 0);
        }
        MemoryContext context = new MemoryContext(); QueueCheckpoint storage = new QueueCheckpoint(context);
        storage.save(state(1), 0);
        try { context.value = new JSONObject(context.value).put("videoQuality",4320).toString(); }
        catch(Exception invalid) { throw new AssertionError(invalid); }
        check(storage.load().getInt("videoQuality", -1) == 0); // Unknown/corrupt caps never enable unbounded resolution.
        try { JSONObject legacy = new JSONObject(context.value); legacy.remove("videoQuality"); context.value = legacy.toString(); }
        catch(Exception invalid) { throw new AssertionError(invalid); }
        check(storage.load().getInt("videoQuality", -1) == 0); // Checkpoints from before quality selection remain Auto.
        Bundle empty = state(0); empty.putInt("videoQuality",1080);storage.save(empty,0);
        check(storage.load().getInt("videoQuality",-1) == 1080); // Preference survives even an empty queue.
        NativeTrack requested = new NativeTrack("song", "", "", "", 0, "", false);
        PlaybackSource expected = new PlaybackSource("https://media.example/audio", "", "audio/mp4", "", Map.of(), 0);
        int[] calls = {0};
        NativeStreamResolver existing = (track,video) -> {check(track == requested && video);calls[0]++;return expected;};
        try { check(existing.resolve(requested,true,1080) == expected && calls[0] == 1); }
        catch(Exception invalid) { throw new AssertionError(invalid); }
    }
    private static final class MemoryContext extends Context {
        String value = "";
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return new SharedPreferences() {
                @Override public String getString(String key, String fallback) { return value.isEmpty() ? fallback : value; }
                @Override public Editor edit() { return new Editor() {
                    private String pending;
                    @Override public Editor putString(String key, String text) { pending = text; return this; }
                    @Override public void apply() { value = pending; }
                }; }
            };
        }
    }
    private static void equal(List<String> expected, List<String> actual) { check(expected.equals(actual)); }
    private static void check(boolean result) { checks++; if (!result) throw new AssertionError("Native queue check " + checks); }
    private static void rejects(Runnable action) {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid queue mutation accepted at check " + checks);
    }
}
