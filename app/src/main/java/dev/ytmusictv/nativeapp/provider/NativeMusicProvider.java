package dev.ytmusictv.nativeapp.provider;

import android.os.Handler;
import android.util.Log;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import dev.ytmusictv.nativeapp.model.NativeCollection;
import dev.ytmusictv.nativeapp.model.NativeArtistPage;
import dev.ytmusictv.nativeapp.model.NativeAlbumPage;
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
import java.util.HashSet;
import java.util.Set;
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
    private final ThreadLocal<Long> taskGeneration = new ThreadLocal<>();
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

    public void searchArtists(String query, Callback<List<NativeCollection>> callback) {
        searchCollections(query, NativeCollection.Kind.ARTIST, callback);
    }

    public void searchAlbums(String query, Callback<List<NativeCollection>> callback) {
        searchCollections(query, NativeCollection.Kind.ALBUM, callback);
    }

    private void searchCollections(String query, NativeCollection.Kind kind, Callback<List<NativeCollection>> callback) {
        final String input = query == null ? "" : query.trim();
        submit(callback, () -> {
            if (input.isEmpty()) return Collections.emptyList();
            if (input.length() > 256) throw new IOException("Search text is too long.");
            String filter = kind == NativeCollection.Kind.ARTIST ? "EgWKAQIgAWoMEA4QChADEAQQCRAF" : "EgWKAQIYAWoMEA4QChADEAQQCRAF";
            return parseCollections(api("search", new JSONObject().put("query", input).put("params", filter), false), kind);
        });
    }

    public void artist(NativeCollection artist, Callback<NativeArtistPage> callback) {
        submit(callback, () -> loadArtist(artist));
    }
    NativeArtistPage loadArtist(NativeCollection artist) throws Exception {
            validateCollection(artist, NativeCollection.Kind.ARTIST);
            JSONObject response = api("browse", new JSONObject().put("browseId", artist.id), false);
            LinkedHashMap<String, NativeCollection> albums = new LinkedHashMap<>();
            for (NativeCollection album : parseCollections(response, NativeCollection.Kind.ALBUM)) albums.put(album.id, album);
            List<JSONObject> more = releasePages(response);
            boolean complete = true;
            int pages = 0;
            for (JSONObject endpoint : more) {
                if (pages++ >= 2 || albums.size() >= MAX_RESULTS) { complete = false; break; }
                JSONObject body = new JSONObject().put("browseId", endpoint.optString("browseId"));
                if (!endpoint.optString("params").isEmpty()) body.put("params", endpoint.optString("params"));
                JSONObject all = api("browse", body, false);
                for (NativeCollection album : parseCollections(all, NativeCollection.Kind.ALBUM)) {
                    if (albums.size() >= MAX_RESULTS) { complete = false; break; }
                    albums.putIfAbsent(album.id, album);
                }
                if (!continuation(all).isEmpty()) complete = false;
            }
            if (albums.size() >= MAX_RESULTS) complete = false;
            return new NativeArtistPage(artist, new ArrayList<>(albums.values()), complete);
    }

    public void album(NativeCollection album, Callback<NativeAlbumPage> callback) {
        submit(callback, () -> loadAlbum(album));
    }
    NativeAlbumPage loadAlbum(NativeCollection album) throws Exception {
            validateCollection(album, NativeCollection.Kind.ALBUM);
            JSONObject request = new JSONObject().put("browseId", album.id);
            if (!album.browseParams.isEmpty()) request.put("params", album.browseParams);
            JSONObject response = api("browse", request, false);
            if (albumShelf(response) == null) throw new IOException("Album tracks are unavailable. Refresh and try again.");
            JSONObject header = albumHeader(response);
            String albumArtist = albumArtist(header);
            String albumImage = albumArtwork(header);
            if (albumImage.isEmpty()) albumImage = secureArtwork(album.thumbnailUrl);
            List<NativeTrack> tracks = new ArrayList<>(albumMetadata(parseAlbumTracks(response, 200), albumArtist, album.title, albumImage));
            boolean allRowsReadable = tracks.size() == albumRowCount(albumShelf(response));
            String next = continuation(albumShelf(response));
            Set<String> seen = new HashSet<>();
            int pages = 0;
            while (!next.isEmpty() && tracks.size() < 200 && pages++ < 3 && seen.add(next)) {
                JSONObject following = api("browse", new JSONObject().put("continuation", next), false);
                if (albumShelf(following) == null) break;
                List<NativeTrack> additional = albumMetadata(parseAlbumTracks(following, 200 - tracks.size()), albumArtist, album.title, albumImage);
                allRowsReadable &= additional.size() == albumRowCount(albumShelf(following));
                tracks.addAll(additional);
                next = continuation(albumShelf(following));
                if (additional.isEmpty()) break;
            }
            return new NativeAlbumPage(album, tracks, allRowsReadable && next.isEmpty() && tracks.size() < 200);
    }

    static List<NativeTrack> parseAlbumTracks(JSONObject response, int limit) throws Exception {
        return parseAlbumTracks(response, limit, "");
    }
    static List<NativeTrack> parseAlbumTracks(JSONObject response, int limit, String fallbackImage) throws Exception {
        JSONObject shelf = albumShelf(response);
        if (shelf == null) return Collections.emptyList();
        JSONArray contents = shelf.optJSONArray("contents");
        if (contents == null) return Collections.emptyList();
        JSONObject header = albumHeader(response);
        String image = albumArtwork(header);
        if (image.isEmpty()) image = secureArtwork(fallbackImage);
        return albumMetadata(parseTrackRows(new JSONObject().put("rows", contents), limit, true),
                albumArtist(header), header == null ? "" : text(header.optJSONObject("title")), image);
    }
    private static String secureArtwork(String url) { return url != null && url.startsWith("https://") ? url : ""; }
    private static String albumArtwork(JSONObject header) {
        JSONObject image = header == null ? null : header.optJSONObject("thumbnail");
        JSONObject renderer = image == null ? null : image.optJSONObject("musicThumbnailRenderer");
        JSONObject thumbnails = renderer == null ? null : renderer.optJSONObject("thumbnail");
        return thumbnail(thumbnails == null ? null : thumbnails.optJSONArray("thumbnails"));
    }
    private static List<NativeTrack> albumMetadata(List<NativeTrack> rows, String artist, String title, String image) {
        List<NativeTrack> result = new ArrayList<>(rows.size());
        for (NativeTrack track : rows) result.add(new NativeTrack(track.videoId, track.title,
                track.artist.isEmpty() ? artist : track.artist, track.album.isEmpty() ? title : track.album,
                track.durationSeconds, track.thumbnailUrl.isEmpty() ? image : track.thumbnailUrl, track.isVideoSource));
        return Collections.unmodifiableList(result);
    }
    private static JSONObject albumHeader(JSONObject response) {
        JSONObject header = response.optJSONObject("header");
        JSONObject detail = header == null ? null : header.optJSONObject("musicDetailHeaderRenderer");
        if (detail != null) return detail;
        JSONObject contents = response.optJSONObject("contents");
        JSONObject columns = contents == null ? null : contents.optJSONObject("twoColumnBrowseResultsRenderer");
        if (columns == null && contents != null) columns = contents.optJSONObject("singleColumnBrowseResultsRenderer");
        JSONArray tabs = columns == null ? null : columns.optJSONArray("tabs");
        if (tabs == null) return null;
        for (int i = 0; i < Math.min(tabs.length(), 10); i++) {
            JSONObject item = tabs.optJSONObject(i);
            JSONObject tab = item == null ? null : item.optJSONObject("tabRenderer");
            if (tab == null || (tabs.length() > 1 && !tab.optBoolean("selected"))) continue;
            JSONObject content = tab.optJSONObject("content");
            JSONObject section = content == null ? null : content.optJSONObject("sectionListRenderer");
            JSONArray sections = section == null ? null : section.optJSONArray("contents");
            if (sections == null) continue;
            for (int j = 0; j < Math.min(sections.length(), 100); j++) {
                JSONObject row = sections.optJSONObject(j);
                JSONObject found = row == null ? null : row.optJSONObject("musicResponsiveHeaderRenderer");
                if (found != null) return found;
            }
        }
        return null;
    }
    private static String albumArtist(JSONObject header) {
        if (header == null) return "";
        StringBuilder artists = new StringBuilder();
        for (String key : new String[]{"straplineTextOne", "subtitle"}) {
            JSONObject label = header.optJSONObject(key);
            JSONArray runs = label == null ? null : label.optJSONArray("runs");
            if (runs == null) continue;
            for (int i = 0; i < Math.min(runs.length(), 100); i++) {
                JSONObject run = runs.optJSONObject(i);
                JSONObject endpoint = run == null ? null : run.optJSONObject("navigationEndpoint");
                JSONObject browse = endpoint == null ? null : endpoint.optJSONObject("browseEndpoint");
                if (browse == null || !browse.optString("browseId").startsWith("UC")) continue;
                String name = run.optString("text", "").trim();
                if (!name.isEmpty()) {
                    if (artists.length() > 0) artists.append(", ");
                    artists.append(name);
                }
            }
            if (artists.length() > 0) break;
        }
        return artists.toString();
    }
    // Album browse responses already contain the selected edition's ordered track list.
    // Read only known primary sections: recursive traversal can pick recommendation shelves.
    private static JSONObject albumShelf(JSONObject response) throws Exception {
        JSONObject continuation = response.optJSONObject("continuationContents");
        if (continuation != null) for (String key : new String[]{"musicPlaylistShelfContinuation", "musicShelfContinuation"}) {
            JSONObject shelf = continuation.optJSONObject(key);
            if (shelf != null) return shelf;
        }
        for (String key : new String[]{"onResponseReceivedActions", "onResponseReceivedEndpoints"}) {
            JSONArray actions = response.optJSONArray(key);
            if (actions == null) continue;
            for (int i = 0; i < Math.min(actions.length(), 20); i++) {
                JSONObject action = actions.optJSONObject(i);
                JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
                JSONArray items = append == null ? null : append.optJSONArray("continuationItems");
                if (items != null) return new JSONObject().put("contents", items);
            }
        }
        JSONObject contents = response.optJSONObject("contents");
        if (contents == null) return null;
        JSONObject columns = contents.optJSONObject("twoColumnBrowseResultsRenderer");
        if (columns != null) {
            JSONObject shelf = sectionShelf(columns.optJSONObject("secondaryContents"));
            if (shelf != null) return shelf;
        } else columns = contents.optJSONObject("singleColumnBrowseResultsRenderer");
        if (columns != null) {
            JSONArray tabs = columns.optJSONArray("tabs");
            if (tabs != null) for (int i = 0; i < Math.min(tabs.length(), 10); i++) {
                JSONObject item = tabs.optJSONObject(i);
                JSONObject tab = item == null ? null : item.optJSONObject("tabRenderer");
                // Unselected tabs can contain other catalogue views.
                if (tab == null || (tabs.length() > 1 && !tab.optBoolean("selected"))) continue;
                JSONObject shelf = sectionShelf(tab.optJSONObject("content"));
                if (shelf != null) return shelf;
            }
        }
        return sectionShelf(contents);
    }
    private static JSONObject sectionShelf(JSONObject sectionRoot) {
        JSONObject section = sectionRoot == null ? null : sectionRoot.optJSONObject("sectionListRenderer");
        JSONArray sections = section == null ? null : section.optJSONArray("contents");
        if (sections == null) return null;
        for (int i = 0; i < Math.min(sections.length(), 100); i++) {
            JSONObject item = sections.optJSONObject(i);
            if (item == null) continue;
            JSONObject shelf = item.optJSONObject("musicPlaylistShelfRenderer");
            if (shelf != null) return shelf;
            // Current album pages use an untitled musicShelfRenderer in secondaryContents.
            shelf = item.optJSONObject("musicShelfRenderer");
            if (shelf != null && text(shelf.optJSONObject("title")).isEmpty()) return shelf;
        }
        return null;
    }
    private static int albumRowCount(JSONObject shelf) {
        JSONArray rows = shelf == null ? null : shelf.optJSONArray("contents");
        int count = 0;
        if (rows != null) for (int i = 0; i < Math.min(rows.length(), 20_000); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.optJSONObject("musicResponsiveListItemRenderer") != null) count++;
        }
        return count;
    }

    private static void validateCollection(NativeCollection collection, NativeCollection.Kind kind) throws IOException {
        if (collection == null || collection.kind != kind || !collection.id.matches("[A-Za-z0-9_-]{2,256}"))
            throw new IOException("This catalogue item is unavailable.");
    }

    static List<NativeCollection> parseCollections(JSONObject response, NativeCollection.Kind kind) {
        LinkedHashMap<String, NativeCollection> result = new LinkedHashMap<>();
        collectCollections(response, kind, result, 0, new int[]{0});
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }
    private static void collectCollections(Object value, NativeCollection.Kind kind,
            LinkedHashMap<String, NativeCollection> result, int depth, int[] visited) {
        if (depth > 48 || visited[0]++ > 20_000 || result.size() >= MAX_RESULTS) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String type : new String[]{"musicResponsiveListItemRenderer", "musicTwoRowItemRenderer"}) {
                JSONObject row = object.optJSONObject(type);
                if (row != null) {
                    NativeCollection collection = collection(row, kind);
                    if (collection != null) result.putIfAbsent(collection.id, collection);
                    return;
                }
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && result.size() < MAX_RESULTS && visited[0] <= 20_000)
                collectCollections(object.opt(keys.next()), kind, result, depth + 1, visited);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length() && result.size() < MAX_RESULTS && visited[0] <= 20_000; index++)
                collectCollections(array.opt(index), kind, result, depth + 1, visited);
        }
    }
    private static NativeCollection collection(JSONObject row, NativeCollection.Kind kind) {
        JSONArray columns = row.optJSONArray("flexColumns");
        JSONObject titleObject = columns == null ? row.optJSONObject("title") : columnText(columns.optJSONObject(0));
        JSONObject endpoint = row.optJSONObject("navigationEndpoint");
        JSONObject browse = endpoint == null ? null : endpoint.optJSONObject("browseEndpoint");
        if (browse == null && titleObject != null) {
            JSONArray runs = titleObject.optJSONArray("runs");
            if (runs != null) for (int index = 0; index < runs.length() && browse == null; index++) {
                JSONObject run = runs.optJSONObject(index);
                JSONObject navigation = run == null ? null : run.optJSONObject("navigationEndpoint");
                browse = navigation == null ? null : navigation.optJSONObject("browseEndpoint");
            }
        }
        if (browse == null) return null;
        String id = browse.optString("browseId", "");
        boolean valid = kind == NativeCollection.Kind.ARTIST ? id.startsWith("UC") : id.startsWith("MPRE");
        String title = text(titleObject);
        if (!valid || !id.matches("[A-Za-z0-9_-]{2,256}") || title.isEmpty()) return null;
        String subtitle = columns == null ? text(row.optJSONObject("subtitle")) : text(columnText(columns.optJSONObject(1)));
        JSONObject thumbnailRoot = row.optJSONObject("thumbnailRenderer");
        if (thumbnailRoot == null) thumbnailRoot = row.optJSONObject("thumbnail");
        JSONObject image = thumbnailRoot == null ? null : thumbnailRoot.optJSONObject("musicThumbnailRenderer");
        JSONObject images = image == null ? null : image.optJSONObject("thumbnail");
        return new NativeCollection(id, title, subtitle,
                thumbnail(images == null ? null : images.optJSONArray("thumbnails")), kind, browse.optString("params", ""));
    }
    private static List<JSONObject> releasePages(JSONObject response) {
        LinkedHashMap<String, JSONObject> pages = new LinkedHashMap<>();
        collectReleasePages(response, pages, 0, new int[]{0});
        return new ArrayList<>(pages.values());
    }
    private static void collectReleasePages(Object value, LinkedHashMap<String, JSONObject> pages, int depth, int[] visited) {
        if (depth > 40 || visited[0]++ > 20_000 || pages.size() >= 3) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject shelf = object.optJSONObject("musicCarouselShelfRenderer");
            JSONObject header = shelf == null ? null : shelf.optJSONObject("header");
            JSONObject basic = header == null ? null : header.optJSONObject("musicCarouselShelfBasicHeaderRenderer");
            if (basic != null) {
                String label = text(basic.optJSONObject("title")).toLowerCase(java.util.Locale.ROOT);
                if (label.contains("album") || label.contains("single") || label.contains("ep")) {
                    JSONObject endpoint = firstBrowse(basic, 0);
                    if (endpoint != null && !endpoint.optString("browseId").isEmpty())
                        pages.putIfAbsent(endpoint.optString("browseId") + '\n' + endpoint.optString("params"), endpoint);
                }
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && visited[0] <= 20_000) collectReleasePages(object.opt(keys.next()), pages, depth + 1, visited);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && visited[0] <= 20_000; i++) collectReleasePages(array.opt(i), pages, depth + 1, visited);
        }
    }
    private static JSONObject firstBrowse(Object value, int depth) {
        if (depth > 12) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject endpoint = object.optJSONObject("browseEndpoint");
            if (endpoint != null) return endpoint;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                JSONObject found = firstBrowse(object.opt(keys.next()), depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < Math.min(array.length(), 100); i++) {
                JSONObject found = firstBrowse(array.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static String continuation(JSONObject response) { return continuationValue(response, 0, new int[]{0}); }
    private static String continuationValue(Object value, int depth, int[] visited) {
        if (depth > 40 || visited[0]++ > 20_000) return "";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : new String[]{"nextContinuationData", "reloadContinuationData", "continuationCommand"}) {
                JSONObject data = object.optJSONObject(key);
                String token = data == null ? "" : data.optString(key.equals("continuationCommand") ? "token" : "continuation", "");
                if (!token.isEmpty() && token.length() <= 16_384) return token;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && visited[0] <= 20_000) {
                String key = keys.next();
                if (key.equals("menu") || key.equals("header") || key.equals("thumbnail")) continue;
                String found = continuationValue(object.opt(key), depth + 1, visited);
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && visited[0] <= 20_000; i++) {
                String found = continuationValue(array.opt(i), depth + 1, visited);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
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

    private <T> void submit(Callback<T> callback, Callable<T> operation) {
        if (closed) return;
        long token = generation.incrementAndGet();
        worker.getQueue().clear();
        ProviderHttp previous = active;
        if (previous != null) previous.close();
        try {
            worker.execute(() -> {
                if (closed || generation.get() != token) return;
                taskGeneration.set(token);
                try {
                    T result = operation.call();
                    main.post(() -> { if (!closed && generation.get() == token) callback.onSuccess(result); });
                } catch (Exception error) {
                    String message = userMessage(error);
                    main.post(() -> { if (!closed && generation.get() == token) callback.onError(message); });
                } finally { taskGeneration.remove(); }
            });
        } catch (RejectedExecutionException ignored) {
            main.post(() -> { if (!closed && generation.get() == token) callback.onError("Music is busy. Try again shortly."); });
        }
    }

    private JSONObject api(String method, JSONObject body, boolean authenticated) throws Exception {
        checkCancelled();
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
        checkCancelled();
        ProviderHttp http = new ProviderHttp();
        active = http;
        try { return http.request(url, method, headers, body, limit); }
        finally { http.close(); if (active == http) active = null; }
    }
    private void checkCancelled() throws IOException {
        Long token = taskGeneration.get();
        if (closed || Thread.currentThread().isInterrupted() || (token != null && generation.get() != token))
            throw new IOException("Request cancelled.");
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
        return parseTrackRows(response, MAX_RESULTS, false);
    }
    private static List<NativeTrack> parseTrackRows(JSONObject response, int limit, boolean preserveDuplicates) {
        LinkedHashMap<String, NativeTrack> tracks = new LinkedHashMap<>();
        collect(response, tracks, 0, new int[]{0}, Math.min(200, limit), preserveDuplicates);
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
    private static void collect(Object value, LinkedHashMap<String, NativeTrack> tracks, int depth, int[] visited, int limit, boolean preserveDuplicates) {
        if (depth > 48 || visited[0]++ > 20_000 || tracks.size() >= limit) return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject row = object.optJSONObject("musicResponsiveListItemRenderer");
            if (row != null) {
                NativeTrack track = track(row);
                if (track != null) tracks.putIfAbsent(preserveDuplicates ? Integer.toString(tracks.size()) : track.videoId, track);
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && tracks.size() < limit && visited[0] <= 20_000)
                collect(object.opt(keys.next()), tracks, depth + 1, visited, limit, preserveDuplicates);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length() && tracks.size() < limit && visited[0] <= 20_000; index++)
                collect(array.opt(index), tracks, depth + 1, visited, limit, preserveDuplicates);
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
