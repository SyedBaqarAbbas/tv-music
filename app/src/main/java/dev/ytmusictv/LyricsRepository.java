package dev.ytmusictv;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded network worker and a small in-memory cache; never blocks the UI. */
public final class LyricsRepository implements AutoCloseable {
    public interface Callback { void onResult(JSONObject payload); }
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_SEARCH_RESULTS = 20;
    private final Handler main;
    private final boolean debugLogging;
    private final AtomicLong generation = new AtomicLong();
    private final LruCache<String, JSONObject> cache = new LruCache<>(8);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private long nextRequestAt;
    private int cooldownStatus;

    public LyricsRepository(Handler mainHandler) { this(mainHandler, false); }
    public LyricsRepository(Handler mainHandler, boolean debugLogging) {
        main = mainHandler;
        this.debugLogging = debugLogging;
    }

    public void request(JSONObject metadata, Callback callback) {
        if (closed) return;
        final String trackId = field(metadata, "trackId");
        final String title = field(metadata, "title");
        final String artist = field(metadata, "artist");
        final String album = field(metadata, "album");
        final int duration = (int) Math.round(metadata.optDouble("duration", 0));
        final String key = title + "\n" + artist + "\n" + album + "\n" + duration;
        final long token = generation.incrementAndGet();
        try {
            worker.execute(() -> {
                if (cancelled(token)) return;
                JSONObject result = cache.get(key);
                if (result == null) {
                    result = fetch(title, artist, album, duration, token);
                    if (!cancelled(token) && !"error".equals(result.optString("status"))) cache.put(key, result);
                }
                if (cancelled(token)) return;
                try {
                    JSONObject payload = new JSONObject(result.toString());
                    payload.put("trackId", trackId);
                    payload.put("title", title);
                    payload.put("artist", artist);
                    main.post(() -> { if (!cancelled(token)) callback.onResult(payload); });
                } catch (Exception ignored) { }
            });
        } catch (RejectedExecutionException ignored) {
            // close() can race with a request; a destroyed activity needs no callback.
        }
    }

    private JSONObject fetch(String title, String artist, String album, int duration, long token) {
        if (title.isEmpty() || artist.isEmpty()) return status("missing", "Play a song to load lyrics.", 0);
        try {
            Response exact = lookup("exact", signature(title, artist, album, duration), false, token);
            if (exact.failure != null) return exact.failure;
            JSONObject found = usableRecord(exact.record, title, artist, duration, exact.code);
            if (found != null) return found;
            String cleanedTitle = LyricsMatcher.cleanTitle(title, artist);
            String cleanedArtist = LyricsMatcher.queryArtist(artist);
            // A video may have an intro/outro. Drop album and duration constraints;
            // the returned recording still needs title/artist and timing checks below.
            if (!album.isEmpty() || (duration >= 1 && duration <= 3600)
                    || !title.equals(cleanedTitle) || !artist.equals(cleanedArtist)) {
                Response cleaned = lookup("normalized", signature(cleanedTitle, cleanedArtist, "", 0), false, token);
                if (cleaned.failure != null) return cleaned.failure;
                found = usableRecord(cleaned.record, title, artist, duration, cleaned.code);
                if (found != null) return found;
            }
            // At most three sequential calls. Search is capped and matches title AND artist.
            Response searched = lookup("search", "/search?track_name=" + encode(cleanedTitle)
                    + "&artist_name=" + encode(cleanedArtist), true, token);
            if (searched.failure != null) return searched.failure;
            found = usableRecord(select(searched.records, title, artist, album, duration),
                    title, artist, duration, searched.code);
            return found != null ? found : status("missing", "No matching lyrics found for this recording.", searched.code);
        } catch (Exception error) {
            diagnostic("lookup failed: " + error.getClass().getSimpleName());
            return status("error", "Lyrics are unavailable. Check your connection and try again.", 0);
        }
    }

    private static String signature(String title, String artist, String album, int duration) throws Exception {
        String query = "/get?track_name=" + encode(title) + "&artist_name=" + encode(artist);
        if (!album.isEmpty()) query += "&album_name=" + encode(album);
        if (duration >= 1 && duration <= 3600) query += "&duration=" + duration;
        return query;
    }

    private Response lookup(String stage, String path, boolean array, long token) {
        HttpURLConnection request = null;
        int code = 0;
        try {
            if (cancelled(token)) return Response.failed(status("error", "Request cancelled.", 0));
            long wait = nextRequestAt - SystemClock.elapsedRealtime();
            if (wait > 500) return Response.failed(status("error", "Lyrics service is busy. Try again later.", cooldownStatus));
            if (wait > 0) Thread.sleep(wait);
            if (cancelled(token)) return Response.failed(status("error", "Request cancelled.", 0));
            request = (HttpURLConnection) new URL("https://lrclib.net/api" + path).openConnection();
            connection = request;
            request.setConnectTimeout(8000);
            request.setReadTimeout(8000);
            request.setInstanceFollowRedirects(false);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("User-Agent", "YTMusicTV/0.1 (personal Android TV prototype)");
            code = request.getResponseCode();
            diagnostic(stage + " HTTP " + code);
            if (code == 429 || code == 503) {
                cooldownStatus = code;
                long delay = retryDelay(request);
                long jitter = ThreadLocalRandom.current().nextLong(1, Math.min(1000, delay / 4) + 1);
                long now = SystemClock.elapsedRealtime();
                nextRequestAt = delay >= Long.MAX_VALUE - now - jitter ? Long.MAX_VALUE : now + delay + jitter;
                return Response.failed(status("error", "Lyrics service is busy (HTTP " + code + "). Try again later.", code));
            }
            if (code == 404) return new Response(code, null, null, null);
            if (code != 200) return Response.failed(status("error",
                    "Lyrics service returned HTTP " + code + ". Try again later.", code));
            String contentType = request.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) {
                return Response.failed(status("error", "Unexpected response from the lyrics service (HTTP 200).", code));
            }
            Object parsed;
            try (InputStream input = request.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled(token)) return Response.failed(status("error", "Request cancelled.", code));
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                        return Response.failed(status("error", "Lyrics service response is too large. Try another recording.", code));
                    }
                    bytes.write(buffer, 0, count);
                }
                parsed = new JSONTokener(bytes.toString(StandardCharsets.UTF_8.name())).nextValue();
            }
            if (array && parsed instanceof JSONArray) return new Response(code, null, (JSONArray) parsed, null);
            if (!array && parsed instanceof JSONObject) return new Response(code, (JSONObject) parsed, null, null);
            return Response.failed(status("error", "Invalid response from the lyrics service (HTTP 200).", code));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Response.failed(status("error", "Request cancelled.", code));
        } catch (Exception error) {
            diagnostic(stage + " failed: " + error.getClass().getSimpleName());
            return Response.failed(status("error", "Lyrics are unavailable. Check your connection and try again.", code));
        } finally {
            if (request != null) {
                request.disconnect();
                nextRequestAt = Math.max(nextRequestAt, SystemClock.elapsedRealtime() + 350);
            }
            connection = null;
        }
    }

    private JSONObject select(JSONArray records, String title, String artist, String album, int duration) {
        JSONObject best = null;
        double bestScore = -Double.MAX_VALUE;
        int count = records == null ? 0 : Math.min(records.length(), MAX_SEARCH_RESULTS);
        for (int index = 0; index < count; index++) {
            JSONObject record = records.optJSONObject(index);
            if (record == null || !matches(record, title, artist) || !hasLyrics(record)) continue;
            double candidateDuration = record.optDouble("duration", 0);
            boolean timed = LyricsMatcher.timingMatches(duration, candidateDuration);
            double distance = Double.isFinite(candidateDuration) && candidateDuration > 0 && duration > 0
                    ? Math.min(Math.abs(duration - candidateDuration), 600) : 600;
            double score = (timed ? 1000 : 0) + (!text(record, "syncedLyrics").isEmpty() ? 50 : 20)
                    + (LyricsMatcher.normalize(album).equals(LyricsMatcher.normalize(field(record, "albumName"))) ? 10 : 0)
                    - distance;
            if (score > bestScore) { best = record; bestScore = score; }
        }
        diagnostic("search candidates " + count + "; match " + (best != null));
        return best;
    }

    private JSONObject usableRecord(JSONObject record, String title, String artist, int duration, int code) throws Exception {
        if (record == null || !matches(record, title, artist) || !hasLyrics(record)) return null;
        boolean timed = LyricsMatcher.timingMatches(duration, record.optDouble("duration", 0));
        if (record.optBoolean("instrumental") && timed) return status("instrumental", "Instrumental track", code);
        String synced = text(record, "syncedLyrics");
        String plain = text(record, "plainLyrics");
        if (synced.isEmpty() && plain.isEmpty()) return null;
        if (!timed) {
            if (plain.isEmpty()) plain = LyricsMatcher.plainFromSynced(synced);
            synced = "";
        }
        JSONObject payload = status(!synced.isEmpty() ? "synced" : "plain", timed
                ? "Lyrics loaded." : "Untimed lyrics for another recording. The video timing does not match.", code);
        payload.put("syncedLyrics", synced);
        payload.put("plainLyrics", plain);
        payload.put("timingMatched", timed);
        diagnostic("match timing " + (timed ? "within 3s" : "unsynced"));
        return payload;
    }

    private static boolean matches(JSONObject record, String title, String artist) {
        return LyricsMatcher.sameTrack(title, artist, field(record, "trackName"), field(record, "artistName"));
    }

    private static boolean hasLyrics(JSONObject record) {
        return record.optBoolean("instrumental") || !text(record, "syncedLyrics").isEmpty()
                || !text(record, "plainLyrics").isEmpty();
    }

    private static long retryDelay(HttpURLConnection request) {
        try {
            long seconds = Math.max(1, Long.parseLong(request.getHeaderField("Retry-After")));
            return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
        } catch (Exception ignored) {
            long date = request.getHeaderFieldDate("Retry-After", -1);
            return date > 0 ? Math.max(1000, date - System.currentTimeMillis()) : 60_000;
        }
    }

    private boolean cancelled(long token) { return closed || generation.get() != token; }
    private void diagnostic(String message) { if (debugLogging) Log.d("YTMusicTVLyrics", message); }
    private static String field(JSONObject object, String key) {
        String value = text(object, key).trim();
        return value.substring(0, Math.min(value.length(), 512));
    }
    private static String text(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }
    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
    private static JSONObject status(String status, String message, int code) {
        JSONObject result = new JSONObject();
        try {
            result.put("status", status);
            result.put("message", message);
            if (code > 0) result.put("httpStatus", code);
        } catch (Exception ignored) { }
        return result;
    }

    private static final class Response {
        final int code;
        final JSONObject record;
        final JSONArray records;
        final JSONObject failure;
        Response(int code, JSONObject record, JSONArray records, JSONObject failure) {
            this.code = code; this.record = record; this.records = records; this.failure = failure;
        }
        static Response failed(JSONObject payload) {
            return new Response(payload.optInt("httpStatus"), null, null, payload);
        }
    }

    @Override public void close() {
        closed = true;
        generation.incrementAndGet();
        worker.shutdownNow();
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }
}
