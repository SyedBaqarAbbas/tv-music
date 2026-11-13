package dev.ytmusictv;

import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Provider policy regression fixtures; run with the API30 compile-check jar. */
public final class LyricsRepositoryTest {
    private static int checks;
    private static final String FULL_TEXT = String.join("\n",
            "The synthetic opening line begins the song",
            "A second synthetic line continues its story",
            "The third synthetic line enters the refrain",
            "A fourth synthetic line repeats the melody",
            "The fifth synthetic line leads to the finale",
            "A final synthetic line ends the whole song");
    private static final String COMPLETE = "[00:02]" + FULL_TEXT.replace("\n", "\n[00:20]");

    public static void main(String[] args) throws Exception {
        Method usable = method("usableRecord", JSONObject.class, String.class, String.class, int.class, int.class);
        Method select = method("select", JSONArray.class, String.class, String.class, String.class, int.class);
        Method fallback = method("fullerFallback", JSONObject.class, JSONObject.class);
        try (LyricsRepository repository = new LyricsRepository(null)) {
            JSONObject partial = record(223, "[00:02]Opening fragment", FULL_TEXT);
            JSONObject plain = (JSONObject) usable.invoke(repository, partial, "Synthetic song", "Synthetic artist", 223, 200);
            check("plain".equals(plain.getString("status")));
            check(plain.getString("syncedLyrics").isEmpty());
            check(FULL_TEXT.equals(plain.getString("plainLyrics")));
            check(plain.getBoolean("syncedIncomplete"));
            check(plain.getBoolean("timingMatched"));

            JSONObject complete = record(224, COMPLETE, FULL_TEXT).put("albumName", "Other album");
            JSONObject otherRecording = record(204, COMPLETE, FULL_TEXT);
            JSONObject chosen = (JSONObject) select.invoke(repository,
                    new JSONArray().put(partial).put(otherRecording).put(complete),
                    "Synthetic song", "Synthetic artist", "Exact album", 223);
            check(chosen == complete); // complete compatible timing beats the exact but partial record
            JSONObject timed = (JSONObject) usable.invoke(repository, chosen, "Synthetic song", "Synthetic artist", 223, 200);
            check("synced".equals(timed.getString("status")));
            JSONObject untimed = (JSONObject) usable.invoke(repository, otherRecording,
                    "Synthetic song", "Synthetic artist", 223, 200);
            check("plain".equals(untimed.getString("status")) && !untimed.getBoolean("timingMatched"));
            check(fallback.invoke(null, plain, untimed) == plain);

            JSONObject instrumental = (JSONObject) usable.invoke(repository,
                    record(223, "", "").put("instrumental", true), "Synthetic song", "Synthetic artist", 223, 200);
            check("instrumental".equals(instrumental.getString("status")));
            check(fallback.invoke(null, plain, instrumental) == plain); // conflicting flag cannot erase vocal text
            JSONObject interlude = (JSONObject) usable.invoke(repository,
                    record(223, "[00:02]One vocal phrase", "One vocal phrase"),
                    "Synthetic song", "Synthetic artist", 223, 200);
            check("synced".equals(interlude.getString("status"))); // duration alone does not reject sparse vocals
        }
        System.out.println("Lyrics provider policy checks passed: " + checks);
    }

    private static JSONObject record(int duration, String synced, String plain) throws Exception {
        return new JSONObject().put("trackName", "Synthetic song").put("artistName", "Synthetic artist")
                .put("albumName", "Exact album").put("duration", duration).put("syncedLyrics", synced)
                .put("plainLyrics", plain).put("instrumental", false);
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method result = LyricsRepository.class.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }
    private static void check(boolean result) {
        checks++;
        if (!result) throw new AssertionError("Failed provider check " + checks);
    }
}
