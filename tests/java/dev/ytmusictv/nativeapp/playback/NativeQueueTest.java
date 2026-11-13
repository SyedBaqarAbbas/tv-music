package dev.ytmusictv.nativeapp.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        persistence();
        System.out.println("Native queue/checkpoint checks passed: " + checks);
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
