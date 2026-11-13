package dev.ytmusictv.nativeapp.provider;

import dev.ytmusictv.nativeapp.model.NativeTrack;
import dev.ytmusictv.nativeapp.model.NativeCollection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real, sanitized public catalogue rows plus adversarial bounded-parser cases. */
public final class NativeProviderParserTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path fixtureDir = Path.of(args[0]);
        JSONObject search = new JSONObject(Files.readString(fixtureDir.resolve("public-search.json")));
        JSONObject library = new JSONObject(Files.readString(fixtureDir.resolve("library-shelf.json")));
        List<NativeTrack> found = NativeMusicProvider.parseTracks(search);
        equal(4, found.size());
        equal("LBhcqYqeu0U", found.get(0).videoId);
        equal("LEMONADE", found.get(0).title);
        equal("aespa", found.get(0).artist);
        check(!found.get(0).isVideoSource, "ATV is a song representation");
        check(found.get(0).thumbnailUrl.startsWith("https://"), "secure thumbnail retained");
        check(found.get(0).thumbnailUrl.contains("=w120-h120"), "largest provided small thumbnail is retained for loader promotion");
        equal(0, found.get(0).durationSeconds); // Unknown must stay unknown, not a fabricated clock.

        List<NativeTrack> liked = NativeMusicProvider.parseTracks(library);
        equal(3, liked.size());
        equal("iTJSbJtS8MU", liked.get(0).videoId);
        equal("aespa", liked.get(0).artist);
        equal(174, liked.get(0).durationSeconds);
        check(liked.get(0).isVideoSource, "OMV retains video identity");

        JSONObject album = new JSONObject(Files.readString(fixtureDir.resolve("album-easy.json")));
        JSONObject artistAlbums = new JSONObject(Files.readString(fixtureDir.resolve("artist-albums.json")));
        List<NativeCollection> releases = NativeMusicProvider.parseCollections(artistAlbums, NativeCollection.Kind.ALBUM);
        equal(1, releases.size());
        equal("EASY", releases.get(0).title);
        equal("MPREb_1d55KGW6MTI", releases.get(0).id);
        check(!releases.get(0).browseParams.isEmpty(), "album card keeps its selected edition params");
        equal(0, NativeMusicProvider.parseCollections(artistAlbums, NativeCollection.Kind.ARTIST).size());
        List<NativeTrack> albumTracks = NativeMusicProvider.parseAlbumTracks(album, 200);
        equal(7, albumTracks.size()); // Current selected server edition includes two EASY versions.
        String[] albumOrder = {"Good Bones", "EASY", "Swan Song", "Smart", "We got so much",
                "EASY (Sped Up ver.)", "EASY (Slowed + Reverb ver.)"};
        for (int i = 0; i < albumOrder.length; i++) {
            equal(albumOrder[i], albumTracks.get(i).title);
            equal("LE SSERAFIM", albumTracks.get(i).artist);
            check(albumTracks.get(i).durationSeconds > 0, "album duration retained");
        }
        equal(2, NativeMusicProvider.parseAlbumTracks(album, 2).size());
        JSONObject drama = new JSONObject(Files.readString(fixtureDir.resolve("album-drama.json")));
        JSONObject dramaHeader = drama.getJSONObject("contents").getJSONObject("twoColumnBrowseResultsRenderer")
                .getJSONArray("tabs").getJSONObject(0).getJSONObject("tabRenderer").getJSONObject("content")
                .getJSONObject("sectionListRenderer").getJSONArray("contents").getJSONObject(0).getJSONObject("musicResponsiveHeaderRenderer");
        String dramaCover = dramaHeader.getJSONObject("thumbnail").getJSONObject("musicThumbnailRenderer")
                .getJSONObject("thumbnail").getJSONArray("thumbnails").getJSONObject(3).getString("url");
        List<NativeTrack> dramaTracks = NativeMusicProvider.parseAlbumTracks(drama, 200);
        equal(6, dramaTracks.size());
        for (NativeTrack track : dramaTracks) {
            equal(dramaCover, track.thumbnailUrl);
            equal("aespa", track.artist);
            equal("Drama - The 4th Mini Album", track.album);
        }
        JSONObject dramaWithRowArt = copy(drama);
        JSONObject firstDramaRow = dramaWithRowArt.getJSONObject("contents").getJSONObject("twoColumnBrowseResultsRenderer")
                .getJSONObject("secondaryContents").getJSONObject("sectionListRenderer").getJSONArray("contents")
                .getJSONObject(0).getJSONObject("musicShelfRenderer").getJSONArray("contents")
                .getJSONObject(0).getJSONObject("musicResponsiveListItemRenderer");
        firstDramaRow.put("thumbnail", new JSONObject().put("musicThumbnailRenderer", new JSONObject().put("thumbnail",
                new JSONObject().put("thumbnails", new JSONArray().put(image("https://example.com/explicit-track-cover",640))))));
        List<NativeTrack> explicitArt = NativeMusicProvider.parseAlbumTracks(dramaWithRowArt, 200);
        equal("https://example.com/explicit-track-cover", explicitArt.get(0).thumbnailUrl);
        equal(dramaCover, explicitArt.get(1).thumbnailUrl);
        equal(dramaTracks.get(0).videoId, explicitArt.get(0).videoId);
        equal(dramaTracks.get(0).isVideoSource, explicitArt.get(0).isVideoSource);
        dramaHeader.remove("thumbnail");
        dramaHeader.put("straplineThumbnail", new JSONObject().put("thumbnails",
                new JSONArray().put(image("https://example.com/artist-avatar",640))));
        for (NativeTrack track : NativeMusicProvider.parseAlbumTracks(drama,200,"https://example.com/selected-album-cover"))
            equal("https://example.com/selected-album-cover",track.thumbnailUrl);
        for (NativeTrack track : NativeMusicProvider.parseAlbumTracks(drama,200)) equal("",track.thumbnailUrl);
        for (NativeTrack track : NativeMusicProvider.parseAlbumTracks(drama,200,"http://example.com/insecure-cover")) equal("",track.thumbnailUrl);

        JSONObject withRecommendations = copy(album);
        JSONObject columns = withRecommendations.getJSONObject("contents").getJSONObject("twoColumnBrowseResultsRenderer");
        JSONObject unrelated = new JSONObject().put("contents", new JSONArray().put(wrap(firstRow(search))))
                .put("title", new JSONObject().put("simpleText", "You might also like"));
        columns.put("tabs", new JSONArray().put(new JSONObject().put("tabRenderer", new JSONObject().put("selected", true)
                .put("content", new JSONObject().put("sectionListRenderer", new JSONObject().put("contents", new JSONArray()
                        .put(new JSONObject().put("musicPlaylistShelfRenderer", unrelated))))))));
        columns.getJSONObject("secondaryContents").getJSONObject("sectionListRenderer").getJSONArray("contents")
                .put(new JSONObject().put("musicShelfRenderer", unrelated));
        equal(7, NativeMusicProvider.parseAlbumTracks(withRecommendations, 200).size());
        equal("Good Bones", NativeMusicProvider.parseAlbumTracks(withRecommendations, 200).get(0).title);
        JSONObject onlyRelated = new JSONObject().put("contents", new JSONObject().put("sectionListRenderer",
                new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("musicShelfRenderer", unrelated)))));
        equal(0, NativeMusicProvider.parseAlbumTracks(onlyRelated, 200).size());
        JSONArray repeatedAlbumRows = new JSONArray().put(wrap(firstRow(search))).put(wrap(firstRow(search)));
        for (String type : new String[]{"musicPlaylistShelfContinuation", "musicShelfContinuation"}) {
            JSONObject continuation = new JSONObject().put("continuationContents", new JSONObject().put(type,
                    new JSONObject().put("contents", repeatedAlbumRows)));
            equal(2, NativeMusicProvider.parseAlbumTracks(continuation, 200).size());
        }
        JSONObject appended = new JSONObject().put("onResponseReceivedActions", new JSONArray().put(new JSONObject()
                .put("appendContinuationItemsAction", new JSONObject().put("continuationItems", repeatedAlbumRows))));
        equal(2, NativeMusicProvider.parseAlbumTracks(appended, 200).size());
        equal(1, NativeMusicProvider.parseTracks(appended).size()); // Search dedup and album order have different requirements.

        JSONObject row = firstRow(search);
        JSONObject artwork = copy(row);
        JSONArray renditions = new JSONArray().put(image("https://example.com/sixty",60))
                .put(image("https://example.com/three-twenty",320)).put(image("https://example.com/five-hundred",500));
        artwork.put("thumbnail", new JSONObject().put("musicThumbnailRenderer", new JSONObject().put("thumbnail",
                new JSONObject().put("thumbnails",renditions))));
        equal("https://example.com/five-hundred",parse(artwork).get(0).thumbnailUrl);
        renditions.put(image("https://example.com/twelve-eighty",1280)).put(image("https://example.com/six-forty",640));
        equal("https://example.com/six-forty",parse(artwork).get(0).thumbnailUrl);
        renditions.put(image("http://example.com/insecure",700));
        equal("https://example.com/six-forty",parse(artwork).get(0).thumbnailUrl);
        JSONObject copy = copy(row);
        copy.remove("playlistItemData");
        equal("LBhcqYqeu0U", parse(copy).get(0).videoId); // Title endpoint fallback.
        copy.put("fixedColumns", new JSONArray().put(new JSONObject().put("musicResponsiveListItemFixedColumnRenderer",
                new JSONObject().put("text", new JSONObject().put("simpleText", "1:02:03")))));
        equal(3723, parse(copy).get(0).durationSeconds);

        JSONObject artists = copy(row);
        JSONArray artistColumns = artists.getJSONArray("flexColumns");
        JSONArray runs = new JSONArray().put(linked("Artist A", "MUSIC_PAGE_TYPE_ARTIST"))
                .put(new JSONObject().put("text", " & ")).put(linked("Artist B", "MUSIC_PAGE_TYPE_ARTIST"))
                .put(new JSONObject().put("text", " • ")).put(linked("Album name", "MUSIC_PAGE_TYPE_ALBUM"));
        artistColumns.put(1, new JSONObject().put("musicResponsiveListItemFlexColumnRenderer", new JSONObject().put("text", new JSONObject().put("runs", runs))));
        equal("Artist A, Artist B", parse(artists).get(0).artist);
        equal("Album name", parse(artists).get(0).album);

        JSONArray duplicate = new JSONArray().put(wrap(row)).put(wrap(copy(row)));
        equal(1, NativeMusicProvider.parseTracks(new JSONObject().put("rows", duplicate)).size());
        JSONObject invalid = copy(row).put("playlistItemData", new JSONObject().put("videoId", "not-an-id"));
        equal(0, parse(invalid).size());
        equal(0, parse(new JSONObject()).size());
        JSONObject privateRow = copy(row);
        privateRow.getJSONArray("flexColumns").getJSONObject(0).getJSONObject("musicResponsiveListItemFlexColumnRenderer")
                .put("text", new JSONObject().put("simpleText", "Private video"));
        equal(0, parse(privateRow).size());
        privateRow.getJSONArray("flexColumns").getJSONObject(0).getJSONObject("musicResponsiveListItemFlexColumnRenderer")
                .put("text", new JSONObject().put("simpleText", "[Deleted video]"));
        equal(0, parse(privateRow).size());

        JSONArray many = new JSONArray();
        for (int index = 0; index < 75; index++) {
            JSONObject item = copy(row).put("playlistItemData", new JSONObject().put("videoId", String.format(java.util.Locale.ROOT, "%011d", index)));
            many.put(wrap(item));
        }
        equal(60, NativeMusicProvider.parseTracks(new JSONObject().put("items", many)).size());
        JSONObject nested = wrap(row);
        for (int depth = 0; depth < 60; depth++) nested = new JSONObject().put("nested", nested);
        equal(0, NativeMusicProvider.parseTracks(nested).size());
        equal(0, NativeMusicProvider.parseTracks(new JSONObject()).size());
        equal("empty_likes", NativeMusicProvider.classifyLibraryMessage(message("Songs you like appear here.")));
        equal("empty_likes", NativeMusicProvider.classifyLibraryMessage(message("You haven’t liked any music yet.")));
        equal("empty_likes", NativeMusicProvider.classifyLibraryMessage(message("This playlist has no videos.")));
        equal("sign_in_required", NativeMusicProvider.classifyLibraryMessage(message("Sign in to see this playlist.")));
        equal("unavailable", NativeMusicProvider.classifyLibraryMessage(message("This playlist is not available.")));
        equal("other_message", NativeMusicProvider.classifyLibraryMessage(message("A future service format.")));
        equal("no_message", NativeMusicProvider.classifyLibraryMessage(new JSONObject()));
        equal("empty_likes", NativeMusicProvider.classifyLibraryMessage(new JSONObject().put("messageRenderer", "No songs yet.")));
        equal("empty_likes", NativeMusicProvider.classifyLibraryMessage(new JSONObject().put("messageRenderer",
                new JSONObject().put("text", "This playlist has no videos."))));
        equal("empty_likes", NativeMusicProvider.classifyLibraryMessage(new JSONObject().put("messageRenderer",
                new JSONObject().put("subtext", new JSONObject().put("text", new JSONObject().put("simpleText", "Songs you like appear here."))))));
        equal("sign_in_required", NativeMusicProvider.classifyLibraryMessage(new JSONObject().put("messageRenderer",
                new JSONObject().put("title", "Sign in").put("subtext", "See your music."))));
        JSONObject hiddenValue = new JSONObject().put("messageRenderer", new JSONObject().put("text", "PRIVATE_VALUE_DO_NOT_LOG")
                .put("token", "PRIVATE_TOKEN_DO_NOT_LOG"));
        String shape = NativeMusicProvider.messageStructure(hiddenValue);
        check(shape.contains("text:string") && shape.contains("token:string"), "diagnostics identify schema and types");
        check(!shape.contains("PRIVATE_"), "diagnostics do not include message or token values");
        equal("no_message", NativeMusicProvider.classifyLibraryMessage(new JSONObject().put("messageRenderer",
                new JSONObject().put("token", "Sign in"))));
        equal(Boolean.TRUE, NativeMusicProvider.configValue("{\"LOGGED_IN\":false}; {\"LOGGED_IN\":true}", "LOGGED_IN"));
        equal("1", NativeMusicProvider.configValue("{\"SESSION_INDEX\":\"0\"}; {\"SESSION_INDEX\":\"1\"}", "SESSION_INDEX"));
        equal("test_channel", NativeMusicProvider.configValue("{\"DELEGATED_SESSION_ID\":\"test_channel\"}", "DELEGATED_SESSION_ID"));
        check(NativeMusicProvider.configValue("{\"SESSION_INDEX\":null}", "SESSION_INDEX") == null, "null session configuration remains absent");
        System.out.println("Native catalogue parser checks passed: " + checks);
    }
    private static JSONObject firstRow(JSONObject value) throws Exception {
        return value.getJSONObject("contents").getJSONObject("sectionListRenderer").getJSONArray("contents")
                .getJSONObject(0).getJSONObject("musicShelfRenderer").getJSONArray("contents")
                .getJSONObject(0).getJSONObject("musicResponsiveListItemRenderer");
    }
    private static JSONObject linked(String text, String type) throws Exception {
        return new JSONObject().put("text", text).put("navigationEndpoint", new JSONObject().put("browseEndpoint",
                new JSONObject().put("browseEndpointContextSupportedConfigs", new JSONObject().put("browseEndpointContextMusicConfig",
                        new JSONObject().put("pageType", type)))));
    }
    private static JSONObject image(String url,int pixels) throws Exception {
        return new JSONObject().put("url",url).put("width",pixels).put("height",pixels);
    }
    private static JSONObject message(String text) throws Exception {
        return new JSONObject().put("contents", new JSONObject().put("messageRenderer",
                new JSONObject().put("text", new JSONObject().put("simpleText", text))));
    }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static JSONObject wrap(JSONObject row) throws Exception { return new JSONObject().put("musicResponsiveListItemRenderer", row); }
    private static List<NativeTrack> parse(JSONObject row) throws Exception { return NativeMusicProvider.parseTracks(wrap(row)); }
    private static void equal(Object expected, Object actual) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("Check " + checks + ": expected " + expected + "; got " + actual);
    }
    private static void check(boolean good, String message) { checks++; if (!good) throw new AssertionError(message); }
}
