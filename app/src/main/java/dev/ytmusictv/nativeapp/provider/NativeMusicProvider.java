package dev.ytmusictv.nativeapp.provider;

import android.os.Handler;
import android.util.Log;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native bounded catalogue adapter. No Music website or hidden playback renderer. */
public final class NativeMusicProvider implements AutoCloseable {
    public interface CookieSource { String getCookies(); }
    public interface Callback<T> { void onSuccess(T result); void onError(String message); }
    private static final String ORIGIN = "https://music.youtube.com";
    private static final int MAX_RESULTS = 60;
    private static final Pattern VERSION = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VISITOR = Pattern.compile("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"");
    private final Handler main;
    private final CookieSource cookieSource;
    private final boolean debugLogging;
    private final AtomicLong generation = new AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
    private volatile ProviderHttp active;
    private volatile boolean closed;
    private String clientVersion = "";
    private String visitorData = "";
    private String sessionIndex = "0";
    private String delegatedSessionId = "";
    private boolean bootstrapLoggedIn;
    private long clientLoadedAt;
    private boolean authenticatedBootstrap;
    private long cooldownUntil;

    public NativeMusicProvider(Handler mainHandler, CookieSource cookieSource) {
        this(mainHandler, cookieSource, false);
    }

    public NativeMusicProvider(Handler mainHandler, CookieSource cookieSource, boolean debugLogging) {
        main = mainHandler;
        this.cookieSource = cookieSource;
        this.debugLogging = debugLogging;
    }

    public void search(String query, Callback<List<NativeTrack>> callback) {
        final String text = query == null ? "" : query.trim();
        submit(callback, () -> {
            if (text.isEmpty()) return Collections.emptyList();
            if (text.length() > 256) throw new IOException("Search text is too long.");
            JSONObject body = new JSONObject().put("query", text);
            return parseTracks(api("search", body, false));
        });
    }

    /** First page of the account's liked songs; never silently substitutes guest results. */
    public void library(Callback<List<NativeTrack>> callback) {
        submit(callback, () -> {
            String cookies = cookies();
            if (cookies.isEmpty() || sessionId(cookies).isEmpty()) {
                throw new IOException("Sign in through Account, then open Library again.");
            }
            // LM is the account's Liked Music playlist. Despite its name,
            // FEmusic_liked_videos is the distinct saved-library Songs screen.
            JSONObject response = api("browse", new JSONObject().put("browseId", "VLLM"), true);
            List<NativeTrack> tracks = parseTracks(response);
            String messageKind = classifyLibraryMessage(response);
            if (debugLogging) Log.d("TVMusicNativeCatalog", "liked-track rows=" + tracks.size()
                    + " playlistShelves=" + countKey(response, "musicPlaylistShelfRenderer", 0)
                    + " responsiveRows=" + countKey(response, "musicResponsiveListItemRenderer", 0)
                    + " messageViews=" + countKey(response, "messageRenderer", 0)
                    + " messageKind=" + messageKind);
            if (debugLogging && tracks.isEmpty() && ("no_message".equals(messageKind) || "other_message".equals(messageKind)))
                Log.d("TVMusicNativeCatalog", "library message structure=" + messageStructure(response));
            if (tracks.isEmpty() && "sign_in_required".equals(messageKind))
                throw new IOException("Music requires sign-in for this library. Open Account and sign in again.");
            if (tracks.isEmpty() && ("unavailable".equals(messageKind) || "other_message".equals(messageKind)))
                throw new IOException("The account's liked-track list is unavailable. Refresh Library or check Account.");
            return tracks;
        });
    }

    private void submit(Callback<List<NativeTrack>> callback, Callable<List<NativeTrack>> operation) {
        if (closed) return;
        long token = generation.incrementAndGet();
        worker.getQueue().clear();
        ProviderHttp previous = active;
        if (previous != null) previous.close();
        try {
            worker.execute(() -> {
                if (closed || generation.get() != token) return;
                try {
                    List<NativeTrack> tracks = operation.call();
                    main.post(() -> { if (!closed && generation.get() == token) callback.onSuccess(tracks); });
                } catch (Exception error) {
                    String message = userMessage(error);
                    main.post(() -> { if (!closed && generation.get() == token) callback.onError(message); });
                }
            });
        } catch (RejectedExecutionException ignored) {
            main.post(() -> { if (!closed && generation.get() == token) callback.onError("Music is busy. Try again shortly."); });
        }
    }

    private JSONObject api(String method, JSONObject body, boolean authenticated) throws Exception {
        if (System.currentTimeMillis() < cooldownUntil) throw new IOException("Music service is busy. Try again later.");
        String cookieHeader = authenticated ? cookies() : "";
        loadClient(cookieHeader);
        JSONObject client = new JSONObject().put("clientName", "WEB_REMIX")
                .put("clientVersion", clientVersion).put("hl", "en").put("gl", "US");
        if (!visitorData.isEmpty()) client.put("visitorData", visitorData);
        JSONObject user = new JSONObject().put("lockedSafetyMode", false);
        if (authenticated && !delegatedSessionId.isEmpty()) user.put("onBehalfOfUser", delegatedSessionId);
        body.put("context", new JSONObject().put("client", client).put("user", user));
        Map<String, List<String>> headers = new HashMap<>();
        header(headers, "Content-Type", "application/json; charset=UTF-8");
        header(headers, "Accept", "application/json");
        header(headers, "Origin", ORIGIN);
        header(headers, "X-Origin", ORIGIN);
        header(headers, "Referer", ORIGIN + "/");
        header(headers, "X-Youtube-Client-Name", "67");
        header(headers, "X-Youtube-Client-Version", clientVersion);
        if (!visitorData.isEmpty()) header(headers, "X-Goog-Visitor-Id", visitorData);
        if (authenticated) {
            String sessionId = sessionId(cookieHeader);
            if (sessionId.isEmpty()) throw new IOException("Sign in through Account, then open Library again.");
            long timestamp = System.currentTimeMillis() / 1000;
            String hash = sha1(timestamp + " " + sessionId + " " + ORIGIN);
            header(headers, "Authorization", "SAPISIDHASH " + timestamp + "_" + hash);
            header(headers, "Cookie", cookieHeader);
            header(headers, "X-Goog-AuthUser", sessionIndex);
            if (!delegatedSessionId.isEmpty()) header(headers, "X-Goog-PageId", delegatedSessionId);
            if (bootstrapLoggedIn) header(headers, "X-Youtube-Bootstrap-Logged-In", "true");
        }
        ProviderHttp.Reply reply = request(ORIGIN + "/youtubei/v1/" + method + "?prettyPrint=false",
                "POST", headers, body.toString().getBytes(StandardCharsets.UTF_8), 2 * 1024 * 1024);
        if (debugLogging) Log.d("TVMusicNativeCatalog", method + " HTTP=" + reply.code + " authenticated=" + authenticated);
        if (reply.code == 429 || reply.code == 503) {
            cooldownUntil = System.currentTimeMillis() + retryAfter(reply);
            throw new IOException("Music service is busy. Try again later.");
        }
        if (reply.code == 401 || reply.code == 403) throw new IOException("Account access was rejected. Open Account and sign in again.");
        if (reply.code != 200) throw new IOException("Music service returned HTTP " + reply.code + ". Try again.");
        JSONObject result = new JSONObject(reply.body);
        if (debugLogging && authenticated) {
            JSONObject context = result.optJSONObject("responseContext");
            JSONObject web = context == null ? null : context.optJSONObject("mainAppWebResponseContext");
            Object loggedOut = web == null ? null : web.opt("loggedOut");
            Log.d("TVMusicNativeCatalog", "account response loggedOut="
                    + (loggedOut instanceof Boolean ? loggedOut.toString() : "unknown"));
        }
        if (result.has("error")) throw new IOException("Music service could not complete this request.");
        if (authenticated && result.optJSONObject("responseContext") != null
                && result.optJSONObject("responseContext").optJSONObject("mainAppWebResponseContext") != null
                && result.optJSONObject("responseContext").optJSONObject("mainAppWebResponseContext")
                .optBoolean("loggedOut", false)) {
            throw new IOException("Account access was rejected. Open Account and sign in again.");
        }
        return result;
    }

    private void loadClient(String cookieHeader) throws Exception {
        if (!clientVersion.isEmpty() && authenticatedBootstrap == !cookieHeader.isEmpty()
                && System.currentTimeMillis() - clientLoadedAt < 6 * 60 * 60_000L) return;
        Map<String, List<String>> headers = new HashMap<>();
        header(headers, "Accept-Language", "en-US,en;q=0.9");
        if (!cookieHeader.isEmpty()) header(headers, "Cookie", cookieHeader);
        ProviderHttp.Reply reply = request(ORIGIN + "/", "GET", headers, null, 3 * 1024 * 1024);
        if (reply.code != 200) throw new IOException("Music could not load its catalogue configuration.");
        Matcher version = VERSION.matcher(reply.body);
        if (!version.find()) throw new IOException("Music changed its catalogue format. Try again after an app update.");
        clientVersion = version.group(1);
        Matcher visitor = VISITOR.matcher(reply.body);
        visitorData = visitor.find() ? visitor.group(1) : "";
        Object configuredIndex = configValue(reply.body, "SESSION_INDEX");
        String index = configuredIndex == null ? "" : configuredIndex.toString();
        sessionIndex = index.matches("\\d{1,2}") ? index : "0";
        Object delegated = configValue(reply.body, "DELEGATED_SESSION_ID");
        String delegatedId = delegated instanceof String ? (String) delegated : "";
        delegatedSessionId = delegatedId.matches("[A-Za-z0-9_-]{1,128}") ? delegatedId : "";
        bootstrapLoggedIn = Boolean.TRUE.equals(configValue(reply.body, "LOGGED_IN"));
        if (debugLogging && !cookieHeader.isEmpty()) Log.d("TVMusicNativeCatalog", "bootstrap loggedIn="
                + bootstrapLoggedIn + " sessionIndexPresent=" + !index.isEmpty()
                + " delegatedChannel=" + !delegatedSessionId.isEmpty());
        clientLoadedAt = System.currentTimeMillis();
        authenticatedBootstrap = !cookieHeader.isEmpty();
    }

    private ProviderHttp.Reply request(String url, String method, Map<String, List<String>> headers,
                                       byte[] body, int limit) throws IOException {
        ProviderHttp http = new ProviderHttp();
        active = http;
        try { return http.request(url, method, headers, body, limit); }
        finally { http.close(); if (active == http) active = null; }
    }

    private String cookies() {
        String value = cookieSource == null ? "" : cookieSource.getCookies();
        return value == null || value.length() > 32 * 1024 ? "" : value;
    }
    private static String sessionId(String cookies) {
        String fallback = "";
        for (String pair : cookies.split(";")) {
            int split = pair.indexOf('=');
            if (split < 0) continue;
            String name = pair.substring(0, split).trim();
            String value = pair.substring(split + 1).trim();
            if (name.equals("SAPISID")) return value;
            if (name.equals("__Secure-3PAPISID") || name.equals("__Secure-1PAPISID")) fallback = value;
        }
        return fallback;
    }

    static List<NativeTrack> parseTracks(JSONObject response) {
        LinkedHashMap<String, NativeTrack> tracks = new LinkedHashMap<>();
        collect(response, tracks, 0, new int[]{0});
        return Collections.unmodifiableList(new ArrayList<>(tracks.values()));
    }
    private static int countKey(Object value, String wanted, int depth) {
        if (depth > 40) return 0;
        int count = 0;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has(wanted)) count++;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && count < 100) count += countKey(object.opt(keys.next()), wanted, depth + 1);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < Math.min(array.length(), 500) && count < 100; index++)
                count += countKey(array.opt(index), wanted, depth + 1);
        }
        return count;
    }
    static Object configValue(String html, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|true|false|null|\\d+)");
        Matcher matcher = pattern.matcher(html);
        Object last = null;
        while (matcher.find()) {
            try {
                Object parsed = new JSONTokener(matcher.group(1)).nextValue();
                last = parsed == JSONObject.NULL ? null : parsed;
            } catch (Exception ignored) { }
        }
        return last;
    }
    static String classifyLibraryMessage(JSONObject response) {
        StringBuilder message = new StringBuilder();
        collectMessages(response, message, 0, new int[]{0});
        String value = message.toString().toLowerCase(java.util.Locale.ROOT).replace('\u2019', '\'').trim();
        if (value.isEmpty()) return "no_message";
        if (value.contains("sign in") || value.contains("sign-in") || value.contains("log in")) return "sign_in_required";
        if (value.contains("no songs") || value.contains("no tracks") || value.contains("no music")
                || value.contains("no videos") || value.contains("no items") || value.contains("playlist is empty")
                || value.contains("appear here") || value.contains("you haven't liked")
                || value.contains("start liking") || value.contains("songs you like")
                || value.contains("music you like") || value.contains("like songs")) return "empty_likes";
        if (value.contains("not available") || value.contains("unavailable") || value.contains("not found")
                || value.contains("does not exist") || value.contains("private") || value.contains("permission")) return "unavailable";
        return "other_message";
    }
    private static void collectMessages(Object value, StringBuilder target, int depth, int[] visited) {
        if (depth > 40 || visited[0]++ > 20_000 || target.length() >= 4096) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("messageRenderer")) {
                collectMessageText(object.opt("messageRenderer"), target, 0, new int[]{0});
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && target.length() < 4096 && visited[0] <= 20_000)
                collectMessages(object.opt(keys.next()), target, depth + 1, visited);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length() && target.length() < 4096 && visited[0] <= 20_000; index++)
                collectMessages(array.opt(index), target, depth + 1, visited);
        }
    }
    private static void collectMessageText(Object value, StringBuilder target, int depth, int[] visited) {
        if (depth > 12 || visited[0]++ > 1000 || target.length() >= 4096) return;
        if (value instanceof String) {
            String text = (String) value;
            int remaining = Math.max(0, 4095 - target.length());
            target.append(text, 0, Math.min(text.length(), remaining)).append(' ');
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            // Do not inspect tokens, endpoint URLs, identifiers, or arbitrary string fields.
            for (String key : new String[]{"text", "simpleText", "runs", "subtext", "title", "subtitle",
                    "message", "messageText", "content", "primaryText", "secondaryText", "label"}) {
                if (object.has(key)) collectMessageText(object.opt(key), target, depth + 1, visited);
                if (target.length() >= 4096) break;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < Math.min(array.length(), 100) && target.length() < 4096; index++)
                collectMessageText(array.opt(index), target, depth + 1, visited);
        }
    }
    static String messageStructure(JSONObject response) {
        Object message = firstMessage(response, 0, new int[]{0});
        if (!(message instanceof JSONObject)) return valueType(message);
        JSONObject object = (JSONObject) message;
        StringBuilder result = new StringBuilder("object{");
        java.util.Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext() && count++ < 12) {
            String key = keys.next();
            if (count > 1) result.append(',');
            // Schema keys only, never their values; unexpected key shapes are redacted.
            result.append(key.matches("[A-Za-z][A-Za-z0-9_]{0,47}") ? key : "other")
                    .append(':').append(valueType(object.opt(key)));
        }
        return result.append('}').toString();
    }
    private static Object firstMessage(Object value, int depth, int[] visited) {
        if (depth > 40 || visited[0]++ > 20_000) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("messageRenderer")) return object.opt("messageRenderer");
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && visited[0] <= 20_000) {
                Object found = firstMessage(object.opt(keys.next()), depth + 1, visited);
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length() && visited[0] <= 20_000; index++) {
                Object found = firstMessage(array.opt(index), depth + 1, visited);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static String valueType(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return "string";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return "other";
    }
    private static void collect(Object value, LinkedHashMap<String, NativeTrack> tracks, int depth, int[] visited) {
        if (depth > 48 || visited[0]++ > 20_000 || tracks.size() >= MAX_RESULTS) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject row = object.optJSONObject("musicResponsiveListItemRenderer");
            if (row != null) {
                NativeTrack track = track(row);
                if (track != null) tracks.putIfAbsent(track.videoId, track);
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && tracks.size() < MAX_RESULTS && visited[0] <= 20_000)
                collect(object.opt(keys.next()), tracks, depth + 1, visited);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length() && tracks.size() < MAX_RESULTS && visited[0] <= 20_000; index++)
                collect(array.opt(index), tracks, depth + 1, visited);
        }
    }
    private static NativeTrack track(JSONObject row) {
        String id = pathString(row, "playlistItemData", "videoId");
        JSONArray columns = row.optJSONArray("flexColumns");
        if (columns == null || columns.length() == 0) return null;
        JSONObject titleText = columnText(columns.optJSONObject(0));
        if (id.isEmpty()) id = firstVideoId(titleText);
        if (!id.matches("[A-Za-z0-9_-]{11}")) return null;
        String title = text(titleText);
        if (title.isEmpty() || title.equals("[Deleted video]") || title.equals("[Private video]")
                || title.equals("Deleted video") || title.equals("Private video")) return null;
        String artist = "", album = "";
        int duration = 0;
        for (int index = 1; index < columns.length(); index++) {
            JSONObject description = columnText(columns.optJSONObject(index));
            JSONArray runs = description.optJSONArray("runs");
            if (runs == null) continue;
            StringBuilder artists = new StringBuilder();
            for (int run = 0; run < runs.length(); run++) {
                JSONObject item = runs.optJSONObject(run);
                if (item == null) continue;
                String label = item.optString("text", "");
                JSONObject endpoint = item.optJSONObject("navigationEndpoint");
                JSONObject browse = endpoint == null ? null : endpoint.optJSONObject("browseEndpoint");
                String type = browse == null ? "" : pathString(browse,
                        "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType");
                if (type.contains("ALBUM")) album = label;
                else if (type.contains("ARTIST") || type.contains("USER_CHANNEL")) {
                    if (artists.length() > 0) artists.append(", ");
                    artists.append(label);
                }
                int seconds = duration(label);
                if (seconds > 0) duration = seconds;
            }
            if (artists.length() > 0 && artist.isEmpty()) artist = artists.toString();
            if (artist.isEmpty() && index == 1) {
                String[] segments = text(description).split("\\s*[•·]\\s*");
                if (segments.length > 1 && (segments[0].equals("Song") || segments[0].equals("Video") || segments[0].equals("Episode"))) artist = segments[1];
                else if (segments.length > 0 && !segments[0].matches(".*(?i:plays|views|songs|likes).*")) artist = segments[0];
            }
        }
        JSONArray fixed = row.optJSONArray("fixedColumns");
        if (fixed != null) for (int index = 0; index < fixed.length(); index++) {
            JSONObject c = fixed.optJSONObject(index);
            JSONObject r = c == null ? null : c.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
            int seconds = duration(text(r == null ? null : r.optJSONObject("text")));
            if (seconds > 0) duration = seconds;
        }
        JSONObject thumbnail = row.optJSONObject("thumbnail");
        JSONObject musicThumbnail = thumbnail == null ? null : thumbnail.optJSONObject("musicThumbnailRenderer");
        JSONObject thumbnails = musicThumbnail == null ? null : musicThumbnail.optJSONObject("thumbnail");
        String image = thumbnail(thumbnails == null ? null : thumbnails.optJSONArray("thumbnails"));
        String videoType = pathString(row, "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType");
        if (videoType.isEmpty()) videoType = pathString(titleText, "runs", "0", "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType");
        return new NativeTrack(id, title, artist, album, duration, image,
                videoType.contains("OMV") || videoType.contains("UGC") || text(columnText(columns.optJSONObject(1))).startsWith("Video"));
    }

    private static JSONObject columnText(JSONObject column) {
        JSONObject renderer = column == null ? null : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text = renderer == null ? null : renderer.optJSONObject("text");
        return text == null ? new JSONObject() : text;
    }
    private static String firstVideoId(JSONObject text) {
        JSONArray runs = text.optJSONArray("runs");
        if (runs != null) for (int i = 0; i < runs.length(); i++) {
            String id = pathString(runs.optJSONObject(i), "navigationEndpoint", "watchEndpoint", "videoId");
            if (!id.isEmpty()) return id;
        }
        return "";
    }
    private static String text(JSONObject text) {
        if (text == null) return "";
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) return text.optString("simpleText", "");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null && builder.length() < 2048) builder.append(run.optString("text", ""));
        }
        return builder.toString();
    }
    private static String pathString(JSONObject object, String... path) {
        Object current = object;
        for (String key : path) {
            if (current instanceof JSONObject) current = ((JSONObject) current).opt(key);
            else if (current instanceof JSONArray) {
                try { current = ((JSONArray) current).opt(Integer.parseInt(key)); }
                catch (NumberFormatException ignored) { return ""; }
            } else return "";
        }
        return current instanceof String ? (String) current : "";
    }
    private static String thumbnail(JSONArray entries) {
        if (entries == null) return "";
        String best = "";
        int bestPixels = -1;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String url = entry.optString("url", "");
            if (!url.startsWith("https://")) continue;
            int pixels = Math.max(0, Math.max(entry.optInt("width", 0), entry.optInt("height", 0)));
            // Prefer the smallest supplied rendition sufficient for TV artwork;
            // otherwise use the largest available, never the old closest-to-320 choice.
            boolean better = best.isEmpty() || (bestPixels < 640 ? pixels > bestPixels
                    : pixels >= 640 && pixels < bestPixels);
            if (better) { best = url; bestPixels = pixels; }
        }
        return best;
    }
    private static int duration(String value) {
        if (!value.matches("\\d{1,2}:\\d{2}(?::\\d{2})?")) return 0;
        int seconds = 0;
        for (String part : value.split(":")) seconds = seconds * 60 + Integer.parseInt(part);
        return seconds;
    }
    private static String sha1(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(40);
        for (byte value : digest) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
    private static void header(Map<String, List<String>> headers, String name, String value) {
        headers.put(name, Collections.singletonList(value));
    }
    private static long retryAfter(ProviderHttp.Reply response) {
        for (Map.Entry<String, List<String>> header : response.headers.entrySet()) {
            if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Retry-After") || header.getValue().isEmpty()) continue;
            try { return Math.min(86_400_000L, Math.max(1000, Long.parseLong(header.getValue().get(0)) * 1000)); }
            catch (NumberFormatException ignored) { }
        }
        return 60_000;
    }
    private static String userMessage(Exception error) {
        // Only our known fixed messages may reach the UI; upstream errors may contain URLs.
        String message = error.getMessage();
        if (error instanceof IOException && message != null && !message.contains("http:") && !message.contains("https:")) return message;
        return "Music could not be loaded. Check the connection and try again.";
    }
    @Override public void close() {
        closed = true;
        generation.incrementAndGet();
        worker.shutdownNow();
        ProviderHttp request = active;
        if (request != null) request.close();
    }
}
