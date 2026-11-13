package dev.ytmusictv.nativeapp.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Pure decisions over a snapshot of live playback order, never a second live queue. */
public final class QueueOrder {
    private QueueOrder() { }

    public static List<String> insertNext(List<String> order, String current, String entry) {
        ArrayList<String> copy = checked(order);
        if (copy.contains(entry)) throw new IllegalArgumentException("Duplicate queue entry ID");
        int at = copy.indexOf(current);
        copy.add(at < 0 ? 0 : at + 1, entry);
        return copy;
    }

    public static List<String> append(List<String> order, String entry) {
        ArrayList<String> copy = checked(order);
        if (copy.contains(entry)) throw new IllegalArgumentException("Duplicate queue entry ID");
        copy.add(entry);
        return copy;
    }

    public static List<String> moveUpcoming(List<String> order, String current, String entry, String before) {
        ArrayList<String> copy = checked(order);
        int currentIndex = copy.indexOf(current), from = copy.indexOf(entry);
        int target = before == null || before.isEmpty() ? copy.size() : copy.indexOf(before);
        if (from < 0 || from <= currentIndex || target < 0 || target <= currentIndex) {
            throw new IllegalArgumentException("Only upcoming entries can be moved");
        }
        if (entry.equals(before)) return copy;
        copy.remove(from);
        int destination = before == null || before.isEmpty() ? copy.size() : copy.indexOf(before);
        copy.add(destination, entry);
        return copy;
    }

    public static List<String> shuffleUpcoming(List<String> order, String current, Random random) {
        ArrayList<String> copy = checked(order);
        int start = Math.max(0, copy.indexOf(current) + 1);
        Collections.shuffle(copy.subList(start, copy.size()), random);
        return copy;
    }

    public static List<String> clearUpcoming(List<String> order, String current) {
        ArrayList<String> copy = checked(order);
        int index = copy.indexOf(current);
        if (!copy.isEmpty() && index < 0) throw new IllegalArgumentException("The current entry changed; retry clear");
        return new ArrayList<>(copy.subList(0, index < 0 ? 0 : index + 1));
    }

    public static List<String> retainExisting(List<String> desired, List<String> existing) {
        ArrayList<String> result = new ArrayList<>();
        HashSet<String> available = new HashSet<>(existing);
        for (String id : desired) if (available.remove(id)) result.add(id);
        for (String id : existing) if (available.remove(id)) result.add(id);
        return result;
    }

    private static ArrayList<String> checked(List<String> order) {
        if (new HashSet<>(order).size() != order.size()) throw new IllegalArgumentException("Queue entry IDs must be unique");
        return new ArrayList<>(order);
    }
}
